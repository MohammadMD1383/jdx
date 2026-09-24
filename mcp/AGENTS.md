# AGENTS.md — `mcp/`

MCP stdio server: every `JdxService` query as a typed `jdx_*` tool. Thin adapter —
schemas + wire requests are generated from the single tool table, never hand-written
twice. `explicitApi()` is on.

## Key files

- `McpTools` — the `ALL_MCP_TOOLS` table (one tool per wire command).
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
