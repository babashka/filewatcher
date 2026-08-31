# ADR 0002: Nested ignore files

## Status

Proposed 2026-08-31. Not implemented.

## Context

The requirement, from a user question on 2026-08-31: ignore files like
`.gitignore`. A directory anywhere in the tree holds a file that names what
to ignore from that directory down. A deeper file adds to the files above it
and can negate them.

Three problems sit behind that one sentence.

Matching is a pure function over a pattern and a name. Inheritance is the
rule stack from the root down to a directory. Both can be written today:
`:ignored` takes a function, and a function can read and memoize the ignore
files it needs.

The third is lifecycle. An ignore file is watched content. It appears, it is
edited, it is deleted, and the live tree has to follow. That cannot be
written today, for two reasons.

An edit to an ignore file does not rescan its directory. A hint on
`src/.gitignore` reaches `refresh!` (`impl/core.clj:262`). The path is not in
the tree and is not a root, so the `:else` branch runs: the parent `src` is a
known node, the entry exists, the stat changed, and the result is one
`:change`. `src` itself is never scanned, so no entry is decided again.

A tightened rule never prunes. `scan-dir!` (`impl/core.clj:207`) reaches
`ignored?` only through `add-node!` (`impl/core.clj:136`), which runs for
entries absent from the tree. A directory already in the tree takes the
`(:dir? st)` branch and is never tested again. The rule changes, the subtree
stays watched, and the events keep coming.

A loosened rule heals in full once a scan happens, because `add-node!` scans
the directory it adds and that scan adds what is below it. So the library can
already let entries in and cannot let them out.

## Decision

Two parts. The first is a fix that stands on its own. The second is the
extension point that makes nested files cheap.

### Re-decide and prune

Give each tree node a rule version alongside `:root`, `:level` and
`:entries`. `scan-dir!` compares the version it holds with the version on the
node and tests known entries again only when the two differ. The steady state
costs one comparison per scan.

An entry whose answer flipped to ignored is removed through `remove-node!`,
which already tears down the subtree and calls `unwatch-dir!`.

### The ignore option

The library owns when: discovery, order, inheritance, invalidation, pruning.
The caller owns what: parse a text, match a name.

```clojure
(fw/watch "." handler
          {:ignore {:files [".gitignore"]
                    :parse (fn [dir text parent-rules] rules)
                    :match (fn [rules name dir?] boolean)}})
```

`:files` names the ignore files, so a hint on one of those names recomputes
the rules of its directory and hints the directory again with `subtree?`
true. `refresh!` already takes `subtree?` and `process-due!` already coalesces
hints, so invalidation is a branch and not new machinery.

`:parse` runs once per directory when its ignore file appears or changes, and
receives the parent's rules, so inheritance and negation are the caller's to
define. `:match` runs at the decision point with the rules already resolved
on the node, which is one lookup instead of a walk up the parents per entry.

`:match` receives `dir?`. A path string cannot supply it and a gitignore
pattern that ends in `/` needs it.

Where it lands: the node gains the rules and the version, set in `add-node!`
and `initial-scan!`. `scan-dir!` notices an ignore file in the listing before
it diffs. `ignored?` (`impl/core.clj:79`) gains the node and the stat, and its
only call site is `add-node!`, which holds both.

`:ignored` keeps its meaning and runs first, as the check that needs no
rules. The two compose with `or`.

### Three smaller decisions

A prune emits `:unlink` and `:unlink-dir`. A consumer that put a file on a
compile list has to take it off, and the reason the file left the watched set
does not change that.

Ignore files above the watched root are not read. git reads them up to the
repository root. Watching `src` under a repository whose top-level
`.gitignore` names `/src/generated` would mean reading a tree the watcher was
not pointed at, which is the objection that made `:follow-symlinks` false in
ADR 0001. The caller seeds the root's rules instead.

The ignore file is reported like any other file. git tracks `.gitignore`.
A caller who wants it hidden has `:ignored`.

### The dialect

`:parse` and `:match` for gitignore go in `babashka.filewatcher.gitignore`,
not in the core. Negation, `**`, anchoring, directory-only patterns, escapes
and case folding are a large correctness surface with nothing to do with
watching files. In its own namespace it can be fixed without touching the
core, and `.dockerignore` or `.npmignore` reach the same option.

## Alternatives considered

- A custom walker, in the shape of a `walk-file-tree` visitor with
  `pre-visit-directory` and a skip result. Rejected: after the initial scan
  there is no walk. A backend hints one path and `scan-dir!` compares that one
  directory, re-entered at any depth in any order. A visitor implies a
  traversal state the watcher does not have.
- The stat as a second argument to `:ignored`, and nothing else. It gives the
  match its `dir?` and leaves both lifecycle failures in place.
- gitignore semantics in the core, behind `:ignore-files [".gitignore"]`
  alone. Rejected: the core would own a pattern dialect.
- A public `rescan!` and nothing else, with the caller watching the ignore
  files. Rejected: a rescan reaches `scan-dir!`, which does not test known
  entries, so a tightened rule still does not prune.
- A distinct event type for an entry that left the watched set through a rule.
  Rejected: new vocabulary for a case `:unlink` already covers.

## Consequences

- Re-decide and prune is a bug fix on its own. Any `:ignored` function that
  tightens is ignored today. It can be tested with a predicate over an atom,
  before any ignore file exists.
- A prune emits `:unlink` for a file that is still on disk. Documented, not
  avoidable: the events describe the watched set.
- `:parse` runs once per directory per change to its ignore file, and
  `:match` runs per entry with the rules resolved. The cost matches ripgrep
  and git.
- Work order: re-decide and prune, then `:ignore` `:files` and its
  invalidation, then `:parse` and `:match` against a real tree.

## Grounding

- `gitignore(5)`: the last matching pattern decides, a pattern that ends in
  `/` matches directories only, and an excluded directory is not descended
  into, so a negation below it cannot bring a file back.
- ripgrep, the `ignore` crate: a matcher per directory, stacked from the root
  down.
- chokidar: `ignored` is one predicate over a path, with no nested files.
- `impl/core.clj`: `ignore-fn` line 61, `ignored?` line 79, `add-node!` line
  136, `scan-dir!` line 207, `refresh!` line 262.
