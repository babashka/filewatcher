(ns babashka.filewatcher.impl.inotify
  "The Linux backend. inotify watches one directory per watch descriptor,
  so the core registers every directory it enters through watch-dir!. A
  reader thread polls the inotify descriptor and turns each record into a
  hint. A queue overflow asks for a full comparison of every root."
  {:no-doc true}
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [babashka.filewatcher.impl.core :as core]))

(defcfn inotify-init1 "inotify_init1" [:int] :int)
(defcfn inotify-add-watch "inotify_add_watch" [:int :string :uint32] :int)
(defcfn inotify-rm-watch "inotify_rm_watch" [:int :int] :int)
(defcfn c-read "read" [:int :pointer :size_t] :ssize_t)
(defcfn c-poll "poll" [:pointer :uint64 :int] :int)
(defcfn c-close "close" [:int] :int)
(defcfn errno-location "__errno_location" [] :pointer)

(defn errno [] (ffi/read (ffi/reinterpret (errno-location) 4) :int))

(def IN_CLOEXEC 0x80000)
(def IN_MODIFY 0x2)
(def IN_ATTRIB 0x4)
(def IN_MOVED_FROM 0x40)
(def IN_MOVED_TO 0x80)
(def IN_CREATE 0x100)
(def IN_DELETE 0x200)
(def IN_DELETE_SELF 0x400)
(def IN_MOVE_SELF 0x800)
(def IN_Q_OVERFLOW 0x4000)
(def IN_IGNORED 0x8000)
(def IN_DONT_FOLLOW 0x2000000)

(def mask (bit-or IN_MODIFY IN_ATTRIB IN_MOVED_FROM IN_MOVED_TO IN_CREATE
                  IN_DELETE IN_DELETE_SELF IN_MOVE_SELF IN_DONT_FOLLOW))

(def POLLIN 1)
(def EAGAIN 11)
(def EINTR 4)
(def ENOSPC 28)

(def buffer-size 65536)

(defn add-watch! [w fd wds path]
  (let [wd (inotify-add-watch fd path mask)]
    (if (neg? wd)
      (let [e (errno)]
        (core/error! w (ex-info (str "filewatcher: inotify_add_watch failed for " path
                                     (if (= e ENOSPC)
                                       ": the inotify watch limit is reached (fs.inotify.max_user_watches)"
                                       (str ": errno " e)))
                                {:path path :errno e})))
      (swap! wds (fn [m] (-> m (assoc-in [:by-wd wd] path) (assoc-in [:by-path path] wd)))))))

(defn read-events!
  "Reads what is available and hands each record to the core. Returns
  false when the descriptor is closed."
  [w fd buf wds]
  (let [n (c-read fd buf buffer-size)]
    (cond
      (neg? n) (let [e (errno)]
                 (or (= e EAGAIN) (= e EINTR)))
      (zero? n) false
      :else
      (loop [off 0]
        (if-not (< off n)
          true
          (let [wd (ffi/read buf :int off)
                m (ffi/read buf :uint32 (+ off 4))
                len (ffi/read buf :uint32 (+ off 12))
                name (when (pos? len)
                       (let [s (ffi/ptr->string (ffi/slice buf (+ off 16) len) len)]
                         (when-not (= "" s) s)))]
            (cond
              (pos? (bit-and m IN_Q_OVERFLOW))
              (doseq [r (:roots w)] (core/hint! w (:abs r) true))

              (pos? (bit-and m IN_IGNORED))
              (when-let [path (get-in @wds [:by-wd wd])]
                (swap! wds (fn [x] (-> x (update :by-wd dissoc wd) (update :by-path dissoc path)))))

              :else
              (when-let [dir (get-in @wds [:by-wd wd])]
                (core/hint! w (if name (core/join dir name) dir) false)))
            (recur (+ off 16 len))))))))

(defn make-backend [w _opts]
  (let [arena (ffi/shared-arena)
        running (atom false)
        thread (atom nil)
        fd (atom nil)
        wds (atom {:by-wd {} :by-path {}})]
    {:start!
     (fn []
       (let [d (inotify-init1 IN_CLOEXEC)]
         (when (neg? d)
           (throw (ex-info (str "filewatcher: inotify_init1 failed: errno " (errno)) {})))
         (reset! fd d)
         ;; a file root is watched by itself: its records carry no name
         (doseq [r (:roots w) :when (not (:dir? r))]
           (add-watch! w d wds (:abs r)))
         (reset! running true)
         (let [buf (ffi/alloc arena buffer-size)
               pollfd (ffi/alloc arena 8)
               t (Thread. (fn []
                            (ffi/write pollfd :int d 0)
                            (ffi/write pollfd :int16 POLLIN 4)
                            (try
                              (loop []
                                (when @running
                                  (ffi/write pollfd :int16 0 6)
                                  (let [rc (c-poll pollfd 1 200)]
                                    (if (and (pos? rc) (pos? (bit-and (ffi/read pollfd :int16 6) POLLIN)))
                                      (when (read-events! w d buf wds) (recur))
                                      (recur)))))
                              (catch Throwable t
                                (when @running (core/error! w t)))))
                          "filewatcher-inotify")]
           (.setDaemon t true)
           (.start t)
           (reset! thread t))))
     :stop!
     (fn []
       (reset! running false)
       (when-let [^Thread t @thread]
         (.join t 5000))
       (when-let [d @fd]
         (reset! fd nil)
         (c-close d))
       (.close arena))
     :watch-dir!
     (fn [path]
       (when-let [d @fd]
         (add-watch! w d wds path)))
     :unwatch-dir!
     (fn [path]
       (when-let [d @fd]
         (when-let [wd (get-in @wds [:by-path path])]
           ;; the kernel already dropped the watch of a removed directory
           (inotify-rm-watch d wd)
           (swap! wds (fn [x] (-> x (update :by-wd dissoc wd) (update :by-path dissoc path)))))))}))
