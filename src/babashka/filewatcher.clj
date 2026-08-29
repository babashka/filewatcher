(ns babashka.filewatcher
  "Watch files and directories for changes, with the same events on every
  operating system.

      (require '[babashka.filewatcher :as fw])

      (def w (fw/watch \"src\" (fn [event] (prn event))))
      ;; {:type :add, :path \"src/app.clj\"}
      ;; {:type :ready}
      ;; {:type :change, :path \"src/app.clj\"}

      (fw/unwatch w)

  The backend is FSEvents on macOS, inotify on Linux, ReadDirectoryChangesW
  on Windows, and a timer with :use-polling. The events come from a
  comparison of the file system with a tree the watcher keeps, so every
  backend reports the same events for the same changes."
  (:require [babashka.filewatcher.impl.core :as core]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(defrecord Watcher [paths])

(def ^:private os
  (let [name (System/getProperty "os.name")]
    (cond (str/starts-with? name "Mac") :mac
          (str/starts-with? name "Linux") :linux
          (str/starts-with? name "Windows") :windows
          :else :other)))

(defn- backend-fn [{:keys [use-polling]}]
  (requiring-resolve
   (if use-polling
     'babashka.filewatcher.impl.polling/make-backend
     (case os
       :mac 'babashka.filewatcher.impl.fsevents/make-backend
       :linux 'babashka.filewatcher.impl.inotify/make-backend
       :windows 'babashka.filewatcher.impl.windows/make-backend
       'babashka.filewatcher.impl.polling/make-backend))))

(defn- root [p]
  (let [given (str p)
        abs (str (fs/normalize (fs/absolutize given)))
        st (core/stat abs false)]
    (when-not st
      (throw (ex-info (str "filewatcher: no such file or directory: " given) {:path given})))
    {:given given :abs abs :prefix (str abs fs/file-separator) :dir? (:dir? st)}))

(def ^:private default-awf {:stability-threshold 2000 :poll-interval 100})

(defn watch
  "Watches paths and calls f with an event map for every change. paths is
  one path or a collection of paths, each a file or a directory. Returns a
  watcher for unwatch.

  Events are maps with :type and :path. The path is the watched path as
  given, extended with the part below it. The types:

  - :add, :change, :unlink for a file
  - :add-dir, :unlink-dir for a directory
  - :ready once, after the first scan
  - :error with an :error key, for a failure in the watcher or in f

  f runs on the watcher's thread, one event at a time, in order.

  Options:

  - :ignored: a predicate over the path, a regex, a glob string, or a
    collection of these. An ignored directory is not entered.
  - :ignore-initial: true suppresses the :add and :add-dir events for what
    exists when the watch starts. Default false.
  - :depth: how many levels of subdirectories to enter. 0 watches only the
    entries of the paths. Default unlimited.
  - :follow-symlinks: true stats through symbolic links. Default false.
  - :await-write-finish: true, or {:stability-threshold ms :poll-interval ms},
    holds :add and :change for a file until its size and time stop changing
    for stability-threshold ms (default 2000, checked every 100). Default
    false.
  - :atomic: true hides editor temp files and reports a file replaced by a
    rename as one :change. Default true, except with :use-polling.
  - :use-polling: true compares the tree every :interval ms (default 100)
    instead of listening to the operating system. Use for network and
    container file systems.
  - :delay-ms: how long to collect changes to a path before reporting them.
    Default 50."
  ([paths f] (watch paths f nil))
  ([paths f opts]
   (let [paths (if (or (string? paths) (not (coll? paths))) [paths] (vec paths))
         _ (when (empty? paths)
             (throw (ex-info "filewatcher: watch needs at least one path" {})))
         opts (merge {:ignore-initial false :follow-symlinks false
                      :atomic (not (:use-polling opts)) :interval 100 :delay-ms 50}
                     opts)
         awf (let [a (:await-write-finish opts)]
               (cond (map? a) (merge default-awf a)
                     a default-awf))
         roots (mapv root paths)
         w {:f f
            :roots roots
            :ignored (core/ignore-fn (:ignored opts))
            :ignore-initial (:ignore-initial opts)
            :depth (:depth opts)
            :follow-symlinks (:follow-symlinks opts)
            :await-write-finish awf
            :atomic (:atomic opts)
            :delay-ms (:delay-ms opts)
            :queue (java.util.concurrent.LinkedBlockingQueue.)
            :tree (atom {})
            :file-roots (atom {})
            :pending (atom {})
            :held (atom {})
            :awf (atom {})
            :initial (atom true)
            :running (atom true)
            :thread (atom nil)}
         w (assoc w :backend ((backend-fn opts) w opts))]
     (core/start! w)
     (with-meta (->Watcher (mapv :given roots)) {::impl w}))))

(defn unwatch
  "Stops a watcher from watch. No events follow. Returns nil."
  [watcher]
  (core/stop! (::impl (meta watcher))))

(defn watched
  "Returns a map of each watched directory, as the user sees it, to the
  sorted names of its entries."
  [watcher]
  (core/watched (::impl (meta watcher))))
