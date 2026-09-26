# AGENTS.md — `server/`

Daemon (unix socket) + HTTP/JSON API. Thin adapters over `JdxService` — responses
are `toJson` bytes verbatim, no second shape. `explicitApi()` is on.

## Key files

- `DaemonPaths` — version-stamped socket + pid/log/lock siblings under the OS runtime dir:
  `$XDG_RUNTIME_DIR/jdx/<workspace-hash>-v1.sock` on Linux, `$TMPDIR/jdx-$UID/…` on
  macOS, `%LOCALAPPDATA%/jdx/run/…` on Windows (full table in `docs/PROPOSAL.md`
  §17.1; D-044). A missing `XDG_RUNTIME_DIR` falls back per table, never exit 3.
  Paths past the `sun_path` byte cap (104 macOS, 108 elsewhere) exit 3 with the
  `JDX_RUNTIME_DIR` hint — never a generic bind failure or a silent cold fallback.
- `DaemonServer` — strict 1:1 NDJSON line mapping, per-request idle reset,
  `health` handshake with protocol-version refusal. Holds `<hash>-v1.lock` via
  `FileChannel.tryLock` while serving (concurrent `start` fails fast naming the
  holder); releases it on `stop` so a killed daemon restarts clean.
  `DaemonIdle` — 5-minute idle shutdown (`--idle`, `0` disables);
  `parseIdleDuration` never throws. `stop` validates the pid file owns a
  `daemon run` child before signalling (never kills a reused pid) and escalates
  `destroy()` to `destroyForcibly()`.
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
