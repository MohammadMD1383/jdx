# Session log — shard 6: sessions 051–060

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

## Session 51 — 2026-09-21 — `jdx usages` (T-030)
**Agent/Author:** Muse Spark 1.3 (opencode) · **Commits:** `f992721` (claim) + implementation (this session)

### Goal
Implement T-030, the lowest-numbered unblocked TODO: `jdx usages` — the first
M4 CLI slice over the T-029 reference-edge model.

### What I did
- Claimed T-030 (`TODO`→`WIP` + expand the one-liner into a detail block) and
  committed the claim before coding.
- `core/.../render/Usages.kt` (test-first): `UsageHit` (from-ref, artifact,
  kind word, **per-edge** target), `UsageListing` (artifact group headers,
  `showTargets` for type queries), `buildUsageListing` (shared truncation).
- `index/.../service/JdxService.kt`: `UsageKindFilter` (all ten proposal
  kinds parsed; five deferred exit 3), `UsageOptions`
  (`--kind/--in/--exclude/--limit/--context`), `usages()` +
  `executeUsages()` (T-011 resolution incl. `g:a:v` scope + `DUPLICATE_FQN`,
  then a live `ReferenceExtractor` scan per class; unreadable classes
  warn-and-skip; `--in`/`--exclude` per artifact label, per module under
  `jrt:/`; zero hits exit 1 with a `no usages` detail; `--context` exits 3
  naming T-034) + `ServiceOutcome.UsageList` (+ the two `CorpusSoakTest`
  branches) + `edgeTargetRef`/`canonicalFromRef` renderers.
- `cli/.../commands/UsagesCommand.kt` (thin, registered in `JdxCli`) +
  `UsagesQuery` seam + `usageKindOf` in `ReadCommandSupport`.
- Tests: core `UsagesTest` (5) + `UsagesPropertyTest` (3 thousand-case);
  index `UsagesCaseJars` (ASM `u.*` corpus + `v.Lib` ambiguity namesake;
  test DSL gains `fieldInsn`/`typeInsn`) + `UsagesServiceTest` (18 tier-2
  incl. a JDK truncation smoke) + `UsagesGoldenTest` (8 files); cli
  `UsagesCommandTest` (5 tier-1) + `UsagesCommandsServiceTest` (7 tier-2
  incl. D-017). Two CLI failures on first run were wrong expectations
  (values() is read, never called; StaticInitMarker *is* used) — fixed in
  the tests, implementation untouched.
- Mid-task redesign (L-083): the first cut rendered `kind fromRef` only and
  the fixture-enum probe printed three byte-identical `read $values()` rows
  (RED/YELLOW/GREEN reads). Type-query rows now suffix `-> #member(params)`;
  JSON rows carry the full per-edge target.
- Docs: D-043, L-083, T-030 DONE, CURRENT STATE (next: T-031).

### Decisions made
D-043 (`jdx usages` output semantics: live-roots-first, five kinds live,
row layout with edge targets, zero-usages exit 1, warn-and-skip scan).

### Tasks moved
T-030: TODO → WIP → DONE.

### Lessons distilled
L-083 (per-edge targets turn "duplicate" usages rows into information).

### What works now (and how to verify it yourself)
```bash
./gradlew :app:installDist -x verifyTier1Budget
J=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
app/build/jdx usages 'dev.jdx.fixtures.TrafficLight' --jars $J --no-jdk
app/build/jdx usages 'dev.jdx.fixtures.CovariantOverrides$Child#copy()' --jars $J --no-jdk
app/build/jdx usages 'dev.jdx.fixtures.Generics' --jars $J --no-jdk; echo "exit=$?"  # exit 1, no usages
./gradlew check -x verifyTier1Budget   # tiers 1+2 + JaCoCo gates, green
```

### What is broken / half-done
- `verifyTier1Budget` stays red on this machine (pre-existing variance, 0 test
  failures; new suites cost ~2 s). Nothing from this task.
- Indexed acceleration deferred by design (D-043 §1): `usages` scans live
  roots; a JDK-scale query costs seconds on first touch.

### Open questions / blockers
None.

### Next action
M4 T-031 (project source-dir usages — expand into a detail block when
started; reads source dirs alongside bytecode roots per D-010).
