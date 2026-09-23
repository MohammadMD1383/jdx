# Session log — shard 8: sessions 071–080

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

## Session 76 — 2026-09-23 — T-052 warnings-as-errors, lint, final API review done

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `dd3a681` (claim) + `629b4f5` (cli scoping) + closing commit (this session)

### Goal
Implement one M7 task and push (explicit owner go-ahead this session).
Picked T-052 (warnings-as-errors, lint, final API review): next in M7 value
order. Wrote the detail block when starting per the board rule; pre-claim
probes (full `--rerun-tasks` warning sweep, `explicitApi` trial compile on
`index`, lint-rule cleanliness over every source set) set a shippable scope,
with the test-source + Java remainder split to T-083 up front.

### What I did
- Claimed T-052 first (`docs/TASKS.md` TODO→WIP + detail block + new T-083,
  committed `dd3a681` before coding).
- Fixed the 2 main-source warnings from the sweep:
  `index/.../kotlin/KotlinMembers.kt` redundant `else` (the classifier `when`
  over sealed `KmClassifier` is exhaustive — dropped; the variance `when` is
  over *nullable* `KmVariance`, so its `else` became an explicit `null`
  branch — first attempt removed the wrong `when`'s `else` and the build
  said so) and `server/.../HttpServer.kt:90` redundant `as?`
  (`HttpServer.address` is already non-null `InetSocketAddress`). Both
  behavior-preserving (unreachable branch / identical type).
- `allWarningsAsErrors` on every module's main `compileKotlin` (task-scoped
  in the shared `subprojects` block; test compilations stay under T-083,
  Java ungated — the deliberately-`strictfp` fixture would fail `-Werror`).
- `explicitApi()` on `index`/`sources`/`decompile`/`mcp`/`server` — all
  compile clean (`core` already had it). `cli` reverted in `629b4f5`: 99
  violations across Clikt wiring with no Kotlin consumers — `public` noise
  for zero API safety (its contract is the CLI surface, pinned by help +
  parity goldens). `app` (assembly) and `testfixtures` (deliberately-nasty
  fixtures) excluded likewise.
- New dependency-free root `lint` task wired into every module's `check`
  (CONTRIBUTING.md already promised "tests + lint"): trailing whitespace,
  tabs, bare `TODO`/`FIXME` without `T-nnn`, console IO in library mains.
  100-column stays a soft limit (774 lines over it), explicitly not gated.
  Config-cache safe (plain-File captures only, T-067 pattern).
- Verified: `./gradlew check -Ptier1.budget=10000` green (tiers 1–2, lint
  included; the budget flag is the known machine-variance caveat, not a
  failure). Negative proofs, both removed after: planted redundant-`else`
  fails `:core:compileKotlin` with `warnings found and -Werror specified`;
  planted trailing-WS + bare-TODO fails `lint` with file:line pointers.
  (Side note: an unused-local probe produced no warning at all — gate
  proven with the redundant-`else` class instead.)
- No tier-3 run: both fixes remove provably-unreachable branches, so no
  corpus behaviour can differ (T-047 precedent for tier-1–2 verification).

### Decisions made
- None (no new D-nnn). `cli` out of `explicitApi`, 100-col ungated, Java
  ungated, test sources deferred to T-083 — all recorded as task-scope
  rationale, reversible in one line each.

### Tasks moved
- T-052: TODO → WIP (`dd3a681`) → DONE (this session). Filed T-083
  (test-source + Java warnings-as-errors) as the split remainder. Next:
  T-048 (AppCDS) — M7 order is now T-048, T-050, T-051 last.

### Lessons distilled
- L-106 (a self-referential lint rule flags its own implementation — the
  bare-TODO rule fired on its own source lines; carry the owning task ref).

### What works now (and how to verify it yourself)
- `./gradlew lint` — `lint: clean.`, exit 0.
- `./gradlew compileKotlin --rerun-tasks` — zero `w:` lines, exit 0
  (warnings are errors on mains now).
- `./gradlew check -Ptier1.budget=10000` — tiers 1–2 + lint green.
- Plant your own check: add `else -> x` to any exhaustive `when` in a main
  source → compile fails; add a trailing space to any `.kt` → `lint` fails.

### What is broken / half-done
- Nothing from this task. Test-source warnings (~11: kotest opt-in,
  `shouldNotBeNull { msg }` unused-expression — columns point at the message
  string, investigate a possible silently-dropped message before "fixing" —
  unnecessary `!!`, deprecated `Arb.stringPattern`) and the Java `strictfp`
  fixture are T-083, not this task.

### Open questions / blockers
- None.

### Next action
- **M7 T-048** (AppCDS archive generation) — write the detail block when
  starting. Then T-050 (`bench`), T-051 (README/install) last. T-080/T-081
  stay low priority.

---

## Session 75 — 2026-09-23 — T-047 token budgets (`--brief` + `--max-lines`) done

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `f0580ac` (claim) + closing commit (this session)

### Goal
Implement one M7 task and push (explicit owner go-ahead this session).
Picked T-047 (token budgets): lowest-numbered TODO with DONE deps. Wrote the
detail block when starting per the board rule; scoped to one sitting as
`members`/`outline` text-only flags, so the T-040 wire and all adapters stay
untouched.

### What I did
- Claimed T-047 first (`docs/TASKS.md` TODO→WIP + detail block, committed
  `f0580ac` before coding).
- Core (test-first): new `core/.../render/TokenBudget.kt` (`capLines` —
  whole-line cap, universal newlines, POSIX trailing-newline rule, `… K more
  lines (--max-lines M to see more)` footer); `MemberListing.renderBriefText()`
  (header + bare rows; object summary + limit footer + warnings kept; group
  headers + doc suffixes + provenance dropped) + `MemberRow.briefLine()`;
  `ServiceOutcome.renderBriefText` default in `index/.../service/JdxService.kt`
  (only `MemberList` overrides).
- CLI: `members` + `outline` gain `--brief` and `--max-lines N` (both text
  only — `--json` bytes byte-identical, so DaemonClient/RPC/HTTP/MCP and the
  T-046 parity proof are untouched); `ReadCommandSupport.finish` takes the
  budget params, `validateMemberFlags` rejects `--brief --with-doc` and
  `--max-lines < 0` with exit 3.
- Tests: core `TokenBudgetTest` (6) + `TokenBudgetPropertyTest`
  (whole-line-prefix law + hostile never-throws/determinism/no-`\r`
  properties) + `MemberListingBriefTest` (4 examples + brief⊆full property);
  cli tier-1 `ReadCommandsTest` +6 (rendering, both exit-3 paths, `--json`
  immunity); tier-2 `ReadCommandsServiceTest` +3 (JDK brief, outline brief,
  max-lines cap + JSON immunity) + `TokenBudgetGoldenTest`
  (`golden/members-budget/`, 6 text goldens over Generics/TrafficLight$1/
  KotlinMembers — read the diff before accepting, per the golden rule).
- Docs: PROPOSAL.md §7.1 `members`/`outline` + Appendix B rows; L-105.
- Verified: `./gradlew check -Ptier1.budget=10000` green (tiers 1–2, 0
  failures; the only red at the default budget is the known pre-existing
  `verifyTier1Budget` machine-variance gate). Live: `members HashMap --brief`
  (bare rows + Object summary + limit footer + warning, no `source:`),
  `--max-lines 6` (prefix + footer), `--brief --with-doc` (exit 3),
  `--json --brief --max-lines 1` (byte-identical to `--json`).

### Decisions made
- None (no new D-nnn). Text-only scoping follows D-059 (no text wire, so
  text flags need no RPC/adapter work); the exit-3 combos follow the
  `--static`/`--instance` precedent; the POSIX trailing-newline rule is a
  spec choice recorded in L-105 rather than a decision entry.

### Tasks moved
- T-047: TODO → WIP (`f0580ac`) → DONE (this session). Next: T-052 (M7 value
  order is T-052, T-048, T-050, T-051 last).

### Lessons distilled
- L-105 (POSIX trailing-newline rule for line-counting specs and oracles —
  the new property caught the naive-split disagreement at attempt 39).

### What works now (and how to verify it yourself)
- `app/build/jdx members java.util.HashMap --limit 5 --brief` — bare rows,
  exit 0, no `source:` line.
- `app/build/jdx members java.util.HashMap --max-lines 6` — 6 lines + footer.
- `./gradlew :core:test --tests "dev.jdx.core.render.TokenBudget*" --tests "dev.jdx.core.render.MemberListingBriefTest" -Ptier1.budget=10000` — green.
- `./gradlew :cli:tier2Test --tests "dev.jdx.cli.commands.TokenBudgetGoldenTest" --tests "dev.jdx.cli.commands.ReadCommandsServiceTest"` — green.

### What is broken / half-done
- Nothing from this task. Budgets on the other commands (`search`, `usages`,
  `tree`, `show`, …) are still open — deliberately out of this slice; file a
  follow-up task when starting one (next free number is T-083).

### Open questions / blockers
- None.

### Next action
- **M7 T-052** (warnings-as-errors, lint, final API review) — write the
  detail block when starting.

---

## Session 74 — 2026-09-23 — T-049 `jdx help --agent` done (first M7 slice)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `0ec768d` (claim) + closing commit (this session)

### Goal
Implement one M7 task and push (explicit owner go-ahead this session).
Picked T-049 (`jdx help --agent`): smallest unblocked M7 slice, unblocks
the T-051 README sweep. Expanded the coarse M7 one-liners into detail
blocks per the board rule (M7 order by value-per-sitting: T-049 first).

### What I did
- Claimed T-049 first (`docs/TASKS.md` TODO→WIP + M7 expansion, committed
  `0ec768d` before coding).
- New `cli/.../render/HelpSheet.kt`: one `HELP_ROWS` table (26 commands,
  one-liner + example each) feeding both renderers so the human sheet and
  the paste-ready agent block cannot drift; `HelpResult` + standard
  `--json` envelope. Pure strings — deterministic, no timestamps/paths.
- New `cli/.../commands/HelpCommand.kt` (`jdx help [--agent] [--json]`,
  thin adapter per D-004) + registration in `JdxCli`.
- New `HelpCommandTest` (tier-1, 7 tests green): human/agent content pins,
  `--json` envelope pin, Clikt end-to-end in all three flavours, plus a
  200-case determinism property (the generating family).
- Spec/table sweep: `docs/PROPOSAL.md` Appendix B gains the `help` row;
  `README.md` command surface gains the `help [--agent]` row.
- Verified: `:cli` suite 7/7 green; `./gradlew check` tiers 1–2 green
  (only red is the known pre-existing `verifyTier1Budget`
  machine-variance gate — open-items caveat, not a test failure). Live:
  `app/build/jdx help`, `help --agent`, `help --json` all exit 0 with
  the pinned content.

### Decisions made
- None (no new D-nnn; single-table-feeds-both-flavours follows from the
  "generated from the same metadata" line in PROPOSAL.md Appendix B).

### Tasks moved
- T-049: TODO → WIP (`0ec768d`) → DONE (this session). Next: T-047.

### Lessons distilled
- None (no new L-nnn; the `verifyTier1Budget` red-on-this-machine trap is
  already a recorded caveat).

### What works now (and how to verify it yourself)
- `bash app/build/jdx help --agent` — compact paste-ready block, exit 0.
- `bash app/build/jdx help --json` — same 26 rows in the envelope.
- `./gradlew :cli:test --tests "dev.jdx.cli.commands.HelpCommandTest" -Ptier1.budget=10000`
  — 7 tests green.

### What is broken / half-done
- Nothing from this task.

### Open questions / blockers
- None.

### Next action
- **M7 T-047** (token budgets) — next detail block to write. T-048/T-050/
  T-051/T-052 + T-080/T-081 remain as filed.

---

## Session 73 — 2026-09-23 — T-046 adapter parity done (M6 closed)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `9a13803` (claim) + closing commit (this session)

### Goal
Implement M6 T-046 (adapter parity test: same query via CLI `--json`,
HTTP and MCP returns byte-identical payloads — closes M6). Then push
(explicit owner go-ahead this session).

### What I did
- Claimed T-046 first (`docs/TASKS.md` TODO→WIP, committed `9a13803` before coding).
- New `cli/src/test/kotlin/dev/jdx/cli/parity/AdapterParityTest.kt`
  (`@Tag("tier2")`, 5 tests, all green): lives in `:cli` because it is the
  only module that already depends on all three adapters (`:index` dispatch
  as the CLI-`--json` reference, `:mcp` session, `:server` HTTP) — no new
  module or dependency, D-004 intact.
  - All 17 read `RpcCommand`s in one table, each built with the CLI's own
    `DaemonClient` flag→param builders (so the table also pins the
    flag spelling), asserting `JdxService.dispatch(...).toJson(wire)` ==
    MCP `callTool("jdx_<wire>", …)` text == HTTP `GET /v1/<wire>?…` body,
    one line each, over the fixture-jar workspace `fx`.
  - A JDK sample (`show java.util.HashMap` from a jars-empty
    `includeJdk=true` workspace `jdk`, via the per-call workspace
    override on MCP/HTTP) and two failure branches (exit-1 unknown
    symbol, exit-3 `kind=bogus` usage error, with `isError` pinned).
  - Two real one-shot `--json` spot checks (`ShowCommand`,
    `MembersCommand` with `--kind method --limit 5` through Clikt with
    captured stdout, hermetic store/env/discovery) anchoring the
    `dispatch`-as-CLI reference the structural proof leans on (D-056 §1).
  - A 100-case kotest `checkAll` property over hostile (command, query,
    params) asserting the same three-way parity — the generating family
    the standing bar demands (logged as L-104 for the routing-key filter
    it needed).
- Verified: new suite 5/5 green (`:cli:tier2Test --tests
  "dev.jdx.cli.parity.AdapterParityTest"`); full `./gradlew check` —
  1988 tests, 0 failures, 0 errors. Only red is the known pre-existing
  `verifyTier1Budget` machine-variance gate (open-items caveat, not a
  test failure). Tier 3 not run: test-only change, no artifact/index/
  render code touched (§13 trigger does not fire).

### Decisions made
- None (no new D-nnn; single-suite-in-`:cli` placement follows from the
  dependency rule and is recorded in the test KDoc, not the log).

### Tasks moved
- T-046: TODO → WIP (`9a13803`) → DONE (this session). **M6 CLOSED.**

### Lessons distilled
- L-104 (parity-property generators must filter MCP/HTTP routing keys —
  `query`/`workspace` select subject/roots there but are inert wire
  params to the dispatch).

### What works now (and how to verify it yourself)
- `./gradlew :cli:tier2Test --tests "dev.jdx.cli.parity.AdapterParityTest"`
  — 5 tests green (17-command table, JDK sample, failures, CLI spot
  checks, 100-case hostile property).
- `./gradlew check -x verifyTier1Budget` — tiers 1–2 green (1988 tests,
  0 failures; the `-x` skips the known-red machine-variance gate).

### What is broken / half-done
- Nothing from this task.

### Open questions / blockers
- None.

### Next action
- **M7 T-047…T-052** (coarse one-liners: token budgets, AppCDS,
  `help --agent`, `bench`, README/install, lint/gates — expand into
  detail blocks when starting, per the board rule). T-080/T-081 stay low
  priority.

---

## Session 72 — 2026-09-23 — T-045 `jdx batch` done (NDJSON stdin, max-exit stream)

**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `c85da31` (claim) + closing commit (this session)

### Goal
Implement M6 T-045 (`jdx batch`): NDJSON `RpcRequest` lines on stdin, one
`--json` envelope per line on stdout, exit code = max query exit code. Then
push (explicit owner go-ahead this session).

### What I did
- New `cli/.../commands/BatchCommand.kt`: reads all stdin lines, skips blanks
  (HTTP-batch parity), empty batch → one exit-3 envelope; roots resolve once
  via `ReadCommandSupport.resolveRoots` (`--jars/--src/-w/--no-jdk/--coord/--repo/--fetch`);
  a root failure serialises as every line's envelope (strict 1:1 mapping,
  D-057 §3). Each line: `RpcRequest.decode` (malformed → exit-6 envelope,
  same wording as HTTP batch), `version`/`health` answered internally
  (daemon envelope shape + key order), `doctor` answered in-process via
  `DoctorService` (unlike daemon/HTTP/MCP, batch runs on the caller's
  machine, so the report is about the right environment), all 17 read queries
  via `JdxService.dispatch` + `toJson(wire)` verbatim. Whole run is total
  (per-line try/catch → exit 6); `--json` accepted as a no-op (output already
  is envelopes). Registered `BatchCommand()` in `JdxCli` (README + PROPOSAL
  already list `jdx batch` — no doc-table change needed).
- Tests: tier-1 `BatchCommandTest` (8: mixed-stream max-exit + dispatch
  capture, health shape/count, empty batch, blank skipping, roots-failure
  mapping, `--json` no-op, 200-case hostile never-throws/determinism
  property, doctor envelope) + tier-2 `BatchCommandsServiceTest` (mixed
  ok/exit-2/exit-1/malformed/version stream over the fixture jar:
  golden-pinned to `cli/src/test/resources/golden/batch/mixed.txt` after
  reading the diff, plus byte parity of the show/body lines vs one-shot
  `--json` stdout — T-046 in miniature).
- Fixed along the way (test-only): the tier-1 fake built exit-1 via
  `ErrorResult.generic` (which `require`s 3..6) — the total adapter turned the
  fake's own throw into an exit-6 line (logged as L-103); the tier-2 ambiguous
  line first used a member ref with `members` (exit 3, member refs are a usage
  error there) — switched to `body …#copy` (bytecode-first overload exit 2,
  D-009).
- Verified: cli tier-1 180 tests green, cli tier-2 125 tests green (golden in
  verify mode); live `./app/build/jdx batch` over the JDK (show + members +
  garbage + version → exit 6) and `cmp` parity of a batch show line vs
  `show --json` bytes (PARITY-OK).

### Decisions made
- None (no new D-nnn; the batch-doctor-in-process choice follows from where
  the code runs and is recorded in the command KDoc, not the log).

### Tasks moved
- T-045: TODO → WIP (`c85da31`) → DONE (this session).

### Lessons distilled
- L-103 (total adapters convert fake-construction throws into exit-6 lines —
  suspect the fake first).

### What works now (and how to verify it yourself)
- `echo '{"command":"show","query":"java.util.Map"}' | ./app/build/jdx batch`
  prints the exact `show --json` envelope; `jdx batch --help` lists the root
  flags. Mixed stream + exit codes: pipe show/members/garbage/version lines
  (see session log above) and check exit 6.
- `./gradlew :cli:test :cli:tier2Test --no-configuration-cache -x verifyTier1Budget`
  (the `-x` skips the known-red machine-variance gate, not a test failure).

### What is broken / half-done
- Nothing from this task.

### Open questions / blockers
- None.

### Next action
- **M6 T-046** (adapter parity test — CLI/HTTP/MCP byte-identical payloads;
  closes M6). Batch parity is already proven per-line by the tier-2 test.

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
