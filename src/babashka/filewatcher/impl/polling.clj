(ns babashka.filewatcher.impl.polling
  "The backend every operating system shares: a timer that asks the core to
  compare every root with its tree. No operating system events are used.
  For network and container file systems, and as the reference for the
  other backends: they must produce what this one produces."
  {:no-doc true}
  (:require [babashka.filewatcher.impl.core :as core]))

(defn make-backend [w {:keys [interval]}]
  (let [running (atom false)
        thread (atom nil)]
    {:start! (fn []
               (reset! running true)
               (let [t (Thread. (fn []
                                  (loop []
                                    (when @running
                                      (try (Thread/sleep (long interval))
                                           (catch InterruptedException _ nil))
                                      (when @running
                                        (doseq [r (:roots w)]
                                          (core/hint! w (:abs r) true))
                                        (recur)))))
                                "filewatcher-poll")]
                 (.setDaemon t true)
                 (.start t)
                 (reset! thread t)))
     :stop! (fn []
              (reset! running false)
              (when-let [^Thread t @thread]
                (.interrupt t)
                (.join t 5000)))
     :watch-dir! (fn [_])
     :unwatch-dir! (fn [_])}))
