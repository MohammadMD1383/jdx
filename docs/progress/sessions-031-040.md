# Sessions 031–040

Newest first. Each entry follows the template at the bottom of `docs/PROGRESS.md`.

---

## Session 36 — 2026-09-19 — T-068 dedupe identical resolved roots (WIP completion)
**Agent/Author:** Buffy (Codebuff/GLM) · **Commits:** `6053a38` (prior claim) + closing commit (this session)

### Goal
Finish the session-24/35 WIP: T-068 was already claimed and the `openRoots`
dedupe + a first `DuplicateRootsTest` draft existed uncommitted. Close the
remaining acceptance gaps — the `--jars` + workspace case, the D-020 generative
family — and verify, document, commit.

### What I did
- Reviewed the uncommitted tree: the fix (path-keyed dedupe in `openRoots`)
  and 5 of the 7 example tests were in place; the acceptance bullet "one jar
  passed twice (directly **and via a workspace") and the standing generative
  bar were unmet.
- `DuplicateRootsTest`: added the `--jars` + stored-workspace test driven end
  to end through `WorkspaceResolver` → `RootsSpec.fromResolved` → `openRoots`
  (the exact merge shape the bug was found under), plus a `members` variant
  asserting no `DUPLICATE_FQN` for one file. Both workspace globs are anchored
  absolutely (L-061).
- New `DuplicateRootsPropertyTest` (tier 2, TESTING.md §4 family): 500-case
  properties — (a) every generated redundant path form (`A/./f.jar`,
  `A/../A/f.jar`, and all generated combinations, in one root list together)
  yields byte-identical search output to the single-root answer with 1 hit;
  (b) N copies of one jar in N generated distinct directories stay N providers
  (shading visibility). Two property iterations were needed: chaining `.`/`..`
  decorations from an already-decorated path lands on absent paths — each form
  must derive from the base directory (L-061).
- Verified: `:index:test` + `:index:tier2Test` green (9 new tier-2 tests);
  full `soak` green (2m 8s); `check` tier 1 green (672 tests) with
  `:cli:tier2Test` failing **only** in the four files T-070 names
  (`ReadCommandsServiceTest`, both golden suites, `ReadCommandDiscoveryTest` —
  all `exit 5 / no artifacts match` from the ambient `fx` glob, the exact
  pre-existing set session 35 proved stashed-clean). Docs: TASKS.md T-068 →
  DONE with implementation notes, PROGRESS.md CURRENT STATE, L-061,
  this entry.

### Decisions made
- Dedupe stays in `JdxService.openRoots` (not the resolver): specs become
  concrete paths only at `expandJarSpec`, and `WorkspaceResolver` is a pure,
  IO-free layer — moving dedupe there would re-introduce IO. First occurrence
  wins so explicit-first shadowing order is preserved by construction; the key
  is the normalised absolute *path*, deliberately not the name, so shaded
  same-named jars keep per-provider rows (D-031). Recorded in TASKS.md notes.

### Tasks moved
- T-068: WIP → DONE.

### Lessons distilled
- **L-061** — redundant `.`/`..` path decorations only compose from the base
  path (chained ones land on absent paths, exit 5 masquerading as a dedupe
  bug); workspace globs stored relative resolve against the process CWD, so
  fabricated workspaces must anchor them absolutely.

### What works now (and how to verify it yourself)
```bash
cat ~/.config/jdx/active-workspace  # fx stays in place — nothing here needs shelving
./gradlew :index:test :index:tier2Test   # green incl. the 9 new DuplicateRoots* tests
./gradlew soak                            # green (2m 8s)
./gradlew check                           # tier 1 green; :cli:tier2Test red only on T-070's four files
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: T-070 (the `check` blocker —
  tier-2 read-command tests still pick up the ambient workspace),
  T-069 (`--repo` flag); `verifyTier1Budget` machine variance (session 34)
  unchanged.

### Open questions / blockers
- None.

### Next action
- **T-069** (`--repo` flag) — lowest open TODO with deps DONE; T-070
  (tier-2 read-command hermeticity) also open and blocks a clean `check`.

---

## Session 35 — 2026-09-19 — T-066 make `ReadCommandsTest` hermetic (test-only)
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `68b49d4` (claim) + `2a496b3` (closing)

### Goal
Implement T-066, the lowest open TODO: stop the ambient `~/.config/jdx`
active-workspace (e.g. `fx`) from re-rooting the tier-1 `ReadCommandsTest`
assertions. Test-only task — no production behaviour change.

### What I did
- Reproduced first: `./gradlew :cli:test --tests "...ReadCommandsTest"` with the
  ambient `fx` workspace present → 2 red (`show prints the card text...`,
  `show forwards jars...`), both `RootsSpec` mismatches carrying the `fx`
  workspace's `testfixtures-*.jar` glob and `includeJdk=false`.
- `cli/.../commands/ReadCommandsTest.kt`: added `noEnv` (`{ null }`) and
  `emptyStore()` (`InMemoryWorkspaceStore()`) helpers and injected both into
  every `ShowCommand`/`MembersCommand`/`OutlineCommand` construction that used
  the real-home defaults (19 sites: 14 multi-line + 5 single-line; the 7
  `workspaceStore()` tests already injected). `discover = noDiscovery` was
  already everywhere, so roots are now fully hermetic (store + env + discovery).
- Verified: `:cli:test` green with ambient `fx` present (29/29), and again
  with `JDX_WORKSPACE=fx` forced in the environment (29/29, `--rerun-tasks`).
- Standing-bar check `./gradlew check` with ambient `fx`: tier 1 green
  (672 tests, 27.2 s of 30 s) but `:cli:tier2Test` red — `ReadCommandsServiceTest`
  15/18, both golden suites, `ReadCommandDiscoveryTest` end-to-end, all `exit 5 /
  no artifacts match: testfixtures/...` from the same `fx`-glob trap. Control:
  stashed the T-066 fix and re-ran tier 2 → identical 15 failures, so
  pre-existing and unrelated to this change. Filed as **T-070** (tier-2 half of
  the same trap) instead of scope-creeping this task.

### Decisions made
- None.

### Tasks moved
- T-066: TODO → WIP (`68b49d4`) → DONE (`2a496b3`).
- T-070 (new): tier-2 read-command tests hermeticity — TODO.

### Lessons distilled
- None new (the trap is L-038; the fix follows the `SearchCommandsServiceTest`
  precedent that already cites T-066).

### What works now (and how to verify it yourself)
```bash
cat ~/.config/jdx/active-workspace  # fx — ambient workspace stays in place, no shelving
./gradlew :cli:test --tests "dev.jdx.cli.commands.ReadCommandsTest"  # 29/29 green
JDX_WORKSPACE=fx ./gradlew :cli:test --tests "dev.jdx.cli.commands.ReadCommandsTest" --rerun-tasks -x verifyTier1Budget  # still 29/29
./gradlew check  # tier 1 green; :cli:tier2Test still needs ~/.config/jdx shelved (T-070)
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: T-070 (tier-2 sibling of
  this fix), T-068 (identical-root dedupe), T-069 (`--repo` flag);
  `verifyTier1Budget` variance noted in session 34 unchanged.

### Open questions / blockers
- None.

### Next action
- **T-068** (dedupe identical resolved roots) — lowest open TODO after T-066;
  T-069 (`--repo`) and T-070 (tier-2 hermeticity) also open.

---

## Session 34 — 2026-09-19 — T-065 JFR-style `$$` class names (dedicated warning)
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** closing commit (this session; claim `6cffef3` predates it)

### Goal
Complete the T-065 WIP found in the working tree: decide empty-segments-accept vs
dedicated-warning, satisfy both acceptance lines, and keep tiers 1+2 green.

### What I did
- Kept the tree's decision (dedicated `WarningCode.UNNAMEABLE_CLASS`, model invariant
  untouched) and finished it: removed a dead second matcher disjunct in
  `AsmClassReader.isEmptySegmentFailure` (`"empty nesting segment after"` matches no
  producer string — only `TypeName.ClassType`'s `"empty name segment"` exists; L-060),
  verified `ArtifactIndexer` needs no logic change (generic warning pass-through),
  verified the `$$`-skip widening in `ServiceDifferential` is exact (any `$$` implies
  an empty segment under `$`-splitting).
- Verified: targeted suites green; `test`+`tier2Test` green (672 + 268 tests,
  0 failures, `~/.config/jdx` shelved per T-066 caveat).
- `check` is red only on `verifyTier1Budget` (45.0 s vs 30 s) — re-ran stashed-clean
  as the control: **31.4 s, also red**, with unrelated suites slowest
  (`DoctorEnvironmentTest`, `JdkLayoutTest`, `InstallScriptTest`). Pre-existing machine
  variance, not a T-065 regression; recorded here so the next contributor does not
  chase it.

### Decisions made
- None new (the WIP's accept-vs-reject fork was already resolved to reject-with-code;
  confirmed it is the right call — see implementation notes in T-065).

### Tasks moved
- T-065: WIP → DONE.

### Lessons distilled
- L-060 (matchers on exception message text must mirror the producer string exactly).

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx ~/.config/jdx.shelved  # T-066 ambient-workspace caveat, restore after
./gradlew test tier2Test -x verifyTier1Budget  # green: 672 + 268 tests, 0 failures
./gradlew :index:test --tests "dev.jdx.index.asm.AsmClassReaderTest"  # incl. 2 new T-065 tests
./gradlew :index:tier2Test --tests "dev.jdx.index.index.ArtifactIndexerTest"  # incl. UNNAMEABLE_CLASS test
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: T-066 (`ReadCommandsTest`
  hermeticity), `verifyTier1Budget` red stashed-clean on this machine (see above).

### Open questions / blockers
- None.

### Next action
- **T-066** (make `ReadCommandsTest` hermetic to the machine's workspaces) — lowest
  open TODO; T-068 (identical-root dedupe), T-069 (`--repo` flag) also open.

---

## Session 33 — 2026-09-19 — T-064 close the indexer 3,000/s gap (no code change)
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** claim + closing commit (this session)

### Goal
Implement T-064, the lowest-numbered unblocked TODO: reach ≥ 3,000 classes/s
end-to-end on the benchmark jar, or document why the target moved — with no
behaviour change (T-013/T-014 tests green unmodified).

### What I did
- Claimed T-064 (`TODO`→`WIP` commit) and re-measured instead of optimising.
- First attempt: a throwaway `@Tag("bench")` Gradle test indexing
  `minecraft-client.jar` — indexed **0 classes** with 10,952
  `UNSUPPORTED_CLASS_VERSION` warnings. Cause: Gradle tests run on toolchain
  JDK 21, the jar is major 69 (needs JDK 25+), and `AsmClassReader` rejects
  newer-than-runtime majors by design (L-059). Deleted the harness after use —
  permanent benchmarks belong to T-050.
- Real measurement: throwaway `T064Bench.java` run as plain
  `java -cp index+core+asm(+tree/util)+sqlite-jdbc` under `/usr/lib/jvm/default`
  (JDK 26.0.2.1), fresh temp store per run:
  - `minecraft-client.jar` (10,952 classes): **4,004/s** first run (pays SQLite
    native-load + JIT), **5,663–5,798/s** second run, 0 warnings.
  - Full `jrt:/` (27,546 classes): **5,882–5,919/s**, 0 warnings.
  - Gradle `ArtifactIndexerSoakTest` (toolchain JDK 21, 27,777 classes):
    **4,575/s**, green.
- Conclusion: the session-19 figure (2,502/s) is stale — predates the current
  JDK and the `BulkWriter` statement-reuse. Target met on every benchmark jar
  with headroom; per the task's own warning, the risky client-side id
  assignment / JDBC-batching redesign was not attempted.
- Docs-only change set: T-064 → DONE with notes, status-summary line,
  `ArtifactIndexerSoakTest` KDoc re-measurement note, L-059, this entry,
  CURRENT STATE handoff.

### Decisions made
- None new (no genuine ambiguity; the "optimise vs document" fork resolved to
  *document* once every measurement cleared the target).

### Tasks moved
- T-064: TODO → WIP (claim commit) → DONE.

### Lessons distilled
- L-059 (indexer benchmarks must run under the runtime JDK, not the toolchain JDK).

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx ~/.config/jdx.shelved  # T-066 ambient-workspace caveat, restore after
./gradlew check   # tiers 1+2 green (tier-1 18.9 s of 30 s, 670 tests)
./gradlew :index:soakTest --tests "dev.jdx.index.index.ArtifactIndexerSoakTest"  # green; SOAK line in XML ~4,500/s+
mv ~/.config/jdx.shelved ~/.config/jdx
grep -rh "SOAK indexed" index/build/test-results/soakTest/
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: T-066 (cli store tests red with
  ambient `~/.config/jdx` — the run above shelved it), T-065 (soak `$$` reds in
  `JavapCorpusSoakTest`).

### Open questions / blockers
- None.

### Next action
- **T-065** (handle JFR-style `$$` class names) — lowest-numbered open TODO;
  T-066 (hermetic `ReadCommandsTest`), T-068 (identical-root dedupe), T-069
  (`--repo`) also open.

---

## Session 32 — 2026-09-19 — T-063 share the fixture-jar resolution helpers
**Agent/Author:** Buffy (GLM, Freebuff) · **Commits:** claim + closing commit (this session)

### Goal
Implement T-063, the lowest-numbered unblocked TODO: promote the triplicated
`fixtureBinaryJar()`/`fixtureClassNames()` copies into one shared helper beside the
T-054 `GoldenFiles` helper, migrate the golden suites, and settle core's `Fixtures`.

### What I did
- New `dev.jdx.testsupport.fixtures.FixtureJars` in the `testfixtures` `testFixtures`
  source set (index and cli already consume that jar via
  `testImplementation(testFixtures(project(":testfixtures")))` — zero build changes).
  Carries the canonical resolution: `jdx.fixturesDir` property → upward directory
  search, the exactly-one jar-count guard (L-029 trap documented at the guard),
  `classNames`, and `classBytes` (zip read, never a class load — D-017 rule restated).
- New `FixtureJarsTest` (5 tier-2 tests, ported from core's `FixturesTest`, including
  the duplicated-binary-jar ambiguity guard).
- Migrated **four** copies, not the three the task text knew: index
  `RendererGoldenTest` (jar + names + bytes), cli `ReadCommandsGoldenTest` (jar +
  names), and cli `SortOrdersGoldenTest` (jar — a session-31 file the task predates).
  The module-attributing `jdx.fixturesDir not set (… build wires it)` fail message
  became the generic `build :testfixtures first` error.
- core's `Fixtures` stays (not delegating) — KDoc now says why (dependency-light test
  source set per T-055; D-017 marker helpers are the T-006 corpus contract) and pins
  the mirror rule: change one, change both, `FixturesTest` pins the same behaviour.
- Inspected and deliberately left: index-internal `ArtifactTestJars` — different
  mechanism (resolution shaped for `ArtifactLoader` seams) plus `craftJar`/manifest
  helpers; folding it into the shared helper would couple artifact tests to the
  testFixtures jar for no dedupe gain.

### Decisions made
- None new (no genuine ambiguity; the "delegate or document" fork in the task text
  resolved to *document* — recorded in `Fixtures`' KDoc and the task notes).

### Tasks moved
- T-063: TODO → WIP (claim commit) → DONE.

### Lessons distilled
- L-058 (promoted helpers must document which sibling copies must mirror them).

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx ~/.config/jdx.shelved  # T-066 ambient-workspace caveat, restore after
./gradlew check   # tiers 1+2 green (tier-1 18.9 s of 30 s, 670 tests)
git grep -n "private fun fixtureBinaryJar\|private fun fixtureClassNames"   # only index's ArtifactLoader-shaped copies remain
git grep -n "FixtureJars" index/src cli/src testfixtures/src
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: T-066 (cli store tests red with
  ambient `~/.config/jdx` — the run above shelved it, same as sessions 24–31),
  T-065 (soak `$$` reds).

### Open questions / blockers
- None.

### Next action
- **T-064** (close the indexer 3,000/s gap) — lowest-numbered open TODO; T-065
  (`$$` names), T-066 (hermetic `ReadCommandsTest`), T-068 (identical-root dedupe),
  T-069 (`--repo`) also open.

---

## Session 31 — 2026-09-19 — T-062 member sort orders (`--sort name|declaring`)
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `b7d0271` (claim) + closing commit (this session)

### Goal
Implement T-062, the lowest-numbered unblocked TODO: `--sort name|kind|declaring`
for `members` and `outline`, with exact layouts decided, text↔JSON parity,
per-order determinism, and goldens over a generic, a nested and a Kotlin fixture.

### What I did
- `core/.../render/Listing.kt`: new `MemberSort` enum (`KIND`/`NAME`/`DECLARING`
  + `fromFlag`), `MemberListingOptions.sort` (default `KIND`), `MemberRow.memberName`
  (raw name, JSON-untouched). `buildMemberListing` branches into `kindGroups`
  (T-010 layout, untouched), `declaringGroups` (alphabetical declaring, kind-minor)
  and `nameGroups` (strict global flat order via run-length groups — first draft
  grouped by `(declaring, kind)` which re-sorted methods-before-fields; a failing
  example test caught it, fixed to run-length runs so flattening is globally sorted).
- `index/.../service/JdxService.kt`: `MemberFilters.sort` (default `KIND`; keeps the
  thin-adapter `MemberQuery` arity stable, D-004), mapped into `MemberListingOptions`.
- `cli`: `ReadCommandSupport.sortOf` + validation now accepts all three orders
  (bogus values are exit 3); both commands pass the sort through filters and gained
  accurate `--help` text.
- Tests: 6 core examples (`MemberSortTest`); 2 consolidated thousand-case properties
  (shared laws per random sort; layout laws over all three sorts — first draft had 6
  properties and blew the 30 s tier-1 budget at 37.5 s, consolidated to 2 without
  dropping the 1,000-case minimum, back to 23.6 s); CLI tier-1 sort-plumbing tests
  (replacing the `--sort name exits 3` test); tier-2 `SortOrdersGoldenTest` (18 files:
  Generics, TrafficLight$1, KotlinMembers × 3 sorts × text/JSON; `kind` files are
  byte-identical to the existing default goldens) + tier-2 service tests (every sort
  exit 0, deterministic, same row set; outline covered).
- Golden fixture swap: first draft used `Nesting$Inner` (2 rows, all orders identical);
  replaced with `TrafficLight$1`, which differentiates all three orders (L-057).

### Decisions made
- D-033 (member `--sort` order semantics) in `docs/decisions/D-026-050.md` + index row.

### Tasks moved
- T-062: TODO → WIP (`b7d0271`) → DONE.

### Lessons distilled
- L-057 (golden fixtures must visibly differentiate the new behaviour).

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx ~/.config/jdx.shelved  # T-066 ambient-workspace caveat, restore after
./gradlew check   # tiers 1+2 green (tier-1 23.6 s of 30 s)
./gradlew soak    # green except pre-existing JavapCorpusSoakTest `$$` red (T-065)
./gradlew :cli:tier2Test --tests "dev.jdx.cli.commands.SortOrdersGoldenTest"  # 18 goldens
git diff cli/src/test/resources/golden/members-sort/  # TrafficLight$1 differs 3 ways; kind == members/ goldens
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: T-066 (`ReadCommandsTest`
  hermeticity — 2 reds with ambient `~/.config/jdx`, proven stashed-clean),
  T-065 (`$$` names — soak red quoted below, same signature as sessions 26–28).

### Open questions / blockers
- None.

### Next action
- **T-063** (share the fixture-jar resolution helpers) — lowest-numbered unblocked
  TODO after T-062; T-064, T-065, T-066, T-068, T-069 also open.
