(ns babashka.filewatcher.impl.fsevents
  "The macOS backend: one FSEvents stream for all roots, delivered on a
  dispatch queue. The stream reports file paths (kFSEventStreamCreateFlagFileEvents)
  and the core does the rest. When the kernel or the daemon dropped events,
  the flags say so and the hint asks for a full comparison of the subtree."
  {:no-doc true}
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [babashka.filewatcher.impl.core :as core]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(ffi/load-library "/System/Library/Frameworks/CoreServices.framework/CoreServices")

(defcfn cf-string-create "CFStringCreateWithCString" [:pointer :string :uint32] :pointer)
(defcfn cf-array-create "CFArrayCreate" [:pointer :pointer :long :pointer] :pointer)
(defcfn cf-release "CFRelease" [:pointer] :void)
(defcfn stream-create "FSEventStreamCreate"
  [:pointer :pointer :pointer :pointer :uint64 :double :uint32] :pointer)
(defcfn stream-set-dispatch-queue "FSEventStreamSetDispatchQueue" [:pointer :pointer] :void)
(defcfn stream-start "FSEventStreamStart" [:pointer] :uint8)
(defcfn stream-stop "FSEventStreamStop" [:pointer] :void)
(defcfn stream-invalidate "FSEventStreamInvalidate" [:pointer] :void)
(defcfn stream-release "FSEventStreamRelease" [:pointer] :void)
(defcfn queue-create "dispatch_queue_create" [:string :pointer] :pointer)
(defcfn dispatch-release "dispatch_release" [:pointer] :void)

(def kCFStringEncodingUTF8 0x08000100)
(def kFSEventStreamEventIdSinceNow -1)
(def kFSEventStreamCreateFlagNoDefer 0x2)
(def kFSEventStreamCreateFlagFileEvents 0x10)

;; event flags that mean "look at the whole subtree"
(def kFSEventStreamEventFlagMustScanSubDirs 0x1)
(def kFSEventStreamEventFlagUserDropped 0x2)
(def kFSEventStreamEventFlagKernelDropped 0x4)
(def kFSEventStreamEventFlagRootChanged 0x20)
(def rescan-flags (bit-or kFSEventStreamEventFlagMustScanSubDirs
                          kFSEventStreamEventFlagUserDropped
                          kFSEventStreamEventFlagKernelDropped
                          kFSEventStreamEventFlagRootChanged))

(defn real-roots
  "FSEvents reports resolved paths (/private/tmp for /tmp). Pairs each
  root's real path with the path the user gave, longest first."
  [roots]
  (->> roots
       (map (fn [{:keys [abs]}]
              [(str (fs/real-path abs)) abs]))
       (sort-by (fn [[real _]] (- (count real))))
       vec))

(defn unresolve [reals path]
  (or (some (fn [[real abs]]
              (cond (= real path) abs
                    (str/starts-with? path (str real "/")) (str abs (subs path (count real)))))
            reals)
      path))

(defn make-backend [w _opts]
  (let [arena (ffi/shared-arena)
        state (atom nil)
        reals (real-roots (:roots w))
        on-events (fn [_stream _info n paths flags _ids]
                    ;; runs on the dispatch queue: only hand the paths over
                    (try
                      (let [n (long n)
                            paths (ffi/reinterpret paths (* 8 n))
                            flags (ffi/reinterpret flags (* 4 n))]
                        (dotimes [i n]
                          (let [p (ffi/ptr->string (ffi/read paths :pointer (* 8 i)))
                                f (ffi/read flags :uint32 (* 4 i))]
                            (core/hint! w (unresolve reals p)
                                        (pos? (bit-and f rescan-flags))))))
                      (catch Throwable t
                        (core/error! w t))))]
    {:start!
     (fn []
       (let [cb (ffi/callback arena on-events
                              [:pointer :pointer :size_t :pointer :pointer :pointer] :void)
             cfstrs (mapv (fn [{:keys [abs]}] (cf-string-create ffi/null abs kCFStringEncodingUTF8))
                          (:roots w))
             arr (with-open [a (ffi/confined-arena)]
                   (let [vals (ffi/alloc a (* 8 (count cfstrs)))]
                     (doseq [[i s] (map-indexed vector cfstrs)]
                       (ffi/write vals :pointer s (* 8 i)))
                     (cf-array-create ffi/null vals (count cfstrs)
                                      (ffi/find-symbol "kCFTypeArrayCallBacks"))))
             stream (stream-create ffi/null cb ffi/null arr kFSEventStreamEventIdSinceNow
                                   0.01
                                   (bit-or kFSEventStreamCreateFlagNoDefer
                                           kFSEventStreamCreateFlagFileEvents))
             queue (queue-create "babashka.filewatcher" ffi/null)]
         (stream-set-dispatch-queue stream queue)
         (when (zero? (stream-start stream))
           (stream-invalidate stream)
           (stream-release stream)
           (dispatch-release queue)
           (cf-release arr)
           (run! cf-release cfstrs)
           (.close arena)
           (throw (ex-info "filewatcher: FSEventStreamStart failed" {:paths (mapv :abs (:roots w))})))
         (reset! state {:stream stream :queue queue :arr arr :cfstrs cfstrs})))
     :stop!
     (fn []
       (when-let [{:keys [stream queue arr cfstrs]} @state]
         (reset! state nil)
         (stream-stop stream)
         ;; after Invalidate the callback is not called again, so the arena
         ;; that owns it can close
         (stream-invalidate stream)
         (stream-release stream)
         (dispatch-release queue)
         (cf-release arr)
         (run! cf-release cfstrs)
         (.close arena)))
     :watch-dir! (fn [_])
     :unwatch-dir! (fn [_])}))
