# Changelog

## Unreleased

- The website has a blog. `/blog/` lists posts newest first. Each post is its own
  page, with a title, date, description, canonical URL, Open Graph tags, and
  BlogPosting JSON-LD. Posts are in `sitemap.xml`, and `/blog/feed.xml` is an
  Atom feed. Posts are Markdown files in `docs/blog/`.
- `jdx bench` still omits the `usages` row unless `--include-usages` is set
  (issue #11). The opt-in row times the current live scan and is labelled
  `no indexed path yet (live scan)` in text and JSON. Default output is
  unchanged. Rows still print `ok`/`OVER` and success still exits 0. The
  scale fixture is `minecraft-client.jar` under `~/.gradle/caches/fabric-loom/`;
  gson is the everyday fixture.
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
