# babashka.filewatcher

Watch files and directories from [babashka](https://babashka.org), with the
same event types on macOS, Linux, and Windows. Modeled after
[chokidar](https://github.com/paulmillr/chokidar), built on
[babashka.ffi](https://github.com/babashka/ffi): FSEvents on macOS, inotify on
Linux, `ReadDirectoryChangesW` on Windows, and polling everywhere.

## Status

Experimental. Needs a babashka with `babashka.ffi`.

## Usage

```clojure
(require '[babashka.filewatcher :as fw])

(def watcher
  (fw/watch "src" (fn [event] (prn event))))
;; {:type :add, :path "src/app.clj"}
;; {:type :add-dir, :path "src/app"}
;; {:type :ready}

(spit "src/app.clj" "(ns app)")
;; {:type :change, :path "src/app.clj"}

(fw/unwatch watcher)
```

`watch` takes one path or a collection of paths, each a file or a directory,
and returns a watcher for `unwatch`. The function receives one event map at
a time, in order, on the watcher's thread.

### Events

Every backend reports the same event types for the same changes, because the
events come from a comparison of the file system with a tree the watcher
keeps. The operating system only says where to look.

| `:type` | when |
|---|---|
| `:add` | a file appeared |
| `:change` | a file's size or modification time changed |
| `:unlink` | a file disappeared |
| `:add-dir` | a directory appeared |
| `:unlink-dir` | a directory disappeared, after the `:unlink` of what it held |
| `:ready` | the first scan is complete |
| `:error` | something failed; the exception is under `:error` |

`:path` uses the watched path as you gave it. It includes the path below it.
For example, watch `"src"` to get `"src/app.clj"`.

The watcher collects changes to one path for `:delay-ms` milliseconds
(default 50). It then reports one result for the path. A file moved onto
another file reports one `:change` for the target. A directory removed with
its contents reports `:unlink` for each file, deepest first, then
`:unlink-dir`.

### Options

```clojure
(fw/watch "src" handler
          {:ignored [#"node_modules" "**.log"]
           :ignore-initial true
           :depth 2
           :await-write-finish true})
```

| option | meaning | default |
|---|---|---|
| `:ignored` | a predicate over the path, a regex, a glob, or a collection of these; the watcher does not enter an ignored directory | none |
| `:ignore-initial` | no `:add` and `:add-dir` for what exists at the start | `false` |
| `:depth` | the number of subdirectory levels to enter; `0` watches the entries of the path itself | unlimited |
| `:follow-symlinks` | stat through symbolic links | `false` |
| `:await-write-finish` | `true`, or `{:stability-threshold ms :poll-interval ms}`: hold `:add` and `:change` until the size and modification time stay the same for the threshold (default 2000 ms, checked every 100) | `false` |
| `:atomic` | hide editor temporary files, such as names that end in `~` or `.swp`, and report a file replaced through a rename as one `:change` | `true`, `false` with polling |
| `:use-polling` | compare the tree every `:interval` ms instead of listening to the operating system; for network and container file systems | `false` |
| `:interval` | the polling interval in ms | `100` |
| `:delay-ms` | how long to collect changes to a path before reporting them | `50` |

`watched` returns what the watcher knows. It maps each watched directory to
the names in that directory.

### From the fswatcher pod

`pod-babashka-fswatcher` reported the operating system's own events. These
events differ by platform. This library reports the same event types
everywhere. The names follow chokidar. Recursive watching is the default
here; use `:depth 0` for the old default. `:delay-ms` keeps its meaning.

## Design

[doc/design.md](doc/design.md) explains why the events come from a tree and
what each backend does.

## Tests

The suite is the contract: one set of file operations, one expected stream
of events, run on every backend.

```
bb test:bb         # the backend of this operating system
bb test:polling    # the polling backend, the reference
bb test:jvm        # the same suite on the JVM, with babashka.ffi from git
```

## License

MIT, see [LICENSE](LICENSE).
