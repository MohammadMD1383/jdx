# AGENTS.md — `mcp/`

MCP stdio server: every `JdxService` query as a typed `jdx_*` tool. Thin adapter —
schemas + wire requests are generated from the single tool table, never hand-written
twice. `explicitApi()` is on.

## Key files

- `McpTools` — the `ALL_MCP_TOOLS` table (one tool per wire command), including
  `jdx_annotated` (issue #26 annotation-driven view + recipe aliases),
  `jdx_literals` (issue #86 string-constant + `.properties` search), `jdx_services` (issue #25
  `META-INF/services` SPI listing), `jdx_modules` (issue #25 `module-info`
  requires/exports/opens/uses/provides),   `jdx_manifest` (issue #25
  `META-INF/MANIFEST.MF` attributes, including `entries` for JarEntry
  sections), and `jdx_resources` (issue #25 non-class resource path listing +
  `cat` content extraction). Descriptions, per-parameter schema (description,
  enum, default, examples) and tool annotations (`readOnlyHint`,
  `destructiveHint: false`, `idempotentHint`, `openWorldHint: false`) are
  served from this table. No tool declares `fetch`; `requestFor` drops unknown
  keys, so `jdx_diff` coordinates resolve from local caches and every tool is
  closed-world (issue #18). The site MCP tools page is generated from
  `tools/list`.
- Root `glama.json` is the Glama directory listing (`maintainers` only). It is
  not read at runtime. Root `server.json` is the Official MCP Registry template
  (version is the Gradle default `0.1.0-SNAPSHOT`; `fileSha256` is sixty-four
  zeros until `scripts/pack-mcpb.py stamp` fills a release copy). It is not
  read at runtime either.
- `mcpb/manifest.json` is the MCP Bundle manifest (server type `binary`,
  entry point `bin/jdx`, Windows override `bin/jdx.bat`, argument `mcp`).
  Tool names and descriptions are copied from `tools/list`.
  `python3 scripts/pack-mcpb.py sync-tools` rewrites them;
  `python3 scripts/pack-mcpb.py pack` fails if they have drifted.
  The manifest schema's `compatibility.runtimes` accepts only `node` and
  `python`, so the Java 21+ / `PATH` / `JAVA_HOME` requirement lives in the
  description and long description. `.github/workflows/mcpb.yml` validates
  and packs on pull requests and uploads nothing; the Linux leg of
  `release.yml` uploads `jdx-mcp-<version>.mcpb`.
- `McpSession` — per-session daemon: per-call roots, `version`/`health` internal,
  `doctor` refused (exit 6), `isError = ok:false`.
- `McpServer` — SDK registration + stdio to EOF. `jdx mcp [-w name]`; workspace-bound
  (stored workspace + per-call override only — no auto-discovery, no explicit
  `--jars`/`--coord`). Answers byte-identical to `--json`.

## Rules (distilled serial-incident log — violate these and every client breaks)

- **Stdout is the protocol:** silence every banner, including dependencies' —
  kotlin-logging's startup line goes to stdout by default (disable it before
  creating the transport); slf4j-with-no-binding's warning goes to stderr (fine).
- **Exit on EOF:** await the transport's `onClose`, not `awaitCancellation()`
  (nothing cancels that scope on stdin EOF — one leaked JVM per session). Bound the
  close, then `exitProcess(0)`; prove it by closing stdin and waiting on the exit
  code, repeatedly (the linger is a race).
