# Changelog

## Unreleased

- The v1 daemon, HTTP and MCP wire stays read-only (issue #19). `doctor` is
  refused with exit 6. The daemon says to run the same query without the
  daemon and names `jdx doctor`. HTTP and MCP say to run `jdx doctor` in a
  shell. HTTP maps that exit to status 500. `ws` and `cache` stay off the
  wire. `jdx doctor` and `jdx batch` still answer `doctor` in-process.
- `jdx <command> --release N` (alias `--multi-release`) selects which
  multi-release jar variant answers a query, including `jdx diff`. The default
  is unchanged: the running JDK feature, and `MULTI_RELEASE_VARIANT` when a
  versioned class wins, with the same warning sentence as before. A versioned
  answer names that release on its `source:` line and as `"release"` in JSON.
  A base entry omits the field. `8` serves base entries only. A value below 8
  is a usage error. An explicit value stays in-process. The warm daemon, MCP
  and HTTP keep the running-JDK selection. Resources, services and the manifest
  still list versioned copies as stored.
- `jdx body --kotlin-mode psi|lexer|auto` can read a `.kt` member without the
  compiler sidecar. `psi` (the default) is unchanged: the sidecar, then a
  labelled decompilation. `lexer` brace-matches common `fun` / `val` / `class`
  shapes offline and labels the slice `lexer`
  (`source: lexer scan of … ⚠ brace-matched, not PSI`), distinct from sources
  and from Vineflower/`javap`. `auto` uses the sidecar when that parse succeeds
  and the lexer otherwise. A scan that cannot match braces, strings, or
  comments degrades to the same reconstruction ladder. `SOURCES_VERSION_MISMATCH`
  still warns. `jdx source` and `jdx doc` still use the sidecar. The lexer
  does not download anything; `jdx kotlin install` stays opt-in.
