(ns babashka.filewatcher-test)

;; the test macro for clj-kondo: the events atom and the watcher are bound,
;; the body runs, and the watcher is unwatched
(defmacro with-watch [[events w dir opts] & body]
  `(let [~events (atom [])
         ~w (babashka.filewatcher/watch ~dir identity ~opts)]
     (try ~@body
          (finally (babashka.filewatcher/unwatch ~w)))))
