(ns babashka.filewatcher.impl.core
  "The part of the watcher that is the same on every operating system.

  A backend (FSEvents, inotify, ReadDirectoryChangesW, polling) only says
  which path may have changed. This namespace keeps a tree of the watched
  directories with a stat per entry, and turns each hint into events by
  comparing the file system with the tree. The events are therefore the
  same on every backend: the backend decides when to look, the tree decides
  what to say.

  One dispatcher thread owns the tree, coalesces hints, and calls the
  user's function. Backends never call the user's function."
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(set! *warn-on-reflection* true)

;;;; stat

(defn stat
  "Returns {:dir? :size :mtime} for path, or nil when it does not exist."
  [path follow-symlinks?]
  (try
    (let [a (fs/read-attributes path "basic:*"
                                {:nofollow-links (not follow-symlinks?)})]
      {:dir? (boolean (:isDirectory a))
       :size (long (:size a))
       :mtime (.toMillis ^java.nio.file.attribute.FileTime (:lastModifiedTime a))})
    (catch java.nio.file.NoSuchFileException _ nil)
    (catch java.io.IOException _ nil)))

(defn same-file? [a b]
  (and (= (:size a) (:size b)) (= (:mtime a) (:mtime b))))

;;;; paths

(defn join [dir name]
  (str (fs/path dir name)))

(defn user-path
  "The path as the user sees it: the root as given, extended with the
  relative part."
  [{:keys [given abs]} path]
  (if (= abs path)
    given
    (let [rel (str (fs/relativize abs path))]
      (if (= "." given)
        rel
        (join given rel)))))

(defn root-of [w path]
  (some (fn [r] (when (or (= (:abs r) path)
                          (str/starts-with? path (:prefix r)))
                  r))
        (:roots w)))

;;;; ignore

(defn ignore-fn
  "Turns the :ignored option into a predicate over the user-facing path."
  [ignored]
  (cond
    (nil? ignored) (constantly false)
    (fn? ignored) ignored
    (instance? java.util.regex.Pattern ignored) #(boolean (re-find ignored %))
    (string? ignored) (let [m (.getPathMatcher (java.nio.file.FileSystems/getDefault)
                                               (str "glob:" ignored))]
                        #(.matches m (fs/path %)))
    (coll? ignored) (let [fs (mapv ignore-fn ignored)]
                      (fn [p] (boolean (some #(% p) fs))))
    :else (throw (ex-info (str "filewatcher: :ignored must be a function, regex, glob string or a collection of those, got " (pr-str ignored))
                          {:ignored ignored}))))

;; editor save patterns that :atomic hides, as in chokidar
(def atomic-temp #"(?:^|[\\/])\..*\.sw[px]$|~$|\.subl.*\.tmp$")

(defn ignored? [w root path]
  (let [up (user-path root path)]
    (or ((:ignored w) up)
        (and (:atomic w) (boolean (re-find atomic-temp up))))))

;;;; events

(defn deliver! [w ev]
  (let [f (:f w)]
    (try
      (f ev)
      (catch Throwable t
        (if (= :error (:type ev))
          (binding [*out* *err*]
            (println "filewatcher: the callback threw on an error event:" (ex-message t)))
          (try (f {:type :error :error t})
               (catch Throwable t2
                 (binding [*out* *err*]
                   (println "filewatcher: the callback threw:" (ex-message t2))))))))))

(defn emit! [w type root path]
  (when-not (and @(:initial w) (:ignore-initial w))
    (deliver! w {:type type :path (user-path root path)})))

;;;; the tree
;;
;; tree: abs dir -> {:root r :level n :entries {name stat}}
;; file-roots: abs file -> stat
;; held: abs file -> deadline, an unlink that :atomic holds back
;; awf: abs file -> {:type :add|:change :last stat :since ms :due ms}

(defn descend? [w level]
  (let [d (:depth w)]
    (or (nil? d) (<= level d))))

(declare scan-dir!)

(defn cancel-hold! [w path]
  (let [held (:held w)]
    (when (contains? @held path)
      (swap! held dissoc path)
      true)))

(defn file-event!
  "Emits :add or :change for a file, through the await-write-finish gate
  when that option is on."
  [w type root path st]
  (if-let [awf (:await-write-finish w)]
    (let [now (System/currentTimeMillis)]
      (swap! (:awf w) update path
             (fn [old]
               (if (and old (same-file? (:last old) st))
                 old
                 {:type (or (:type old) type) :root root :last st :since now
                  :due (+ now (:poll-interval awf))}))))
    (emit! w type root path)))

(defn add-node! [w dir name st]
  (let [path (join dir name)
        {:keys [root level]} (get @(:tree w) dir)]
    (when-not (ignored? w root path)
      (if (:dir? st)
        (do
          (swap! (:tree w) assoc-in [dir :entries name] st)
          (emit! w :add-dir root path)
          (when (descend? w (inc level))
            ((:watch-dir! (:backend w)) path)
            (swap! (:tree w) assoc path {:root root :level (inc level) :entries {}})
            (scan-dir! w path false)))
        (do
          (swap! (:tree w) assoc-in [dir :entries name] st)
          (if (cancel-hold! w path)
            (file-event! w :change root path st)
            (file-event! w :add root path st)))))))

(defn unlink-file!
  "Emits :unlink for a file. With hold? and :atomic, the event waits 100 ms
  for an :add of the same path, which makes the pair one :change."
  [w root path hold?]
  (swap! (:awf w) dissoc path)
  (if (and hold? (:atomic w))
    (swap! (:held w) assoc path {:root root :due (+ (System/currentTimeMillis) 100)})
    (emit! w :unlink root path)))

(defn release-held-under!
  "Emits the held unlinks below dir, so that a file's :unlink always comes
  before its directory's :unlink-dir, also when the backend reported the
  file and the directory in separate batches."
  [w dir]
  (let [prefix (str dir fs/file-separator)
        under (filter (fn [[p _]] (str/starts-with? p prefix)) @(:held w))]
    (doseq [[path {:keys [root]}] (sort-by key under)]
      (swap! (:held w) dissoc path)
      (emit! w :unlink root path))))

(defn remove-node!
  "Removes an entry from the tree and emits its :unlink or :unlink-dir.
  For a directory the children go first, as in chokidar. Their unlinks are
  not held for :atomic: a file in a removed directory is not replaced."
  ([w dir name] (remove-node! w dir name true))
  ([w dir name hold?]
   (let [path (join dir name)
         {:keys [root]} (get @(:tree w) dir)
         st (get-in @(:tree w) [dir :entries name])]
     (swap! (:tree w) update-in [dir :entries] dissoc name)
     (if (:dir? st)
       (do
         (when-let [node (get @(:tree w) path)]
           (doseq [child (sort (keys (:entries node)))]
             (remove-node! w path child false))
           ((:unwatch-dir! (:backend w)) path)
           (swap! (:tree w) dissoc path))
         (release-held-under! w path)
         (emit! w :unlink-dir root path))
       (unlink-file! w root path hold?)))))

(defn list-entries
  "Returns {name stat} for the entries of dir, or nil when dir is gone."
  [w dir]
  (try
    (into {}
          (keep (fn [p]
                  (when-let [st (stat p (:follow-symlinks w))]
                    [(fs/file-name p) st])))
          (fs/list-dir dir))
    ;; NoSuchFileException and NotDirectoryException are IOExceptions
    (catch java.io.IOException _ nil)))

(defn scan-dir!
  "Compares dir with the tree and emits the difference. With subtree?
  every known subdirectory is compared as well."
  [w dir subtree?]
  (when-let [node (get @(:tree w) dir)]
    (let [known (:entries node)
          now (list-entries w dir)]
      (if (nil? now)
        ;; the directory is gone. A backend may report the directory itself
        ;; and never its parent, so remove it from the parent here
        (let [parent (some-> (fs/parent dir) str)]
          (if (contains? @(:tree w) parent)
            (remove-node! w parent (fs/file-name dir))
            ;; a root: empty it and report it, but keep watching the path
            (do (doseq [name (sort (keys known))]
                  (remove-node! w dir name false))
                (release-held-under! w dir)
                (emit! w :unlink-dir (:root node) dir))))
        (do
          (doseq [name (sort (keys known))
                  :when (not (contains? now name))]
            (remove-node! w dir name))
          (doseq [name (sort (keys now))
                  :let [st (get now name)
                        old (get known name)]]
            (cond
              (nil? old) (add-node! w dir name st)
              (not= (:dir? old) (:dir? st)) (do (remove-node! w dir name)
                                                (add-node! w dir name st))
              (:dir? st) (when subtree? (scan-dir! w (join dir name) true))
              (not (same-file? old st))
              (do (swap! (:tree w) assoc-in [dir :entries name] st)
                  (file-event! w :change (:root node) (join dir name) st)))))))))

(defn refresh-file-root! [w path]
  (let [root (root-of w path)
        old (get @(:file-roots w) path)
        st (stat path (:follow-symlinks w))]
    (cond
      (and (nil? old) (nil? st)) nil
      (nil? st) (do (swap! (:file-roots w) dissoc path)
                    (unlink-file! w root path true))
      (nil? old) (do (swap! (:file-roots w) assoc path st)
                     (if (cancel-hold! w path)
                       (file-event! w :change root path st)
                       (file-event! w :add root path st)))
      (not (same-file? old st)) (do (swap! (:file-roots w) assoc path st)
                                    (file-event! w :change root path st)))))

(defn refresh!
  "Turns a hint about path into events. subtree? asks for a full
  comparison below path, for a backend that lost events."
  [w path subtree?]
  (let [tree @(:tree w)]
    (cond
      (contains? tree path) (scan-dir! w path subtree?)
      (some #(= path (:abs %)) (:roots w)) (refresh-file-root! w path)
      :else
      (let [dir (some-> (fs/parent path) str)
            name (fs/file-name path)]
        (if-let [node (get tree dir)]
          (let [old (get-in node [:entries name])
                st (stat path (:follow-symlinks w))]
            (cond
              (and (nil? old) (nil? st)) nil
              (nil? st) (remove-node! w dir name)
              (nil? old) (add-node! w dir name st)
              (not= (:dir? old) (:dir? st)) (do (remove-node! w dir name)
                                                (add-node! w dir name st))
              (:dir? st) (scan-dir! w path subtree?)
              (not (same-file? old st))
              (do (swap! (:tree w) assoc-in [dir :entries name] st)
                  (file-event! w :change (:root node) path st))))
          ;; below the depth limit, or under a directory not yet scanned:
          ;; the scan of that directory reports its contents
          nil)))))

;;;; the dispatcher

(defn earliest-due [w]
  (let [dues (concat (map :due (vals @(:pending w)))
                     (map :due (vals @(:held w)))
                     (map :due (vals @(:awf w))))]
    (when (seq dues) (reduce min dues))))

(defn process-due! [w now]
  ;; hints, in path order so the events of one batch are deterministic
  (let [due (into (sorted-map)
                  (filter (fn [[_ v]] (<= (:due v) now)))
                  @(:pending w))]
    (swap! (:pending w) #(apply dissoc % (keys due)))
    (doseq [[path {:keys [subtree?]}] due]
      (refresh! w path subtree?)))
  ;; unlinks that :atomic held back and no add cancelled
  (let [due (filter (fn [[_ v]] (<= (:due v) now)) @(:held w))]
    (doseq [[path {:keys [root]}] (sort-by key due)]
      (swap! (:held w) dissoc path)
      (emit! w :unlink root path)))
  ;; files waiting for their size to settle
  (when-let [awf (:await-write-finish w)]
    (let [due (filter (fn [[_ v]] (<= (:due v) now)) @(:awf w))]
      (doseq [[path {:keys [type root last since]}] (sort-by key due)]
        (let [st (stat path (:follow-symlinks w))]
          (cond
            (nil? st) (do (swap! (:awf w) dissoc path)
                          ;; a file that vanished before its add: no event;
                          ;; a change that vanished: the next hint reports
                          ;; the unlink
                          nil)
            (and (same-file? last st)
                 (>= (- now since) (:stability-threshold awf)))
            (do (swap! (:awf w) dissoc path)
                (emit! w type root path))
            :else
            (swap! (:awf w) assoc path
                   {:type type :root root
                    :last st
                    :since (if (same-file? last st) since now)
                    :due (+ now (:poll-interval awf))})))))))

(defn hint!
  "Called by a backend, from any thread."
  [w path subtree?]
  (.put ^LinkedBlockingQueue (:queue w) [path subtree?]))

(defn error!
  "Called by a backend, from any thread."
  [w e]
  (.put ^LinkedBlockingQueue (:queue w) [::error e]))

(defn run-dispatcher [w]
  (let [^LinkedBlockingQueue q (:queue w)]
    (loop []
      (let [due (earliest-due w)
            now (System/currentTimeMillis)
            item (if due
                   (.poll q (max 0 (- due now)) TimeUnit/MILLISECONDS)
                   (.take q))]
        (cond
          (= ::stop item) nil
          (nil? item) (do (process-due! w (System/currentTimeMillis))
                          (recur))
          (= ::error (first item)) (do (deliver! w {:type :error :error (second item)})
                                       (recur))
          :else
          (let [[path subtree?] item
                due (+ (System/currentTimeMillis) (:delay-ms w))]
            (swap! (:pending w) update path
                   (fn [old] {:due (if old (min due (:due old)) due)
                              :subtree? (boolean (or subtree? (:subtree? old)))}))
            (recur)))))))

;;;; start and stop

(defn initial-scan! [w]
  (doseq [r (:roots w)]
    (if (:dir? r)
      (do ((:watch-dir! (:backend w)) (:abs r))
          (swap! (:tree w) assoc (:abs r) {:root r :level 0 :entries {}})
          (scan-dir! w (:abs r) false))
      (refresh-file-root! w (:abs r))))
  (reset! (:initial w) false)
  (deliver! w {:type :ready}))

(defn start! [w]
  (let [backend (:backend w)]
    ((:start! backend))
    ;; the backend is live before the first scan, so nothing between the
    ;; scan and the start is missed
    (let [t (Thread. (fn []
                       (try
                         (initial-scan! w)
                         (run-dispatcher w)
                         (catch Throwable t
                           (deliver! w {:type :error :error t}))))
                     "filewatcher-dispatch")]
      ;; a persistent watcher keeps the process alive until close: a
      ;; non-daemon thread does that on both hosts
      (.setDaemon t (not (:persistent w)))
      (.start t)
      (reset! (:thread w) t))
    w))

(defn stop! [w]
  (when (compare-and-set! (:running w) true false)
    ((:stop! (:backend w)))
    (.put ^LinkedBlockingQueue (:queue w) ::stop)
    (when-let [^Thread t @(:thread w)]
      (.join t 5000)))
  nil)

(defn watched
  "Returns {user dir [names]} for every watched directory."
  [w]
  (into (sorted-map)
        (map (fn [[dir {:keys [root entries]}]]
               [(user-path root dir) (vec (sort (keys entries)))]))
        @(:tree w)))
