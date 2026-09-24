# AGENTS.md — `server/`

Daemon (unix socket) + HTTP/JSON API. Thin adapters over `JdxService` — responses
are `toJson` bytes verbatim, no second shape. `explicitApi()` is on.

## Key files

- `DaemonPaths` — version-stamped socket + pid/log siblings at
  `$XDG_RUNTIME_DIR/jdx/<workspace-hash>-v1.sock`; no `XDG_RUNTIME_DIR` → exit 3.
- `DaemonServer` — strict 1:1 NDJSON line mapping, per-request idle reset,
  `health` handshake with protocol-version refusal. `DaemonIdle` — 5-minute idle
  shutdown (`--idle`, `0` disables); `parseIdleDuration` never throws.
- `DaemonDispatch` — resolves roots, serialises outcomes; all 17 read commands to
  `JdxService` (`indexedArtifacts` stays 0 — live roots, no persistent acceleration).
- `HttpServer` — JDK builtin only: `GET /v1/<command>`, `POST /v1/batch` (NDJSON),
  `GET /v1/health`; `?workspace=` per-call override; localhost by default; blocks
  until interrupted (no idle shutdown, unlike the daemon).

## Rules

- Talk to unix sockets with `ByteBuffer` reads/writes only (`Socket.socket()`
  streams support INET alone — `UnsupportedOperationException` on UNIX channels;
  client read timeout via `Selector`, blocking reads on server threads). When a
  never-throws wrapper fails everywhere at once, suspect its shared primitive first.
- Malformed/unknown requests stay transport-owned envelopes; `doctor` refused
  (exit 6); `ws`/`cache` absent from the wire on purpose. The daemon never fetches.
