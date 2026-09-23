# Session log — shard 7: sessions 061–070

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

## Session 68 — 2026-09-23 — T-082 daemon query dispatch done (warm answers live)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `25a7447` (claim) + closing commit (this session)

### Goal
T-082, the filed T-041 remainder: dispatch all 17 read `RpcCommand`s through
the live daemon socket to the same `JdxService` methods the one-shot CLI
calls, with `toJson(wire)` bytes verbatim — so T-042's transparent client has
warm answers to forward to.

### What I did
- Claimed T-082 first (`docs/TASKS.md` TODO→WIP, committed `25a7447` before coding).
- **`index/.../service/RpcDispatch.kt`** (new): `JdxService.dispatch(request,
  roots)` over all 17 read commands with the v1 param contract (flag names
  without dashes, text values, absent = CLI default; full table in the KDoc),
  plus `JdxService.daemonRoots(workspace, query, store)` (`-w` semantics per
  request: stored jars + local-only coords + srcs + JDK flag; missing → exit
  4, bad coord → exit 5). Total functions, no shared parse state (per-call
  `ParamScope`, safe across the daemon's concurrent connections); unknown
  enums / bad ints / bad `--lines` / bad `--grep` / exclusive-flag pairs are
  exit-3 envelopes naming the param. `VERSION`/`DOCTOR`/`HEALTH` through
  `dispatch` answer exit 3 (transport-owned).
- **`server/.../DaemonDispatch.kt`** (new) + **`server/build.gradle.kts`**:
  `jdxServiceHandler` keeps `health`/`version` on the T-041 internals, refuses
  `doctor` exit-6 (names `jdx doctor`; `DoctorService` lives in `:cli`, a
  dependency cycle the other way), and dispatches everything else through
  per-request roots + `toJson(wire)`. New `:server`→`:index` dep (D-004:
  parsing lives in `:index`); test fixtures wiring mirrored from
  `index/build.gradle.kts` for the socket tests.
- **`cli/.../commands/DaemonCommand.kt`**: `daemon run` builds the server with
  the dispatch handler (lateinit capture of the server for `status`, assigned
  before any connection is served). `indexedArtifacts` stays 0 — no index
  lands here (live roots, D-043 precedent).
- **Tests:** index tier-2 `RpcDispatchTest` (28: byte parity per command over
  the fixture jar + crafted case jars, 8 param-error pins, `daemonRoots`
  exit-4/5 pins, hostile-params never-throws + determinism properties — the
  generating family); server tier-2 `DaemonDispatchTest` (5: all 17 commands
  round-tripped over the live socket byte-equal to the in-process call,
  exit-0/command pins, missing-workspace exit 4, doctor exit 6, status query
  counting). Existing `DaemonServerTest` refusal pin still passes (default
  handler untouched).
- **Live proof** (fat jar, isolated `XDG_RUNTIME_DIR` + `HOME`, cleaned up
  after): `daemon restart --workspace fx` over a fixture-jar workspace;
  python socket client → `members`/`show`/`search`/`hierarchy` all `ok:true`;
  warm `members --json` bytes **byte-identical** to one-shot `jdx -w fx
  members --json`; `daemon stop` sweeps, temp workspace removed.

### Decisions made
- **D-058** — dispatch param contract, `:index` placement, per-request `-w`
  roots with no fetch, `doctor` refusal, `indexedArtifacts` stays 0.

### Tasks moved
- T-082: WIP → DONE. Next is **T-042** (transparent CLI daemon client).

### Lessons distilled
- **L-099** — nullable params (`engine`, `lines`) need a presence check:
  absent and invalid both read as null, so `?: return failure` rejects the
  no-flag case.

### What works now (and how to verify it yourself)
```bash
./gradlew :index:tier2Test --tests "dev.jdx.index.service.RpcDispatchTest" -x verifyTier1Budget  # 28 green
./gradlew :server:tier2Test --tests "dev.jdx.server.DaemonDispatchTest" -x verifyTier1Budget  # 5 green
./gradlew check -x verifyTier1Budget  # tiers 1-2 green incl. gates
export XDG_RUNTIME_DIR=/tmp/jdx-try HOME=/tmp/jdx-try-home
./app/build/jdx ws create fx --jars <jar> --no-jdk && ./app/build/jdx daemon start --workspace fx
# raw socket: {"jdx":1,"command":"members","query":"<type>","params":{"limit":"5"}} → ok:true envelope
# compare with: ./app/build/jdx -w fx members <type> --limit 5 --json  # byte-identical
./app/build/jdx daemon stop --workspace fx  # sweeps socket+pid, keeps .log
```

**Caveats, unchanged and pre-existing:**
- `verifyTier1Budget` is red on this machine (pre-existing machine
  variance, 0 test failures — run past the gate via `-x verifyTier1Budget`
  per the session-66 precedent).
- Tier 3 not run: T-082 touches dispatch/roots plumbing over already-covered
  readers (the §13 trigger fires on artifact/index/render changes, and every
  touched path is exercised tier-2 over real jars + the soak corpora are
  untouched).
- `JavapCorpusSoakTest` still reds only on JDK-internal synthetic `access$`
  members (pre-existing, proven on the stashed-clean tree).

### What is broken / half-done
- Nothing from this task. `doctor` through the daemon is an honest exit-6
  refusal (no `DoctorService` in `:server`); the transparent client (T-042)
  decides what to do with it.

### Open questions / blockers
- None.

### Next action
- **M6 T-042** (transparent CLI daemon client + `--no-daemon`; dispatch and
  transport are both live, so the client has warm answers to forward to).

---

## Session 67 — 2026-09-23 — T-041 daemon + unix socket done (M6 transport live)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `d5fb289` (claim) + closing commit (this session)

### Goal
T-041, the M6 transport slice: a background JVM on a version-stamped
unix-domain socket with `daemon start|stop|status|restart`, 5-min idle
shutdown, and framing tests — against the T-040 contract, with no
`JdxService` dispatch unless it fits one sitting (it did not: filed as
T-082).

### What I did
- Claimed T-041 first (`docs/TASKS.md` TODO→WIP, committed `d5fb289` before coding).
- **`server/.../DaemonPaths.kt`** (new, pure): `$XDG_RUNTIME_DIR/jdx/
  <16-hex-sha256-of-workspace>-v<rpc-version>.sock` + `.pid`/`.log`
  siblings; no runtime dir is a `null`, never a fallback.
- **`server/.../DaemonIdle.kt`** (new, pure): `parseIdleDuration`
  (`0` disables, `<n>s|m|h`, bare seconds) + `DEFAULT_IDLE` (5 min).
- **`server/.../DaemonServer.kt`** (new): `DaemonServer` (unix socket,
  strict 1:1 NDJSON line mapping, per-request idle reset via a single-shot
  scheduler, pid file, `snapshot()`), `DaemonProbe` (never-throws
  round-trip + `health` handshake with protocol-level version refusal),
  `defaultDaemonHandler` (`health`/`version` live, reads refused exit-6
  naming T-082). Envelopes hand-rolled with core `JsonEscape` in the exact
  T-010 key order — `server` cannot call the `internal` core builder.
- **`cli/.../commands/DaemonCommand.kt`** (new): `daemon
  start|stop|status|restart|run` thin over `:server` (new cli→server dep;
  D-004 intact). `start` spawns `java.home`'s java detached onto `daemon
  run` and polls health 5 s (idempotent: live daemon exits 0); `stop` kills
  via the pid file and sweeps stale files; `status` prints uptime,
  workspace, memory, indexed artifacts, query count (+ `--json` envelope);
  `run` is the foreground spawn target. Exits 1 = not running, 3 = usage,
  6 = spawn/IO.
- **Tests:** tier-1 `DaemonPathsTest` (8) + `DaemonIdleTest` (9, incl. two
  hostile never-throws properties) + cli `DaemonCommandTest` (7:
  `formatUptime`, exit-1/3/6 paths over a temp runtime dir, ok-false status
  envelope); tier-2 `DaemonServerTest` (13: lifecycle, query counting,
  honest read refusal, malformed + wire-version refusal, double-start
  refusal, idle shutdown with 200 ms idle, idle-clock reset, file sweeping,
  socket framing round-trip + hostile-lines properties over 200 generated
  `RpcRequest`s each).
- **Live proof** (fat jar, `XDG_RUNTIME_DIR=/tmp/jdx-e2e`, cleaned up
  after): `status`→exit 1 stopped; `start`→`daemon started (pid …)`;
  `status` text + `--json` (query count climbs); second `start`→`already
  running` exit 0; raw python socket client → `members` gets the T-082
  exit-6 envelope, `{garbage` + `jdx:999` each get `command:"unknown"`
  envelopes (2 lines in, 2 envelopes out); `stop`→exit 0, repeat→exit 1;
  `--idle soon`→exit 3; unset `XDG_RUNTIME_DIR`→exit 3; `--idle 2s`
  daemon gone after 4 s with socket+pid swept (log kept); `restart` works.
- Filed **T-082** (daemon `JdxService` dispatch) and repointed T-042's
  Depends at T-041+T-082 — no warm answers exist until dispatch lands.

### Findings that surprised me
- Every socket test failed identically (`roundTrip` → `null`) with no
  trace: `SocketChannel.socket()` throws `UnsupportedOperationException`
  for AF_UNIX channels, and the probe's never-throws `catch → null`
  swallowed it. Unix sockets need raw `ByteBuffer` IO (+ `Selector` read
  timeout client-side). See L-098.

### Decisions made
- **D-057** — daemon transport + lifecycle semantics (socket layout, raw
  channel IO, 1:1 line mapping, idle task, spawn model, cli→server dep,
  health shape out of T-046 parity scope).

### Tasks moved
- T-041: WIP → DONE. Filed T-082 (daemon dispatch) as TODO; T-042 now
  depends on T-041 + T-082. Next is **T-082**.

### Lessons distilled
- **L-098** — `Socket.socket()` does not speak AF_UNIX.

### What works now (and how to verify it yourself)
```bash
./gradlew :server:test :server:tier2Test :cli:test --tests 'dev.jdx.server.*' --tests 'dev.jdx.cli.commands.DaemonCommandTest' -x verifyTier1Budget  # 37 tests green
./gradlew check -x verifyTier1Budget  # tiers 1-2 green incl. gates
export XDG_RUNTIME_DIR=/tmp/jdx-try && ./app/build/jdx daemon start --workspace demo && ./app/build/jdx daemon status --workspace demo && ./app/build/jdx daemon stop --workspace demo
```
Nothing query-visible changed: the daemon answers `health`/`version` only;
every read command still runs in-process exactly as after session 66 (the
transparent client is T-042, dispatch is T-082).

**Caveats, unchanged and pre-existing:**
- `verifyTier1Budget` is red on this machine (pre-existing machine
  variance, 0 test failures — not run past the gate here; `-x
  verifyTier1Budget` per the session-66 precedent).
- Tier 3 not run: T-041 touches no artifact reading, indexing or
  rendering, so the `docs/TESTING.md` §13 trigger does not fire.
- `doctor`'s `daemon` row still counts sockets (aliveness upgrade left
  for a follow-up; it names M6, not this task).

### What is broken / half-done
- Read queries through the daemon are refused naming T-082 by design
  (transport slice). `indexed artifacts` reports 0 until T-082 wires the
  store. `daemon` output paths are absolute socket paths only in the
  spawn-failure message (log location) — accepted as diagnostic, like
  `doctor`.

### Open questions / blockers
- None.

### Next action
- **M6 T-082** (daemon `JdxService` dispatch; detail block already in
  `docs/TASKS.md`). T-042 waits on it.

---

## Session 66 — 2026-09-22 — T-040 RPC v1 wire contract done (M6 opened)
**Agent/Author:** Claude Opus 5 (1M context) · **Commits:** `74b3b5b` (claim, previous
session) + closing commit (this session)

### Goal
T-040, the first M6 slice: fix the `JdxService` RPC wire contract in `core` — the
codec only, no socket, no server, no client. T-041…T-046 implement transports
against it.

### What I did
- **`core/src/main/kotlin/dev/jdx/core/rpc/RpcProtocol.kt`** (new, the only
  production file this session): `RPC_VERSION = 1` mirroring `ENVELOPE_VERSION`,
  `REQUEST_TERMINATOR`, `RpcCommand` (17 read-only queries + `version`/`doctor`/
  `health`, each with a stable `wire` name and a `requiresQuery` flag), and
  `RpcRequest(command, query, params)` with `encode()`/`frame()`/`decode()`.
  Encoding is fixed-key-order, params sorted by key, always one line. Decoding is
  a small recursive-descent JSON reader, private to the file, because `core` takes
  no dependencies (D-028).
- **Responses are not defined anywhere new.** A response is the existing
  `ServiceOutcome.toJson(command)` envelope verbatim — that is what makes T-046
  parity structural instead of a pile of per-adapter goldens. Said so in the file
  KDoc so nobody invents a second shape.
- **Tests, written first** (`core` is test-first): `RpcProtocolTest` (30 pinned
  vectors — byte-level encodings, sorted-params determinism, framing, generous
  accepts, every refusal path, the command table itself) and
  `RpcProtocolPropertyTest` (7 properties, 1,000 cases each — round-trip,
  framed round-trip, determinism under shuffled params, one-line invariant,
  never-throws on hostile strings, never-throws on *mutated valid encodings*,
  and every proper prefix refused). The character pool is deliberately hostile:
  quotes, backslashes, raw control characters, JSON punctuation, U+2028/9.
- **Docs:** PROPOSAL.md gained **§14.5** (the shared wire contract — §14 described
  four interfaces but never the bytes they share). D-056 records the seven
  semantic choices; L-096/L-097 record what the mutation run taught.

### Findings that surprised me
- The mutation run was the useful reviewer, not the test count. First measurement
  was **81 %** with 4 uncovered mutants, and the survivors were honest: no test
  sent a `false` param, none sent whitespace *inside* a container, none nested an
  object, none exercised `+` in an exponent. Closing those gaps took it to
  **94 %** with 100 % line coverage.
- Six of the survivors could not be killed at all: I had validated `\uXXXX`
  digits twice (an explicit hex predicate *and* `toIntOrNull(16)`). Redundant
  validation reads as thoroughness and measures as a gap — see L-096. Replacing
  both with one `digitToIntOrNull` accumulation removed 8 mutants and simplified
  the function.
- `char in "+-.eE"` compiles to `indexOf(...) >= 0`, so PIT's boundary mutator
  quietly drops the set's *first* member (L-097).

### Decisions made
- **D-056** — RPC v1 wire contract: NDJSON requests + the existing envelope as
  the response; one request shape always (params emitted even when empty); param
  values are text with numbers/booleans canonicalised; the wire is **read-only**
  in v1 (`ws`/`cache` deliberately absent, so one daemon client cannot
  reconfigure the others); generous-where-safe / strict-where-a-guess-is-
  dangerous decoding; `decode` never throws and nesting is bounded at 32;
  `requiresQuery` lives in the enum so adapters stay thin.

### Tasks moved
- T-040: WIP → DONE. M6: TODO → IN PROGRESS. Next is **T-041** (daemon + unix
  socket). T-041…T-046 detail blocks were already expanded in session 66's claim
  commit and are unchanged.

### Lessons distilled
- **L-096** — two guards that reject the same input are an unkillable mutant, not
  defence in depth.
- **L-097** — PIT's boundary mutator on `c in "chars"` drops the set's first
  character.

### What works now (and how to verify it yourself)
```bash
./gradlew :core:test --tests 'dev.jdx.core.rpc.*' -x verifyTier1Budget   # 37 tests green
./gradlew check -x verifyTier1Budget                                     # tiers 1-2 green, incl. coverage gates
./gradlew :core:pitest -PpitestScope='dev.jdx.core.rpc.*'                # 133/141 killed (94%), line 179/179
```
Nothing user-visible changed: T-040 adds no command and no CLI flag, so `jdx`
behaves exactly as it did after session 65. The contract is consumed by T-041+.

**Caveats, unchanged and pre-existing:**
- `verifyTier1Budget` is red on this machine (251 s vs the 30 s budget, 0 test
  failures — machine variance, same as sessions 53/55/56/58/65). The two new
  suites contribute **0.67 s** of that total, so they are not the cause; the
  slowest entries are `JavaBodiesTest` (32 s) and `DoctorEnvironmentTest` (14 s).
- Tier 3 not run: T-040 is pure strings, touches no jar, no indexing and no
  rendering, so the `docs/TESTING.md` §13 trigger for tier 3 does not fire. The
  stale `JavapCorpusSoakTest` red in `index/build/test-results/soakTest/` is the
  known JDK-internal synthetic `access$` drift from session 65, not this work.
- 8 mutants survive in the rpc package and are equivalent or reachable only by
  damaged input that cannot change the outcome: the `position + 4` guard (a
  `\uXXXX` ending at EOF leaves the string unterminated either way), three
  `readString` early-returns whose leftover text breaks the enclosing object
  regardless, `readEscape` on a trailing backslash, and `forEachIndexed`'s
  `throwIndexOverflow` (needs 2^31 params — the same class of mutant the build
  already excludes for `Intrinsics`).

---

## Session 65 — 2026-09-22 — T-039 Kotlin bodies/KDoc done (M5 closed)
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `a1e3a8b` (claim) + closing commit (this session)

### Goal
Implement T-039, the last T-036 remainder slice (and the last M5 task):
serve `.kt` member bodies + KDoc through the T-038 loader with real PSI
ranges, property-aware, bytecode-authoritative, never-throwing, degrading to
decompile/javap without usable PSI.

### What I did
- Claimed T-039 first (`docs/TASKS.md` TODO→WIP, committed `a1e3a8b` before coding).
- `sources/.../KotlinBodies.kt` (new, ~700 lines): pure `KotlinDecl`/
  `KotlinFile` model + `findKotlinBodies`/`findKotlinMemberDocs`/
  `findKotlinTypeDoc`/`listKotlinMembers` returning the shared Java seam
  types (+ new `ParserUnavailable` cases on all three). Discovery is direct
  `.kt` hit → package-scoped scan (PSI-confirmed) → facade convention;
  matching covers `@JvmName`/unmangled-`internal`/property aliases (`aka`
  from the bytecode match), JVM-spelled `suspend`, extension receivers,
  omitted trailing defaults, `Int`/`Any?` key normalisation, companion
  fallback; slicing trims KDoc the PSI range includes.
- `sources/.../KotlinParser.kt`: `parseKotlin` on the seam, reflective env
  (`idea.home.path` → app env → empty `ExtensionStorage` → project env;
  sibling runtime jars on the loader; deepest-cause degradation detail).
- `sources/.../KotlinToolchain.kt`: `kotlinSidecarDir` + `kotlinMissingHint`
  (`~`-relative, deterministic).
- `index/.../service/JdxService.kt`: every T-039 degradation rewired —
  `body` (incl. properties), `doc` member/type (incl. properties, inherited
  walk), `source` whole-file + `--around`, `body --with-doc`, listing
  `--with-doc`, samples snippets; `kotlinUserHome` test seams on
  Body/Source/DocOptions; enrichment paths use one ambient parser per
  command. `NoSource` with no `.kt` evidence stays `NoSource` (T-026 intact).
- Tests: tier-1 `KotlinBodiesTest` (24 tests incl. hostile-never-throws,
  determinism, line-monotonicity properties); tier-2 `KotlinBodiesPsiTest`
  (9 tests, real compiler symlinked from the Gradle cache, skip when
  absent); tier-2 `KotlinSourcesServiceTest` (13 tests incl. unavailable
  degradation); updated 4 tests that pinned the old T-039 exit-1s.
- Live proof (sidecar symlinked into the real cache, removed afterwards):
  `body …KotlinMembers#fetch` → `KotlinShapes.kt:72` suspend slice;
  `body …KotlinData#nickname` → property slice; `doc …KotlinShapesKt#
  extensionGreeting` → KDoc; whole-file `source`, `--around`, `@JvmName`
  aliasing, `--with-doc` all verified; `doctor kotlin` OK→WARN round-trip.
- Notable finds: forced Vineflower *fails* on the Kotlin fixture class
  ("could not parse decompiled text…", pre-existing engine limit — the T-073
  javap retry is what answers); file-header KDoc over a typealias is not the
  class's KDoc in PSI (test retargeted at `UserIdBox`).

### Decisions made
- **D-055** — T-039 semantics (3-step bootstrap, sidecar-as-set, one parser
  per command, no auto-fetch, shared seam types, deferred mismatch pairing).

### Tasks moved
- T-039: TODO → WIP (`a1e3a8b`) → DONE. T-036: WIP → DONE (umbrella closed).
  M5: TODO → DONE. Filed T-080 (sidecar fetch) + T-081 (Kotlin-aware
  mismatch pairing) as TODO.

### Lessons distilled
- **L-094** — standalone PSI bootstrap recipe (three steps + sibling
  runtime jars + deepest-cause diagnosis).
- **L-095** — KDoc attaches to the following declaration.
- Re-hit **L-039** (nested block comments): a literal `/**` inside a KDoc
  opens a nested comment and breaks the file at EOF.

### What works now (and how to verify it yourself)
```bash
./gradlew :sources:test :sources:tier2Test -x verifyTier1Budget  # incl. KotlinBodiesTest/PsiTest
./gradlew :index:tier2Test --tests "dev.jdx.index.service.KotlinSourcesServiceTest" -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
./gradlew soak -x verifyTier1Budget  # green except the known pre-existing JavapCorpusSoakTest JrtDirectoryStream access$ drift (same signature as sessions 53/55/56/58; CorpusSoakTest incl. the new Kotlin body/source/doc paths green)
# live (needs sidecar): ln -s <gradle-cache jars> ~/.cache/jdx/kotlin/ && ./app/build/jdx body 'dev.jdx.fixtures.KotlinMembers#fetch' --jars testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar --no-jdk
```

### What is broken / half-done
- Nothing from this task. Known deferred (D-055 §6, T-080/T-081): no
  auto-fetch; no Kotlin `SOURCES_VERSION_MISMATCH`; no facade file-docs.

### Open questions / blockers
- None.

### Next action
- **M6 T-040** (`JdxService` RPC protocol; expand the coarse one-liner into a
  detail block when starting, per the board rules).

## Session 64 — 2026-09-22 — T-038 Kotlin PSI loader seam done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `aafe8bd` (claim) + closing commit (this session)

### Goal
Implement T-038, the first T-036 remainder slice: the D-008 loading
infrastructure (versioned sidecar path, isolated side-load, `doctor`
reporting) with no parsing yet — `body`/`source`/`doc` keep their T-039
degradations.

### What I did
- Claimed T-038 first (`docs/TASKS.md` TODO→WIP detail block, committed
  `aafe8bd` before coding); also filed the T-039 detail block (TODO) so the
  last T-036 slice is specified.
- `sources/.../KotlinToolchain.kt` (new): `KOTLIN_COMPILER_VERSION` const
  (mirrors `libs.versions.toml#kotlinCompiler`), versioned sidecar path
  (`~/.cache/jdx/kotlin/kotlin-compiler-embeddable-<version>.jar`),
  `probeKotlinToolchain` returning `Installed`/`Missing` — never throws,
  deterministic.
- `sources/.../KotlinParser.kt` (new): `KotlinSourceParser` seam +
  `openKotlinParser` — present sidecar opens an isolated `URLClassLoader`
  (platform parent, never the app loader) with a load-without-initialise
  `KotlinCoreEnvironment` presence check; absent/corrupt reads as an
  unavailable value with an install hint, never a throw. Compiler names exist
  only as string literals — no compile dependency, nothing in the fat jar.
- `cli/.../service/DoctorService.kt` (`kotlin` row): reports the real probe —
  OK with version+path when present, WARN with the install hint when absent
  (was a hardcoded WARN).
- Tests: tier-2 `KotlinToolchainTest` (14 tests — path/version pins, probe
  missing/present/directory/empty, open missing/garbage/non-compiler-zip/
  presence-stub-compiled-with-the-JDK-compiler, close-idempotence,
  no-compiler-on-classpath premise pin, 200-case hostile-path never-throws
  property); `DoctorServiceTest` + `DoctorTestFixtures` (`kotlinSidecarPresent`)
  pin the OK/WARN rows.
- Live proof: `./app/build/jdx doctor` prints
  `kotlin: warn (side-loaded compiler not installed
  (…/kotlin-compiler-embeddable-2.4.20.jar) — …)` on this machine.
- Nearly deleted L-092's body with a bad `edit` `oldString` (reused the header
  as the whole match); caught via `git diff` before committing and restored.

### Decisions made
- **D-054** — side-load seam semantics: versioned sidecar path, presence (cheap,
  doctor) vs usability (open-time, degrading) split, platform-parent isolated
  loader with load-without-initialise presence check, test-pinned
  no-compile-dependency premise.

### Tasks moved
- T-038: TODO → WIP (`aafe8bd`) → DONE. T-036 stays WIP (T-039 next).

### Lessons distilled
- **L-093** — stub the presence class to test an isolated-loader seam (JDK-
  compile a `KotlinCoreEnvironment` stub, jar it, point the seam at it).

### What works now (and how to verify it yourself)
```bash
./gradlew :sources:tier2Test --tests "dev.jdx.sources.KotlinToolchainTest" -x verifyTier1Budget
./gradlew :cli:test --tests "dev.jdx.cli.service.Doctor*" -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
./app/build/jdx doctor | grep kotlin
# warn … not installed (…/kotlin-compiler-embeddable-2.4.20.jar) … (D-008)
```

### What is broken / half-done
- Nothing from this task. `doctor kotlin` OK fires on presence only — a corrupt
  jar at the sidecar path reads OK until first use, where `openKotlinParser`
  degrades honestly (D-054 §2 accepts this). Full PSI queries land in T-039.

### Open questions / blockers
- None.

### Next action
- **M5 T-039** (Kotlin bodies/KDoc over the T-038 seam; detail block already in
  `docs/TASKS.md`).

## Session 63 — 2026-09-22 — T-075 usages graph enrichment done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `f7b75cf` (claim) + closing commit (this session)

### Goal
Implement T-075, the last fully-specified TODO on the board: `usages --kind
new|throw|annotation` (parked since T-034) plus the `--context` decision.

### What I did
- Claimed T-075 first (`docs/TASKS.md` TODO→WIP, committed `f7b75cf` before coding).
- `index/.../service/JdxService.kt` (`usages`/`executeUsages`): `new` filters
  the T-029 vocabulary to `METHOD_CALL`→`<init>` edges (kind word `new`);
  `throw`/`annotation` scan `ClassInfo` metadata (`throws` declarations,
  class+member annotation uses) over the same live-roots scan; `all` is edges
  plus the two metadata kinds. Metadata kinds are type-level (member refs
  report no usages, exit 1); source-dir mentions stay `ref`-only;
  `--context` keeps its exit-3 `samples` redirect. No extractor or store
  change (D-053).
- `cli/.../commands/UsagesCommand.kt`: `--help`/`--kind` text names the live
  kinds.
- Tests: tier-2 `UsagesCaseJars.kt` gains `u.Widget`/`u.Factory` (ctor),
  `u.Boom` (`throws`), `u.Mark` (class+method annotations) plus a
  `ClassBuilder.annotate` helper; `UsagesServiceTest` pins all three kinds,
  `all`-inclusion and member-ref emptiness (replacing the exit-3 test);
  `UsagesPropertyTest` generates the three new kind words; CLI passthrough
  comment updated.
- Docs: D-053 (enrichment semantics), `docs/DECISIONS.md` index row,
  T-075 DONE, CURRENT STATE.

### Decisions made
- **D-053** — `usages` graph-enrichment semantics (T-075): filtered-view
  `new`, declaration-based `throw` (no `athrow` data-flow in v1), metadata
  `annotation`, type-level-only, `--context` stays a redirect, no store change.

### Tasks moved
- T-075: TODO → WIP (`f7b75cf`) → DONE. T-036 stays WIP (T-038/T-039 next).

### Lessons distilled
- None (no new toolchain/spec gotcha; the metadata-over-edges shape fell out
  of D-042/D-043 directly).

### What works now (and how to verify it yourself)
```bash
./gradlew :index:tier2Test --tests "dev.jdx.index.service.UsagesServiceTest" -x verifyTier1Budget
./gradlew :core:test --tests "dev.jdx.core.render.Usages*" :cli:test --tests "dev.jdx.cli.commands.Usages*" -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
```

### What is broken / half-done
- Nothing from this task. `ATHROW` throw *sites* are not attributed in v1
  (D-053 §2 documents the limit); indexed acceleration still lands with the
  daemon/`jdx index` work.

### Open questions / blockers
- None.

### Next action
- **M5 T-038/T-039** (Kotlin PSI source parsing + Kotlin bodies; file detail
  blocks when starting).

## Session 62 — 2026-09-22 — T-079 annotation-element matching done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `d97c74f` (claim) + closing commit (this session)

### Goal
Implement T-079, the T-074 follow-up: `body`/`doc` on an annotation *element*
(`Tag#value()`, `Matrix#names()`) reported no source counterpart because
`matchByName` had no `AnnotationMemberDeclaration` branch.

### What I did
- Claimed T-079 first (`docs/TASKS.md` TODO→WIP, committed `d97c74f` before coding).
- `sources/.../JavaBodies.kt`: `matchByName` matches annotation elements by
  declared name; `passesArity`/`memberParamsMatch` enforce zero-arity;
  `memberReturnMatches` compares the declared element type (same disambiguator
  as methods — vacuous for singletons, consistent for the seam);
  `sliceBody` slices elements to `METHOD` bodies (mirroring the
  `declaredMembersOf` mapping T-028 already relies on).
- `sources/.../JavaDocs.kt`: `sliceMemberDoc` kinds elements as `METHOD`;
  `docCommentOf` reads their javadoc.
- Tests: tier-1 `JavaBodiesTest` (element slicing, under-specified/zero-arity
  match, parameterised-ref rejection, return-typed graceful match + a 200-case
  generated name×type×default property pinning verbatim/determinism laws);
  tier-1 `JavaDocsTest` (documented element resolves as `METHOD`,
  undocumented element reads as member-not-found for supertype fallback);
  tier-2 `JavaBodiesSourcesTest` sibling block extended to pin
  `Tag#value()` → `Annos.java` (`String value();`).
- Caught while testing: the tier-1 `ref()` helper takes *binary* names, so a
  return-type pin must spell `[I`, not `int[]` — and a wrong return type on a
  singleton still returns the body (the Bridges-test graceful rule), it does
  not go not-found.

### Decisions made
- None (no new D-nnn; branch placement mirrors `declaredMembersOf` by construction).

### Tasks moved
- T-079: TODO → WIP (`d97c74f`) → DONE. T-036 stays WIP (T-038/T-039 next).

### Lessons distilled
- None (L-092 from session 61 already covers the `AnnotationMemberDeclaration` fact).

### What works now (and how to verify it yourself)
```bash
./gradlew :sources:test :sources:tier2Test -x verifyTier1Budget  # element suites
./gradlew :index:tier2Test --tests "dev.jdx.index.service.BodyServiceTest" \
  --tests "dev.jdx.index.service.DocServiceTest" \
  --tests "dev.jdx.index.service.SourceServiceTest" \
  --tests "dev.jdx.index.service.SourcesMismatchServiceTest" -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
./app/build/jdx body 'dev.jdx.fixtures.Tag#value()' --jars "$JAR" --no-jdk
# exit 0: String value(); from Annos.java (was: no source counterpart)
./app/build/jdx body 'dev.jdx.fixtures.Matrix#names()' --jars "$JAR" --no-jdk
# exit 0: String[] names() default {}; from Annos.java
```

### What is broken / half-done
- Nothing from this task. Known adjacent remainder: T-075 (`usages` graph
  enrichment) stays the only low-priority TODO alongside WIP T-036.

### Open questions / blockers
- None.

### Next action
- **M5 T-038/T-039** (Kotlin PSI source parsing + Kotlin bodies; file detail
  blocks when starting).

## Session 61 — 2026-09-22 — T-074 `srcmap` siblings done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `701fde9` (claim) + closing commit (this session)

### Goal
Implement T-074, the T-020 `srcmap` remainder: same-file top-level siblings
(`Tag`/`Tags`/`Matrix` in `Annos.java`) resolved to no source file because
`binaryName → source file` mapped per outer name only.

### What I did
- Claimed T-074 first (`docs/TASKS.md` TODO→WIP, committed `701fde9` before coding).
- `sources` (new `SourceSiblings.kt`): `findJavaSourcePath(root, binaryName)` —
  direct outer hit first, else a package-scoped sibling scan (same directory
  only, never a full walk) parsing each `.java` candidate's top-level type
  names with JavaParser `BLEEDING_EDGE` (same grammar choice as `parseJavaUnit`).
  A sibling `.java` wins over a `.kt` direct hit; `.kt`-only and missing cases
  are preserved. Never throws — per-file failures skip that file.
- `sources/.../JavaBodies.kt` (`loadJavaUnit`): resolves through
  `findJavaSourcePath` instead of `SourceRoot.findSource`, so
  `body`/`source --around`/`doc`/`listJavaMembers` (T-028 pairing) all find
  siblings with no further changes.
- `index/.../service/JdxService.kt` (`sourceOutcome`): whole-file `source`
  resolves through `findJavaSourcePath`, so
  `jdx source dev.jdx.fixtures.Tag` serves `Annos.java`.
- Tests: tier-1 `SourceSiblingsTest` (9 examples + 3 properties —
  hostile-input totality, determinism, generated multi-type shared-file
  resolution); tier-2 fixture pins (`JavaBodiesSourcesTest` sibling block,
  `SourceServiceTest` sibling whole-file).
- Filed T-079 (annotation-element member matching) instead of folding it in.

### Decisions made
- None (no new D-nnn; package-scoped scan follows the task's "without a full
  scan" constraint by construction).

### Tasks moved
- T-074: TODO → WIP (`701fde9`) → DONE. Filed T-079 (annotation-element
  `matchByName` gap found while testing). T-036 stays WIP (T-038/T-039 next).

### Lessons distilled
- **L-092** — JavaParser annotation elements are `AnnotationMemberDeclaration`,
  not `MethodDeclaration` (matchers must branch explicitly).

### What works now (and how to verify it yourself)
```bash
./gradlew :sources:test :sources:tier2Test -x verifyTier1Budget  # sibling suites
./gradlew :index:tier2Test --tests "dev.jdx.index.service.SourceServiceTest" \
  --tests "dev.jdx.index.service.BodyServiceTest" \
  --tests "dev.jdx.index.service.DocServiceTest" -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
./app/build/jdx source 'dev.jdx.fixtures.Tag' --jars "$JAR" --no-jdk
# exit 0: Annos.java whole file with sources provenance (was: no source counterpart)
./app/build/jdx body 'dev.jdx.fixtures.Annos#tagged()' --jars "$JAR" --no-jdk
# exit 0: verbatim slice from Annos.java
```

### What is broken / half-done
- Nothing from this task. Known adjacent gap, filed not fixed: `body`/`doc` on
  an annotation *element* (`Tag#value()`, `Matrix#names()`) still report no
  source counterpart — the file now resolves but `matchByName` has no
  `AnnotationMemberDeclaration` branch (T-079). Pre-existing T-021 gap, proven
  unrelated to the sibling scan (single-file annotations miss the same way).

### Open questions / blockers
- None.

### Next action
- **M5 T-038/T-039** (Kotlin PSI source parsing + Kotlin bodies; file detail
  blocks when starting). T-075/T-079 stay low-priority TODOs.
