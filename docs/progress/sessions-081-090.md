# Session log — shard 9: sessions 081–090

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

---

## Session 85 — 2026-09-24 — T-086 warm text output done (board empty, no follow-ups left)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `3d5876c` (claim) + closing commit (this session)

### Goal
Implement one task and push. Board was empty (all 85 DONE), so filed the next
free number from the last `open-items.md` deferred follow-up: warm text output
(T-042 serves `--json` warm, text stays in-process in v1 — D-059).

### What I did
- Claimed T-086 first (`docs/TASKS.md` detail block as WIP, committed `3d5876c`
  before coding). Took the text-wire option (D-059): the daemon renders plain
  text server-side into an extra envelope field, so no client-side model
  reconstruction.
- `index/.../service/RpcDispatch.kt`: new `JdxService.dispatchJson`
  (dispatch + serialise) + `ServiceOutcome.toJsonWithWarmText(command, params)`
  + `injectWarmText`. Without `warmText=true` the bytes are exactly
  `toJson(wire)`; with it, plain `renderText(false)` (`renderBriefText` under
  `warmBrief=true`, `TokenBudget.capLines` under a valid `warmMaxLines >= 0`)
  is injected before the trailing `"warnings"` marker (last occurrence, so a
  result payload containing the marker cannot misplace it). Hostile
  presentation params degrade to the plain rendering; a missing marker returns
  the envelope unmodified. Never throws.
- `server/.../DaemonDispatch.kt`: `jdxServiceHandler` answers both the Ready
  and the Failed (exit 4/5) paths through the new serialisation, so warm text
  works for root failures too.
- `cli/.../DaemonClient.kt`: `shouldAttempt` no longer refuses text; new
  `WarmTextHit` + `tryWarmText` + `parseWarmText` (string-only, never throws);
  `serveWarmIfReady` takes `brief`/`warmMaxLines` (defaults cold-correct) and
  on the text path forwards `warmText`/`warmBrief`/`warmMaxLines` params,
  prints the `text` field verbatim, and carries the envelope exit code. A
  missing `text` field (pre-T-086 daemon) degrades to in-process. Warm text is
  always plain (no ANSI — the daemon has no TTY); piped output is identical.
- `cli/.../commands/ReadCommands.kt`: `members`/`outline` forward
  `--brief`/`--max-lines` to the warm path (the only text-only flags; every
  other text-affecting flag already rides the wire as a query param).
- Tests: `DaemonClientTest` +5 (text may-go-warm, text print + exit carriage,
  missing-text fallback incl. non-string `text`, brief/max-lines param
  forwarding, `parseWarmText` 1000-case hostile property) with the stale
  `no-daemon and text stay in-process` guard split; `RpcDispatchTest` +4
  (JSON immunity, text pin, brief/cap shaping, failure text) + `dispatchJson`
  totality inside the hostile property (L-114: the first immunity assertion
  used substring absence and tripped on `BodyBlock`'s own `"text"` field —
  replaced with byte-identity); `DaemonWarmTest`: text-cold test rewritten to
  warm-text parity (all-17 JSON loop untouched), new every-command warm-text
  parity + brief/cap test, hostile parity now expects `dispatchJson` (hostile
  params may carry `warmText`).
- Verified: `./gradlew check -Ptier1.budget=10000` green (1m39s; the bare
  `verifyTier1Budget` stays red at the default 30 s budget — pre-existing
  machine variance, 0 failures). Live proof against the built binary with a
  real daemon (`warm-t` workspace, since removed): cold-vs-warm `show`
  byte-identical, warm `--json` still verbatim vs `--no-daemon`, warm
  `--brief --max-lines 2` byte-identical to cold. Restored workspace state
  (`warm-t` removed, default cleared — `fx`/`serve-proof` remain).

### Decisions made
- **D-067** — warm text output via a server-rendered `text` envelope field
  (text wire, not a JSON→Outcome parser; presentation-only params; inject
  before trailing `warnings`; plain always; only `members`/`outline` forward
  presentation flags).

### Tasks moved
- T-086: TODO → WIP (`3d5876c`) → DONE. Board empty again (T-001…T-086, all
  DONE); no deferred follow-ups left.

### Lessons distilled
- **L-114** — pin envelope-field absence with byte-identity, not substring
  absence (`testing`).

### What works now (and how to verify it yourself)
```bash
./gradlew check -Ptier1.budget=10000  # green (tiers 1-2 + lint + coverage)
./gradlew :cli:test --tests "dev.jdx.cli.DaemonClientTest" -Ptier1.budget=10000
./gradlew :index:tier2Test --tests "dev.jdx.index.service.RpcDispatchTest" -Ptier1.budget=10000
./gradlew :cli:tier2Test --tests "dev.jdx.cli.DaemonWarmTest" -Ptier1.budget=10000
./gradlew :app:installDist -q && XDG_RUNTIME_DIR=/tmp/opencode/jdx-rt app/build/jdx daemon start --workspace <ws>  # then diff text/json vs --no-daemon
```

### What is broken / half-done
- Nothing from this task. Known: warm text is plain (no ANSI) even on a TTY —
  documented in D-067 §4; piped output is byte-identical.

### Open questions / blockers
- None.

### Next action
- **Board empty** — new work gets the next free number (T-087), or owner
  direction.

---

## Session 84 — 2026-09-24 — T-085 `cache gc` daemon-log sweep done (one follow-up left)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `90e2ab9` (claim) + closing commit (this session)

### Goal
Implement one task and push. Board was empty (all 84 DONE), so filed the next
free number from the `open-items.md` deferred follow-ups: daemon `.log`
retention (idle shutdown and `stop` sweep socket + pid but keep `.log`, with
no rotation or `gc` coverage).

### What I did
- Claimed T-085 first (`docs/TASKS.md` detail block as WIP, committed `90e2ab9`
  before coding).
- `index/.../cache/CacheService.kt`: injectable `daemonRuntimeDir: Path? = null`
  (null skips — today's behaviour) + `daemonSocketAlive: (Path) -> Boolean`;
  `gc()` sweeps top-level `*.log` regular files whose sibling `.sock` is absent
  or answers no daemon, live-daemon logs kept. Runs even when the index DB is
  absent; exit-4/6 failures still touch nothing. Never throws: missing dir
  sweeps nothing, throwing probe keeps, IO failures keep. New `DaemonLogSweep`
  (sorted names + bytes) rides `GcReport` with a default, so seam-off reports
  are unchanged.
- `cli/.../commands/CacheCommands.kt`: production `cacheGroup` wires the real
  runtime dir + `DaemonProbe.health(it) != null`; text renders a `daemon logs:`
  line only when something was/would be deleted (seam-off bytes identical);
  JSON gains `daemonLogsDeleted` + `daemonLogBytesFreed`; `gc` help mentions
  the sweep.
- Caught by test while verifying: first implementation mapped a throwing probe
  to orphaned (delete) via `runCatching{}.getOrDefault(false)`; the task's
  conservative rule says keep. Fixed `daemonLogAlive` to return `true` on
  probe throw — the `gc keeps logs when the probe throws` test pins it.
- Tests: `CacheServiceTest` +8 examples; new `DaemonLogSweepPropertyTest`
  (200 cases: sorted/conservative/idempotent/dry-run-promise laws — the
  generating family); `CacheCommandsTest` tier-2 +3 (text sweep, JSON fields,
  dry-run).
- Docs: `TASKS.md` T-085 DONE, **D-066**, this entry, CURRENT STATE,
  `open-items.md` (follow-up filed out).

### Decisions made
- **D-066** — orphan daemon `.log` gc semantics (gc owns orphans, sweep runs
  without a DB but not on failure, conservative deletion, names-not-paths,
  injectable seam).

### Tasks moved
- T-085: TODO → WIP (`90e2ab9`) → DONE. Remaining deferred follow-up: warm
  text output (D-059).

### Lessons distilled
- None. (The probe-throw catch was a spec-vs-code mismatch the new test
  existed to catch — ordinary TDD, already covered by D-020.)

### What works now (and how to verify it yourself)
```bash
./gradlew :index:test --tests "dev.jdx.index.cache.*" :cli:tier2Test --tests "dev.jdx.cli.commands.CacheCommandsTest" -Ptier1.budget=10000  # 40/40
./gradlew check -Ptier1.budget=10000  # green, tiers 1-2 + lint + coverage
export XDG_RUNTIME_DIR=/tmp/jdx-run && ./app/build/jdx cache gc --dry-run  # daemon logs: would delete … (orphans only)
./app/build/jdx cache gc --json  # result.daemonLogsDeleted + daemonLogBytesFreed
```

### What is broken / half-done
- Nothing from this task. Dead `.sock`/`.pid` files are still `stop`/`doctor`
  business (this task takes logs only, D-066 §1). No log rotation — the sweep
  bounds growth instead; revisit if daemon chatter grows.

### Open questions / blockers
- None.

### Next action
- Remaining deferred follow-up: warm text output (D-059, T-042 serves `--json`
  warm; text stays in-process) — or owner direction. Push this session's
  commits (the standing "implement 1 task and push" covers it).

---

## Session 83 — 2026-09-24 — T-084 `doctor` daemon aliveness done (board empty again)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `8c1e86c` (claim) + closing commit (this session)

### Goal
Implement one task and push. Board was empty (all 83 DONE), so filed the next
free number from the `open-items.md` deferred follow-ups: `doctor` daemon
aliveness (probe each `.sock` with `health`, report running/stale).

### What I did
- Claimed T-084 first (`docs/TASKS.md` detail block as WIP, committed `8c1e86c`
  before coding).
- `cli/.../service/DoctorService.kt`: `daemonCheck` probes every
  `$XDG_RUNTIME_DIR/jdx/*.sock` (sorted) through an injectable
  `(Path) -> DaemonStatusSnapshot?` defaulting to the real `DaemonProbe.health`.
  All answer → OK `N running (ws, …)` (workspaces sorted); none answer → WARN
  `M stale socket(s) (…) — no daemon answers (delete the file(s) or restart
  the daemon)`; mixed → WARN naming both. Throwing probes read as stale.
  No-runtime-dir / missing-dir / no-socket rows unchanged. Never throws,
  single-line via `check()`.
- Tests: `DoctorServiceTest` +6 (running-only OK, stale-only WARN with the real
  default probe over dummy files, mixed WARN, throwing-probe stale, no-socket
  OK, plus a 100-case hostile-socket-layout determinism/single-line property —
  the generating family); new tier-2 `DoctorDaemonLiveTest` +2 (live
  `DaemonServer` + dead file → mixed WARN; stopped daemon + leftover → stale
  WARN, both with the real probe); `DoctorEnvironmentTest` 576-combination
  matrix repinned (SOCKETS now expects stale WARN).
- Docs: `TASKS.md` T-084 DONE, **D-065**, `LESSONS.md` + shard (L-113),
  `open-items.md` (follow-up filed out, T-084 closing row), this entry,
  CURRENT STATE.
- Verified: `./gradlew check -Ptier1.budget=10000` green (tiers 1–2 + lint).
  Live proof against the built binary with a real daemon: stale-only → warn,
  running+stale → warn naming both, running-only → `ok (1 running (default))`
  in text and `--json`, after `daemon stop` + cleanup → `ok (not running)`.
  (`verifyTier1Budget` at the default 30 s budget stays red on this machine —
  pre-existing variance, 0 test failures; the override is the documented path.)

### Decisions made
- **D-065** — doctor daemon aliveness semantics (injectable probe, stale is
  WARN never FAIL, running names workspaces / stale names files).

### Tasks moved
- T-084: TODO → WIP (`8c1e86c`) → DONE. Board empty again (T-001…T-084).

### Lessons distilled
- **L-113** — a callable reference keeps defaulted parameters: `DaemonProbe::health`
  does not assign to `(Path) -> …` (defaults are not overloads); default to a lambda.

### What works now (and how to verify it yourself)
```bash
./gradlew :cli:test --tests "dev.jdx.cli.service.*" -Ptier1.budget=10000  # 33/33 incl. the property
./gradlew :cli:tier2Test --tests "dev.jdx.cli.service.DoctorDaemonLiveTest" -Ptier1.budget=10000  # 2/2 live
./gradlew check -Ptier1.budget=10000  # green, tiers 1-2 + lint
export XDG_RUNTIME_DIR=/tmp/jdx-run && ./app/build/jdx daemon start && ./app/build/jdx doctor | grep daemon  # ok (1 running (default))
```

### What is broken / half-done
- Nothing from this task. Remaining deferred follow-ups (no task number):
  warm text output (D-059) and daemon `.log` retention — both in `open-items.md`.

### Open questions / blockers
- None.

### Next action
- Board is empty (all 84 tasks DONE). Owner direction decides what comes
  next — push this session's commits (the standing "implement 1 task and push" covers it).

## Session 82 — 2026-09-24 — T-083 test-source/Java warnings-as-errors done (board all-DONE)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `1cbdfbb` (claim) + closing commit (this session)

### Goal
Implement one task and push. Picked T-083 (only remaining TODO:
test-source + Java warnings-as-errors, filed session 76 as the T-052 split).

### What I did
- Claimed T-083 first (`docs/TASKS.md` TODO→WIP, committed `1cbdfbb`
  before coding).
- Fixed all 11 test-source warnings (all in `core`, the only module with
  any — proven by a full `--rerun-tasks` sweep):
  - 5× `shouldNotBeNull { msg }` "unused expression"
    (`GenericSignatureTest` ×3, `GenericSignaturePropertyTest` ×2) → the
    messages were genuinely dropped: `javap` on kotest's `MatchersKt`
    shows the block overload is `(T, (T) -> Unit)`, so the message string
    coerces to `Unit`. Replaced with `requireNotNull(x) { msg }` (lazy
    message kept, smart-cast) at 6 sites — including
    `JvmDescriptorPropertyTest`, which never warned but had the identical
    latent misuse — and dropped the now-unused kotest imports.
  - 4× unnecessary `!!` (render property tests) → capture-then-check
    locals (`val truncation = cut.truncation; if (truncation == null) …`),
    smart-cast, no assertion. First attempt kept the check on
    `cut.truncation` (no smart-cast on the local) — caught on re-read
    before compiling.
  - 1× deprecated `Arb.stringPattern` → `Arb.pattern` (same `(Arb, String)`
    signature, verified via `javap` on `PatternKt`).
  - 1× `ExperimentalKotest` opt-in → `-opt-in` compiler flag on
    `compileTestKotlin` (D-064 §1). The blanket flag on
    `compileTestFixturesKotlin` was tried first and breaks the build
    (unresolved opt-in marker is a hard error — no kotest on that
    classpath), so the flag is scoped to `compileTestKotlin` only.
- Java decision (D-064 §2): `-Werror` on every `JavaCompile` (all Java in
  the repo is `testfixtures` fixtures, so excluding the module would gate
  nothing) + `@SuppressWarnings("strictfp")` at the
  `VarargsAndModifiers.fp` declaration — proven to silence the warning via
  a `javac` scratch test before wiring the gate.
- Gates: `allWarningsAsErrors` on `compileTestKotlin` +
  `compileTestFixturesKotlin`; Java `-Werror` (shared `subprojects` block,
  task-scoped like T-052).
- Negative proofs (both bite, then removed): planted unnecessary `!!` in a
  test source fails `compileTestKotlin` with `-Werror`; de-suppressed
  `strictfp` fails `compileJava` with `-Werror`. (First Kotlin plant used
  `!!` on a nullable — necessary, no warning — and top-level vals draw no
  unused warning; replanted with `Int` + `!!`.)
- Full `./gradlew check -Ptier1.budget=10000` green (4m14s, tiers 1–2 +
  lint + coverage, 0 failures). Zero warnings on full
  `compileTestKotlin`/`compileTestFixturesKotlin`/`compileJava
  --rerun-tasks`. No tier-3 run: test-only + build-logic changes
  (T-047/T-052 precedent).

### Decisions made
- **D-064** — T-083 gating scope (opt-in flag on `compileTestKotlin` only;
  Java `-Werror` everywhere with declaration-level suppression;
  `requireNotNull` over `shouldNotBeNull { msg }`).

### Tasks moved
- T-083: TODO → WIP (`1cbdfbb`) → DONE. Board is now all-DONE
  (T-001…T-083); M7 DONE.

### Lessons distilled
- **L-111** — probe the matcher overload before "fixing" an
  unused-expression warning (the warning reported real message loss).
- **L-112** — a one-build `:lint` validation red after build-logic
  changes is stale state until proven otherwise (see below).

### What works now (and how to verify it yourself)
```bash
./gradlew compileTestKotlin compileTestFixturesKotlin compileJava --rerun-tasks 2>&1 | grep -cE "^w: |^e: |warning:"  # 0
./gradlew check -Ptier1.budget=10000  # green, tiers 1-2 + lint + coverage
./gradlew lint  # clean
```

### What is broken / half-done
- Nothing from this task. One transient to know about: the first `check`
  after wiring the gates failed `:lint` validation ("uses this output of
  `:core:compileKotlin`"), but the stashed-clean tree passed, `lint` alone
  passed, the same `check` passed on retry (green), and forced
  lint+compile co-executions pass with and without the change — stale
  state from interleaved `--rerun-tasks`/stash churn (L-112). A narrowing
  of lint's declared inputs was tried and reverted as unneeded.

### Open questions / blockers
- None.

### Next action
- Board is empty (all 83 tasks DONE). Owner direction decides what comes
  next — push this session's commits (D-012: unpushed sessions need owner
  go-ahead per session; the standing "implement 1 task and push" covers it).

## Session 81 — 2026-09-23 — T-081 Kotlin-aware mismatch pairing done (stale `.kt` warns, matched stays silent)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `c97159f` (claim) + closing commit (this session)

### Goal
Implement one task and push (explicit owner go-ahead this session).
Picked T-081 (lowest-numbered TODO: `@Metadata`-aware mismatch pairing).

### What I did
- Claimed T-081 first (`docs/TASKS.md` TODO→WIP, committed `c97159f`
  before coding).
- `sources/.../SourcesMismatch.kt` (new `detectKotlinSourcesMismatch`):
  projects bytecode into declaration space (display names via
  `kotlinMethodViews`, `Continuation` strip, folded accessors/fields
  dropped for body-declared property rows, primary-ctor props via
  `<init>`), pairs with `kotlinTypeKey` params + the typealias leniency
  (unresolvable source spelling vs known JVM type), excuses leftovers
  only (data/value synthetics, `Marker` ctors, `@JvmOverloads` shorts,
  companion-evidenced outer statics, plain-named synthetics kept for
  `inline` impls). `DefaultConstructorMarker` overloads no longer count
  toward the implicit-default rule (shared with the Java detector —
  behaviour-identical there). Non-Kotlin input delegates to JVM pairing.
- `sources/.../KotlinBodies.kt`: `listedKotlinMembers` counts the
  extension receiver as the leading param (facade statics pair; only
  other consumer is a name-only check).
- `index/.../service/JdxService.kt`: `mismatchWarning` tries Java, then
  Kotlin when `isKotlin` — shared per-command parser on body/doc/around,
  throwaway from `kotlinUserHome` on whole-file `source`; unavailable
  stays silent, never throws. All six call sites threaded.
- Calibrated with a throwaway tier-2 dump of real `ClassInfo` views +
  listings over the fixture jar (L-110): five false-positive shapes found
  and fixed before real tests (companion statics, synthetic `inline`
  impls, `UserId` alias, primary-ctor props, `const`-in-`$Companion`);
  scratch deleted after.
- Tests: sources tier-1 `SourcesMismatchTest` +13 (11 Kotlin examples +
  never-throws/determinism properties) + index tier-2
  `KotlinMismatchServiceTest` (5: matched silence over
  suspend+alias/`@JvmName`/internal/defaults/properties/data/value/
  object/facade bodies+sources; added/renamed stale warns naming both
  sides; determinism + text⊆JSON). Fixed two self-inflicted test
  failures (source refs render raw spellings: `#brandNew(Int)`).
- Docs: `TASKS.md` T-081 DONE, **D-063**, `LESSONS.md` + shard (L-110),
  `open-items.md`, this entry, CURRENT STATE.
- Verified: `./gradlew check -Ptier1.budget=10000` green (tiers 1–2 +
  lint, 0 failures). No tier-3 run: no artifact/index/render change —
  service-only warnings logic over already-covered readers (T-047/T-052
  precedent); soak invariants (no crash, valid JSON, determinism)
  unaffected by warning text.

### Decisions made
- **D-063** — Kotlin mismatch pairing semantics (declaration-space
  pairing, leftover-only excuses, alias leniency, T-039 parser wiring,
  two documented limitations).

### Tasks moved
- T-081: TODO → WIP (`c97159f`) → DONE. Remaining: T-083 only
  (test-source/Java warnings-as-errors, TODO, low priority).

### Lessons distilled
- **L-110** — calibrate pairing rules against a scratch dump of the real
  fixture (dump compiler-output shapes first, rule second).

### What works now (and how to verify it yourself)
```bash
./gradlew check -Ptier1.budget=10000  # green incl. lint + the 18 new tests
./gradlew :sources:test --tests "dev.jdx.sources.SourcesMismatchTest"  # 13 new Kotlin cases
./gradlew :index:tier2Test --tests "dev.jdx.index.service.KotlinMismatchServiceTest"  # 5/5
./app/build/jdx body 'dev.jdx.fixtures.KotlinMembers#fetch' --jars testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar --no-jdk  # no SOURCES_VERSION_MISMATCH
./app/build/jdx source dev.jdx.fixtures.KotlinData --jars testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar --no-jdk  # silent
```

### What is broken / half-done
- Nothing from this task. Known limitations (in the detector KDoc):
  companion `const` under direct `$Companion` queries may warn (const
  lives only in the outer bytecode); removing a body-declared property
  from sources is not detected (hidden accessors dropped
  unconditionally).

### Open questions / blockers
- None.

### Next action
- **T-083** (test-source + Java warnings-as-errors) — last remaining
  task, low priority; or owner direction.
