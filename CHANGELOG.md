# Changelog

## Unreleased

## v1.8.0 (2026-11-26)

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
- Website cards keep long text inside the grid, and cards in a row share a
  top edge (issues #133, #132). Command labels break after each `|`. Cards,
  prose, nav, and code use `overflow-wrap` and `min-width: 0`. A card link
  stretches with its grid row. Column tracks use `minmax(min(100%, …), 1fr)`.
- Classpath MCP tools accept `jars`, `coord`, and `discover` with no stored
  workspace (issue #20). A named workspace that exists still wins. A missing
  name and no other roots is still exit 4 and names `jdx ws`. `jars` and
  `coord` are a comma-separated string or a JSON array. `jdx mcp --discover`
  turns project auto-discovery on for the session; a per-call `discover`
  overrides it. Discovery walks up from the server's working directory and
  never runs Gradle or Maven. Coordinates come from local caches only. An
  uncached coordinate is exit 5 and names a cold CLI `--fetch`. MCP does not
  download. `diff`, `version`, `doctor`, and `health` do not take these
  arguments. The unix daemon and HTTP API are unchanged.
- The site build retries a transient fixture download four times (2s, then 5s,
  then 10s). A checksum mismatch is not retried. The Pages workflow caches
  `sandbox/cache/jdx/m2`, keyed on the coordinates the examples download.
- jdx packs an MCP Bundle for the official registry. `mcpb/manifest.json`
  launches `bin/jdx mcp` over stdio (`bin/jdx.bat mcp` on Windows) and requires
  Java 21+ on `PATH` or in `JAVA_HOME`. `server.json` is the registry template
  (`io.github.MohammadMD1383/jdx`); the `fileSha256` in git is a placeholder.
  The Linux release leg uploads `jdx-mcp-<version>.mcpb`, its `.sha256`, and a
  stamped `jdx-mcp-<version>.server.json`. It does not call `mcp-publisher`.
  Runtime behaviour of `jdx` is unchanged.
- MCP tool descriptions say what each of the 27 tools returns and which sibling
  to use instead. Every input property has a description, plus `enum`,
  `default`, and `examples` where the dispatch has them. Every tool is
  annotated read-only, not destructive, idempotent, and `openWorldHint: false`.
  Root `glama.json` names the maintainer. Tool names, parameters, and dispatch
  are unchanged. `jdx doctor` is still refused over MCP (exit 6).
- Site guides added since v1.7.0: Minecraft mod jars (#134), read or decompile
  a jar from the terminal (#136), a Java MCP server, a javap alternative, and
  smaller agent context (#137), which jar contains a class (#139), Minecraft
  mixin targets (#140), diffing Minecraft and mod versions (#142), Javadoc
  from a jar (#143), the Kotlin API of a compiled jar (#144), in-content links
  to pages Search Console had not discovered (#145), sharper titles and FAQs
  on the find-class and Minecraft-mod pages (#146), a JD-GUI alternative
  (#147), and Maven or Gradle dependency source (#148). The README links the
  Glama MCP server score badge (#141).
