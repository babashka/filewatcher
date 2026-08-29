# ADR 0001: Events come from a tree, not from the operating system

## Status

Accepted 2026-08-29. Implemented; the test suite is the contract.

## Context

Each operating system reports file changes in its own way:

| | macOS FSEvents | Linux inotify | Windows `ReadDirectoryChangesW` |
|---|---|---|---|
| unit | a path plus a bitmask, coalesced over a latency window | one record per operation | one record per action |
| recursion | native | a watch per directory | native |
| counts | operations collapse; a flag can say created and modified | a save can be `MODIFY` twice plus `CLOSE_WRITE` | modify often fires twice; the buffer can overflow and drop events |
| rename | one flag, no pairing | a cookie pairs from and to | old and new records |

A library that passes these events through, as fsnotify and therefore
`pod-babashka-fswatcher` do, cannot promise the same events on every
platform. Its documentation says so, and the pod's issue list shows the
cost: duplicate events (#11, #18), a crash on an error message (#12),
several directories under one watcher (#9), and the release binaries
themselves (#15, #17, #22).

MB's requirement for the library: the same events, the same number of
events, the same keys, on every operating system. And, as for every
library on `babashka.ffi`: no arena, pointer, layout, or callback in the
public API.

## Decision

Model the library on chokidar. chokidar does not trust the events. It
keeps a tree of the watched directories with a stat per entry. An event
from the operating system is a hint: look here. The library stats or lists
the path and compares it with the tree. The difference is the event:
`add`, `change`, `unlink`, `addDir`, or `unlinkDir`. The same difference
produces the same event on every backend.

```
FSEvents / inotify / ReadDirectoryChangesW / a timer
        │  "path p may have changed" (+ "and everything below it")
        ▼
core: a tree of {dir {name stat}}, a coalescing window, the atomic and
      await-write-finish holds, one dispatcher thread
        │
        ▼
{:type :add|:change|:unlink|:add-dir|:unlink-dir  :path "…"}
```

The backends are thin. They never call the user's function and never
decide what an event means. The core is plain Clojure over `babashka.fs`.

### What the core guarantees

- One event stream per watcher, delivered in order on one thread.
- Hints for the same path within `:delay-ms` are one comparison, so a
  save is one `:change` on every operating system.
- Hints in a batch are processed in path order, so a batch is
  deterministic.
- A directory that disappears reports `:unlink` for each file in it,
  deepest first, then `:unlink-dir`. Held unlinks below it are released
  first, so this order holds even when the backend reports the file and
  the directory in separate batches.
- A backend that lost events (FSEvents `MustScanSubDirs`, inotify
  `IN_Q_OVERFLOW`, or a zero-length `ReadDirectoryChangesW` result) asks for
  a full comparison of the subtree. The comparison reports the current
  difference. It does not report every operation that occurred before it.
- At least once. A comparison can find a file already changed twice and
  report one `:change`. No backend can promise the exact count of the
  operations, and the library does not pretend to.

### What each backend does

FSEvents (macOS): one stream for all roots, file-level events
(`kFSEventStreamCreateFlagFileEvents`), delivered on a dispatch queue. The
callback runs on a thread the runtime did not create, so it lives in a
shared arena, and only hands the paths to the core. FSEvents reports
resolved paths (`/private/tmp` for `/tmp`); the backend maps them back to
the roots as given. The callback takes six arguments, which is why
babashka's native image registers pure-integer upcalls up to six
(babashka #2077).

inotify (Linux): a watch per directory. The core calls `watch-dir!` for
every directory it enters and `unwatch-dir!` for every one it leaves, so
the backend follows the tree. A new directory is watched before its first
scan, so nothing created in between is missed. A reader thread polls the
descriptor with a timeout, so `unwatch` returns promptly.

ReadDirectoryChangesW (Windows): one overlapped read per root, recursive,
on a thread per root. Names are UTF-16 and relative to the root. A file
root watches its parent directory; the core ignores hints for the
siblings.

Polling: a timer that hints every root, with "and everything below", at
`:interval`. It uses only the core, so it provides the reference behavior.
The other backends must produce the same event types for the same changes.
The suite runs on both a native backend and polling.

Backends are maps of four functions, `:start!`, `:stop!`, `:watch-dir!`,
`:unwatch-dir!`, chosen once by operating system through
`requiring-resolve`, so a backend namespace loads only on its platform.

### The API

`watch`, `unwatch`, `watched`, and event maps with `:type` and `:path`.
The event names are chokidar's in kebab-case. The options are chokidar's
with the names in Clojure form: `:ignored`, `:ignore-initial`, `:depth`,
`:follow-symlinks`, `:await-write-finish`, `:atomic`, `:use-polling`,
`:interval`. `:delay-ms` comes from the pod. Not taken from chokidar:
`persistent` (a babashka script decides for itself whether to block),
`cwd` (the path is reported as given, which covers it), `alwaysStat` and
`binaryInterval` (no demand yet).

Arenas, callbacks, layouts, and the teardown order are inside the
backends. Stop order matters: stop the stream, invalidate it, then close
the arena that owns the callback, never the other way around. That
knowledge belongs to the library, not to its users.

## Alternatives considered

- Pass the operating system's events through, as the pod does. Rejected:
  it cannot meet the requirement, and every consumer rebuilds the same
  deduplication badly.
- kqueue on macOS, as fsnotify and chokidar v4 do. Rejected: a descriptor
  per file, no recursion, directory-level events that need a diff anyway.
  FSEvents is one stream per tree and coalesces in the kernel.
- The JDK's `WatchService`. Rejected: on macOS it polls with seconds of
  latency; on the JVM it would also mean a different code path per host.
- A CFRunLoop on a thread of our own for FSEvents, as directory-watcher
  does through JNA. Not needed: the foreign-thread upcall from a dispatch
  queue works in the native image, and the run-loop thread and its
  stop protocol are gone.
- Polling only. Kept as a backend and as the reference, not as the
  product: an OS backend gives latency in milliseconds instead of an
  interval, at no cost in semantics.
- A protocol for the backends. A map of four functions does the same
  with no type per backend and no shared namespace to load.

## Consequences

- Events are uniform by construction; the suite pins it on every
  platform, twice (native and polling).
- One difference from chokidar, on purpose: with `atomic`, chokidar delays
  a file's `unlink` by 100 ms but emits `unlinkDir` at once, so a removed
  directory reports `unlinkDir` before `unlink` on macOS and Linux and the
  reverse with polling. Here a file in a removed directory is not held,
  and held unlinks below a directory are released before its
  `:unlink-dir`, so the order is the same everywhere.
- A rewrite within the same second, with the same size, is invisible to
  the stat comparison, as it is to every stat-based watcher.
- A full-subtree comparison after lost events is O(tree), as is each
  polling interval.
- Open: `unwatch` stops everything here and removes paths in chokidar,
  which calls stopping `close`. To settle before a first release.

## Grounding

- chokidar: `lib/index.js` (the `_emit` throttling and the atomic hold),
  `lib/nodefs-handler.js` (stat and readdir on every event), `lib/fsevents-handler.js`.
- fsnotify: the README's list of platform differences.
- pod-babashka-fswatcher issues #9, #10, #11, #12, #15, #17, #18, #22.
- Apple: File System Events Programming Guide, `FSEventStreamCreateFlags`.
- Linux: `inotify(7)`, in particular the limits and `IN_Q_OVERFLOW`.
- Windows: `ReadDirectoryChangesW`, `FILE_NOTIFY_INFORMATION`.
