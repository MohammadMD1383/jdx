# Session log — shard 8: sessions 071–080

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

## Session 71 — 2026-09-23 — T-044 HTTP/JSON server done (`jdx serve` live)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `850e42b` (claim) + closing commit (this session)

### Goal
T-044, the third M6 server slice after the daemon + MCP: the HTTP/JSON API
on the JDK builtin `com.sun.net.httpserver` (PROPOSAL.md §14.4, zero
dependencies) — `GET /v1/<command>?…`, `POST /v1/batch`, `GET /v1/health`
over one stored workspace, localhost by default, serving the exact `--json`
envelope so T-046 parity stays structural.

### What I did
- Claimed T-044 first (`docs/TASKS.md` TODO→WIP, committed `850e42b` before coding).
- **`server/.../HttpServer.kt`** (new): `JdxHttpServer(workspace, bind,
  port, appVersion)` over `com.sun.net.httpserver` (virtual-thread pool).
  Per-request roots through `JdxService.daemonRoots` (the T-082/MCP seam);
  answers are `ServiceOutcome.toJson(wire)` verbatim with the exit code
  mapped to HTTP status in one place (`statusForExit`: 0→200, 1→404,
  2→300, 3→400, 4→404, 5→502, 6→500). `GET /v1/<wire>` forwards every
  query key except `query`/`workspace` as D-058 wire params;
  `?workspace=` overrides the server default per call (batch takes it on
  the POST URL, since `RpcRequest` lines carry no workspace).
  `POST /v1/batch` takes NDJSON `RpcRequest` lines (the T-040 framing,
  shared with `jdx batch` stdin) and answers one envelope line per request
  without aborting on per-query failure (the T-045 rule). `version`/
  `health` answer internally in the daemon's shapes (`indexedArtifacts` 0,
  D-043); `doctor` refused exit 6 naming `jdx doctor`. Every handler total
  (hostile paths/methods/query strings/bodies → exit-coded envelopes).
  `DEFAULT_HTTP_BIND` (`127.0.0.1`) + `DEFAULT_HTTP_PORT` (`7070`) pinned
  constants; no idle shutdown — blocks until interrupted.
- **`cli/.../ServeCommand.kt`** (new): `jdx serve [-w name]
  [--port 7070] [--bind 127.0.0.1]`, registered in `JdxCli`. Thin via an
  injectable `(bind, port, workspace) -> Unit` runner (the `DaemonCommand`
  test-seam precedent): exits 3 on bad `--port`/`--bind`, 6 on bind
  failure.
- **Tests:** server tier-2 `HttpServerTest` (12: health/version pins, GET
  members byte parity vs `JdxService.dispatch`, not-found 404 with the
  identical envelope, batch lines + malformed-line survival + empty-batch
  usage, missing-workspace exit 4 with `?workspace=` override, doctor
  refusal, unknown-path/method envelopes, defaults + `statusForExit` +
  query-string pins) + cli tier-1 `ServeCommandTest` (4: defaults, flag
  plumbing, port exit 3, bind-failure exit 6). Two fix cycles, both kept:
  `queryOf` nullability at the call site, and Clikt's `int` lives in
  `parameters.types` (not `options`).
- **Live proof** (fat jar, isolated `HOME`, cleaned up after): `serve -w
  serve-proof --port 7171` → `health` 200 daemon-shaped; `members`
  HTTP body + newline **byte-identical** to `jdx members --json
  --no-daemon` (`cmp` PARITY-OK — the newline is transport framing, the
  MCP modulo); NDJSON batch of `show`+`search` answers 2 lines;
  `/v1/doctor` → 500, `/v1/frobnicate` → 404.
- Docs: T-044 DONE, D-061, Appendix B `serve [-w …]`, CURRENT STATE +
  open-items updated, new `sessions-071-080.md` shard (061–070 full).

### Decisions made
- **D-061** — HTTP/JSON server semantics (third-daemon framing, verbatim
  bodies + `statusForExit`, GET-query/POST-batch split, localhost +
  daemon-separate socket, injectable-runner thin command).

### Tasks moved
- T-044: TODO → WIP (`850e42b`) → DONE. Next is **T-045** (`jdx batch`).

### Lessons distilled
- None (no new toolchain/spec gotcha; the two compile errors were
  ordinary API misremembers fixed in one pass each).

### What works now (and how to verify it yourself)
```bash
./gradlew :server:check :cli:check :mcp:check -x verifyTier1Budget  # green
./gradlew :app:installDist
export HOME=/tmp/jdx-try-home
./app/build/jdx ws create fx --jars <jar> --no-jdk && ./app/build/jdx serve -w fx --port 7170 &
curl http://127.0.0.1:7170/v1/health  # daemon-shaped envelope
curl 'http://127.0.0.1:7170/v1/members?query=<type>&limit=5' > warm.json
./app/build/jdx -w fx members <type> --limit 5 --json --no-daemon > cold.json
cmp <(cat warm.json; echo) cold.json  # byte-identical modulo the println newline
printf '%s\n' '{"jdx":1,"command":"show","query":"<type>","params":{}}' | curl -s -X POST --data-binary @- http://127.0.0.1:7170/v1/batch
kill %1  # serve blocks until interrupted; no idle shutdown
```

**Caveats, unchanged and pre-existing:**
- `verifyTier1Budget` is red on this machine (pre-existing machine
  variance, 0 test failures — run past the gate via `-x verifyTier1Budget`
  per the session-66 precedent).
- Tier 3 not run: T-044 touches transport/roots plumbing over
  already-covered readers (the §13 trigger fires on artifact/index/render
  changes; every touched path is exercised tier-2 over real jars).
- `JavapCorpusSoakTest` still reds only on JDK-internal synthetic `access$`
  members (pre-existing, proven on the stashed-clean tree).

### What is broken / half-done
- Nothing from this task.

### Open questions / blockers
- None.

### Next action
- **M6 T-045** (`jdx batch`: NDJSON requests on stdin, one envelope per
  line, exit code is the max query exit code).
