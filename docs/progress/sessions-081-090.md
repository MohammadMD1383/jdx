# Session log — shard 9: sessions 081–090

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

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
