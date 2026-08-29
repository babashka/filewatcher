# Design

## The problem

Each operating system reports file changes in its own way:

| | macOS FSEvents | Linux inotify | Windows `ReadDirectoryChangesW` |
|---|---|---|---|
| unit | a path plus a bitmask, coalesced over a latency window | one record per operation | one record per action |
| recursion | native | a watch per directory | native |
| counts | operations collapse; a flag can say created and modified | a save can be `MODIFY` twice plus `CLOSE_WRITE` | modify often fires twice; the buffer can overflow and drop events |
| rename | one flag, no pairing | a cookie pairs from and to | old and new records |

A library that passes these events through, as fsnotify and therefore
`pod-babashka-fswatcher` do, cannot promise the same events on every
platform. Its documentation says so.

## The answer, from chokidar

chokidar does not trust the events. It keeps a tree of the watched
directories with a stat per entry. An event from the operating system is a
hint: look here. The library stats or lists the path and compares it with
the tree. The difference is the event: `add`, `change`, `unlink`, `addDir`,
or `unlinkDir`. The same difference produces the same event on every backend.

This library does the same:

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

## What the core guarantees

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
- The watcher reports each current difference at least once. A comparison can find a file already changed twice and
  report one `:change`. No backend can promise the exact count of the
  operations, and the library does not claim to do so.

## What each backend does

FSEvents (macOS): one stream for all roots, file-level events
(`kFSEventStreamCreateFlagFileEvents`), delivered on a dispatch queue. The
callback runs on a thread the runtime did not create, so it lives in a
shared arena, and only hands the paths to the core. FSEvents reports
resolved paths (`/private/tmp` for `/tmp`); the backend maps them back to
the roots as given. The callback takes six arguments, which is why
babashka's native image registers pure-integer upcalls up to six.

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

## Options, and where they come from

The options are chokidar's, with the names in Clojure form: `:ignored`,
`:ignore-initial`, `:depth`, `:follow-symlinks`, `:await-write-finish`,
`:atomic`, `:use-polling`, `:interval`. `:delay-ms` comes from the pod. Not
taken from chokidar: `persistent` (a babashka script decides for itself
whether to block), `cwd` (the path is reported as given, which covers
it), `alwaysStat` and `binaryInterval` (no demand yet).

One difference from chokidar: with `atomic`, chokidar delays a file's
`unlink` by 100 ms but emits `unlinkDir` at once, so a removed directory
reports `unlinkDir` before `unlink` on macOS and Linux and the reverse
with polling. Here a file in a removed directory is not held, and held
unlinks below a directory are released before its `:unlink-dir`, so the
order is the same everywhere.

## The public API hides the FFI

The user sees `watch`, `unwatch`, `watched`, and event maps. Arenas,
callbacks, layouts, and the teardown order are inside the backends. Stop
order matters: stop the stream, invalidate it, then close the arena that
owns the callback, never the other way around. That knowledge belongs to
the library, not to its users.

## Grounding

- chokidar: `lib/index.js` (the `_emit` throttling and the atomic hold),
  `lib/nodefs-handler.js` (stat and readdir on every event), `lib/fsevents-handler.js`.
- fsnotify: the README's list of platform differences.
- Apple: File System Events Programming Guide, `FSEventStreamCreateFlags`.
- Linux: `inotify(7)`, in particular the limits and `IN_Q_OVERFLOW`.
- Windows: `ReadDirectoryChangesW`, `FILE_NOTIFY_INFORMATION`.
