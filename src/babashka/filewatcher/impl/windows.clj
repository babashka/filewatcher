(ns babashka.filewatcher.impl.windows
  "The Windows backend: ReadDirectoryChangesW on each root, recursive,
  overlapped, on a thread per root. A record names a path below the root;
  the core does the rest. An empty result means the buffer overflowed and
  asks for a full comparison of that root."
  {:no-doc true}
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [babashka.filewatcher.impl.core :as core]
            [babashka.fs :as fs]))

(ffi/load-library "kernel32.dll")

(defcfn create-file "CreateFileW"
  [:pointer :uint32 :uint32 :pointer :uint32 :uint32 :pointer] :pointer)
(defcfn read-directory-changes "ReadDirectoryChangesW"
  [:pointer :pointer :uint32 :int :uint32 :pointer :pointer :pointer] :int)
(defcfn create-event "CreateEventW" [:pointer :int :int :pointer] :pointer)
(defcfn wait-for-single-object "WaitForSingleObject" [:pointer :uint32] :uint32)
(defcfn get-overlapped-result "GetOverlappedResult" [:pointer :pointer :pointer :int] :int)
(defcfn cancel-io-ex "CancelIoEx" [:pointer :pointer] :int)
(defcfn close-handle "CloseHandle" [:pointer] :int)
(defcfn get-last-error "GetLastError" [] :uint32)

(def FILE_LIST_DIRECTORY 0x1)
(def FILE_SHARE_ALL 0x7) ; read, write, delete
(def OPEN_EXISTING 3)
(def FILE_FLAG_BACKUP_SEMANTICS 0x02000000)
(def FILE_FLAG_OVERLAPPED 0x40000000)
(def INVALID_HANDLE_VALUE -1)

(def notify-filter
  ;; file name, dir name, attributes, size, last write, creation
  (bit-or 0x1 0x2 0x4 0x8 0x10 0x40))

(def WAIT_OBJECT_0 0)
(def WAIT_TIMEOUT 0x102)
(def ERROR_NOTIFY_ENUM_DIR 1022)
(def ERROR_OPERATION_ABORTED 995)

(def buffer-size 65536)

(defn wide
  "Writes s as a NUL-terminated UTF-16LE string into arena."
  [arena ^String s]
  (let [bytes (.getBytes s "UTF-16LE")
        p (ffi/alloc arena (+ 2 (count bytes)))]
    (ffi/write-array p :byte bytes)
    (ffi/write p :int16 0 (count bytes))
    p))

(defn read-records!
  "Hands every FILE_NOTIFY_INFORMATION record in buf to the core."
  [w root-abs buf n]
  (loop [off 0]
    (let [next (ffi/read buf :uint32 off)
          len (ffi/read buf :uint32 (+ off 8))
          name (String. ^bytes (ffi/read-array buf :byte len (+ off 12)) "UTF-16LE")]
      (core/hint! w (core/join root-abs name) false)
      (when (and (pos? next) (< (+ off next) n))
        (recur (+ off next))))))

(defn watch-root
  "Runs the read loop for one root until running is false."
  [w arena running {:keys [abs dir?]}]
  (let [dir (if dir? abs (str (fs/parent abs)))
        h (create-file (wide arena dir) FILE_LIST_DIRECTORY FILE_SHARE_ALL ffi/null
                       OPEN_EXISTING (bit-or FILE_FLAG_BACKUP_SEMANTICS FILE_FLAG_OVERLAPPED)
                       ffi/null)]
    (when (= INVALID_HANDLE_VALUE (ffi/address h))
      (throw (ex-info (str "filewatcher: CreateFileW failed for " dir ": error " (get-last-error))
                      {:path dir})))
    (let [event (create-event ffi/null 1 0 ffi/null)
          ;; OVERLAPPED: Internal, InternalHigh, Offset+OffsetHigh, hEvent
          overlapped (ffi/alloc arena 32)
          buf (ffi/alloc arena buffer-size)
          bytes (ffi/alloc arena 4)]
      (ffi/write overlapped :pointer event 24)
      (try
        (loop []
          (when @running
            (ffi/write overlapped :long 0 0)
            (ffi/write overlapped :long 0 8)
            (ffi/write overlapped :long 0 16)
            (when (zero? (read-directory-changes h buf buffer-size (if dir? 1 0) notify-filter
                                                 bytes overlapped ffi/null))
              (throw (ex-info (str "filewatcher: ReadDirectoryChangesW failed for " dir
                                   ": error " (get-last-error))
                              {:path dir})))
            ;; wait in slices so stop! is noticed
            (let [rc (loop []
                       (let [rc (wait-for-single-object event 200)]
                         (if (and (= rc WAIT_TIMEOUT) @running)
                           (recur)
                           rc)))]
              (when (= rc WAIT_TIMEOUT)
                (cancel-io-ex h overlapped)
                (wait-for-single-object event 5000))
              (if (zero? (get-overlapped-result h overlapped bytes 0))
                (let [e (get-last-error)]
                  (cond
                    (= e ERROR_OPERATION_ABORTED) nil
                    (= e ERROR_NOTIFY_ENUM_DIR) (do (core/hint! w abs true) (recur))
                    :else (throw (ex-info (str "filewatcher: GetOverlappedResult failed for " dir
                                               ": error " e)
                                          {:path dir}))))
                (let [n (ffi/read bytes :uint32)]
                  (if (zero? n)
                    ;; the buffer overflowed: compare the whole root
                    (core/hint! w abs true)
                    (read-records! w dir buf n))
                  (recur))))))
        (finally
          (close-handle h)
          (close-handle event))))))

(defn make-backend [w _opts]
  (let [arena (ffi/shared-arena)
        running (atom false)
        threads (atom [])]
    {:start!
     (fn []
       (reset! running true)
       (doseq [r (:roots w)]
         (let [t (Thread. (fn []
                            (try
                              (watch-root w arena running r)
                              (catch Throwable t
                                (when @running (core/error! w t)))))
                          (str "filewatcher-rdcw " (:given r)))]
           (.setDaemon t true)
           (.start t)
           (swap! threads conj t))))
     :stop!
     (fn []
       (reset! running false)
       (doseq [^Thread t @threads]
         (.join t 10000))
       (reset! threads [])
       (.close arena))
     :watch-dir! (fn [_])
     :unwatch-dir! (fn [_])}))
