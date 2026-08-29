(ns babashka.filewatcher-test
  "One suite for every backend. The same file operations must produce the
  same events on macOS, Linux and Windows, and on the polling backend."
  (:require [babashka.filewatcher :as fw]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def polling? (= "true" (System/getProperty "filewatcher.test.polling")))

(defn temp-dir []
  (fs/create-dirs "target")
  (str (fs/absolutize (fs/create-temp-dir {:dir "target" :prefix "fw"}))))

(defn wait-for
  "Waits up to 5 s until pred holds for the collected events."
  [events pred]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond (pred @events) true
            (< (System/currentTimeMillis) deadline) (do (Thread/sleep 20) (recur))
            :else false))))

(defn settle
  "Waits for the watcher to have nothing more to say."
  [events]
  (let [n (count @events)]
    (Thread/sleep 400)
    (if (= n (count @events)) @events (recur events))))

(defn ready? [events] (some #(= :ready (:type %)) events))

(defn of-path [events path]
  (mapv :type (filter #(= path (:path %)) events)))

(defn types [events] (mapv :type (remove #(= :ready (:type %)) events)))

(defmacro with-watch
  "Binds events to an atom of events and w to a watcher on dir with opts,
  waits for :ready, runs body, and closes it."
  [[events w dir opts] & body]
  `(let [~events (atom [])
         ~w (fw/watch ~dir (fn [ev#] (swap! ~events conj ev#))
                      (merge {:use-polling polling?} ~opts))]
     (try
       (is (wait-for ~events ready?) "the watcher reports :ready")
       ~@body
       (finally (fw/close ~w)))))

(defn p [& parts] (str/join fs/file-separator parts))

(deftest initial-scan-test
  (let [dir (temp-dir)]
    (spit (fs/file dir "a.txt") "a")
    (fs/create-dirs (fs/path dir "sub"))
    (spit (fs/file dir "sub" "b.txt") "b")
    (testing "what exists is reported as :add and :add-dir, then :ready"
      (with-watch [events w dir {}]
        (is (= [{:type :add :path (p dir "a.txt")}
                {:type :add-dir :path (p dir "sub")}
                {:type :add :path (p dir "sub" "b.txt")}
                {:type :ready}]
               @events))))
    (testing ":ignore-initial reports only :ready"
      (with-watch [events w dir {:ignore-initial true}]
        (is (= [{:type :ready}] @events))
        (is (= {dir ["a.txt" "sub"] (p dir "sub") ["b.txt"]} (fw/watched w))
            "the tree is built all the same")))))

(deftest file-lifecycle-test
  (let [dir (temp-dir)
        f (p dir "a.txt")]
    (with-watch [events w dir {}]
      (spit f "one")
      (is (wait-for events #(= [:add] (of-path % f))))
      (Thread/sleep 1100) ; past the file time resolution of every file system
      (spit f "two, longer")
      (is (wait-for events #(= [:add :change] (of-path % f))))
      (fs/delete f)
      (is (wait-for events #(= [:add :change :unlink] (of-path % f))))
      (is (= [:add :change :unlink] (types (settle events)))
          "and nothing else"))))

(deftest directory-lifecycle-test
  (let [dir (temp-dir)
        sub (p dir "sub")
        f (p sub "b.txt")]
    (with-watch [events w dir {}]
      (fs/create-dirs sub)
      (is (wait-for events #(= [:add-dir] (of-path % sub))))
      (spit f "b")
      (is (wait-for events #(= [:add] (of-path % f))))
      (fs/delete-tree sub)
      (is (wait-for events #(= [:add-dir :unlink-dir] (of-path % sub))))
      (is (= [:add-dir :add :unlink :unlink-dir] (types (settle events)))
          "the file's :unlink comes before the directory's :unlink-dir"))))

(deftest nested-directory-created-at-once-test
  (let [dir (temp-dir)
        deep (p dir "x" "y" "z")]
    (with-watch [events w dir {}]
      (fs/create-dirs deep)
      (spit (fs/file deep "f.txt") "f")
      (is (wait-for events #(= [:add] (of-path % (p deep "f.txt")))))
      (is (= [:add-dir :add-dir :add-dir :add] (types (settle events)))))))

(deftest rename-test
  (let [dir (temp-dir)
        a (p dir "a.txt")
        b (p dir "b.txt")]
    (spit a "a")
    (with-watch [events w dir {:ignore-initial true}]
      (fs/move a b)
      (is (wait-for events #(and (= [:unlink] (of-path % a)) (= [:add] (of-path % b)))))
      (is (= #{:unlink :add} (set (types (settle events))))))))

(deftest ignored-test
  (let [dir (temp-dir)]
    (fs/create-dirs (fs/path dir "node_modules" "dep"))
    (spit (fs/file dir "node_modules" "dep" "x.js") "x")
    (spit (fs/file dir "a.log") "log")
    (spit (fs/file dir "a.txt") "txt")
    (testing "a regex, a glob and a predicate, in a collection"
      (with-watch [events w dir {:ignored [#"node_modules" "**.log" (fn [path] (str/ends-with? path ".tmp"))]}]
        (is (= [{:type :add :path (p dir "a.txt")} {:type :ready}] @events)
            "an ignored directory is not entered")
        (spit (fs/file dir "b.tmp") "tmp")
        (spit (fs/file dir "b.txt") "txt")
        (is (wait-for events #(= [:add] (of-path % (p dir "b.txt")))))
        (is (= [:add :add] (types (settle events))))))))

(deftest depth-test
  (let [dir (temp-dir)]
    (fs/create-dirs (fs/path dir "l1" "l2"))
    (spit (fs/file dir "f0") "")
    (spit (fs/file dir "l1" "f1") "")
    (spit (fs/file dir "l1" "l2" "f2") "")
    (testing "depth 0: only the entries of the path"
      (with-watch [events w dir {:depth 0}]
        (is (= [:add :add-dir] (types @events)))))
    (testing "depth 1: one level of subdirectories"
      (with-watch [events w dir {:depth 1}]
        (is (= [(p dir "f0") (p dir "l1") (p dir "l1" "f1") (p dir "l1" "l2")]
               (mapv :path (remove #(= :ready (:type %)) @events))))
        (spit (fs/file dir "l1" "l2" "f3") "")
        (spit (fs/file dir "l1" "f4") "")
        (is (wait-for events #(= [:add] (of-path % (p dir "l1" "f4")))))
        (is (= [] (of-path (settle events) (p dir "l1" "l2" "f3"))))))))

(deftest await-write-finish-test
  (let [dir (temp-dir)
        f (p dir "big.txt")]
    (with-watch [events w dir {:await-write-finish {:stability-threshold 300 :poll-interval 50}}]
      (with-open [out (java.io.FileWriter. (fs/file f))]
        (dotimes [i 5]
          (.write out (str "chunk " i "\n"))
          (.flush out)
          (Thread/sleep 100)))
      (is (empty? (of-path @events f)) "no event while the file is written")
      (is (wait-for events #(= [:add] (of-path % f))))
      (is (= [:add] (types (settle events))) "one :add after the file settles"))))

(deftest atomic-test
  (let [dir (temp-dir)
        a (p dir "a.txt")
        tmp (p dir "a.txt.tmp")]
    (spit a "old")
    (with-watch [events w dir {:ignore-initial true :atomic true}]
      (Thread/sleep 1100)
      (spit tmp "new")
      (fs/move tmp a {:replace-existing true :atomic-move true})
      (is (wait-for events #(seq (of-path % a))))
      (is (= [:change] (of-path (settle events) a))
          "a file replaced through a rename is one :change")
      (spit (fs/file dir "notes.txt~") "editor backup")
      (spit (fs/file dir ".notes.txt.swp") "vim swap")
      (is (empty? (types (remove #(= a (:path %)) (settle events))))
          "editor temp files stay quiet"))))

(deftest single-file-test
  (let [dir (temp-dir)
        f (p dir "one.txt")]
    (spit f "1")
    (with-watch [events w f {}]
      (is (= [{:type :add :path f} {:type :ready}] @events))
      (Thread/sleep 1100)
      (spit f "22")
      (is (wait-for events #(= [:add :change] (of-path % f))))
      (spit (fs/file dir "other.txt") "not watched")
      (fs/delete f)
      (is (wait-for events #(= [:add :change :unlink] (of-path % f))))
      (is (= [:add :change :unlink] (types (settle events)))))))

(deftest relative-path-test
  (let [dir (temp-dir)
        rel (str (fs/relativize (fs/cwd) dir))]
    (with-watch [events w rel {}]
      (spit (fs/file dir "r.txt") "r")
      (is (wait-for events #(= [:add] (of-path % (p rel "r.txt"))))
          "paths are reported the way the watched path was given"))))

(deftest multiple-paths-test
  (let [d1 (temp-dir)
        d2 (temp-dir)]
    (with-watch [events w [d1 d2] {}]
      (spit (fs/file d1 "one") "")
      (spit (fs/file d2 "two") "")
      (is (wait-for events #(and (= [:add] (of-path % (p d1 "one")))
                                 (= [:add] (of-path % (p d2 "two")))))))))

(deftest close-test
  (let [dir (temp-dir)
        events (atom [])
        w (fw/watch dir #(swap! events conj %) {:use-polling polling?})]
    (is (wait-for events ready?))
    (let [^Thread t @(:thread (::fw/impl (meta w)))]
      (is (not (.isDaemon t)) "a persistent watcher holds the process")
      (fw/close w)
      (fw/close w)
      (is (not (.isAlive t)) "and lets go on close"))
    (spit (fs/file dir "after") "")
    (Thread/sleep 500)
    (is (= [{:type :ready}] @events) "no events after close")))

(deftest callback-error-test
  (let [dir (temp-dir)
        events (atom [])
        w (fw/watch dir (fn [ev]
                          (swap! events conj ev)
                          (when (= :add (:type ev))
                            (throw (ex-info "boom" {}))))
                    {:use-polling polling?})]
    (try
      (is (wait-for events ready?))
      (spit (fs/file dir "a") "")
      (is (wait-for events #(some (fn [ev] (= :error (:type ev))) %)))
      (is (= "boom" (ex-message (:error (first (filter #(= :error (:type %)) @events))))))
      (spit (fs/file dir "b") "")
      (is (wait-for events #(= [:add] (of-path % (p dir "b")))) "the watcher goes on")
      (finally (fw/close w)))))

(deftest missing-path-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such file"
                        (fw/watch (p (temp-dir) "nope") identity))))
