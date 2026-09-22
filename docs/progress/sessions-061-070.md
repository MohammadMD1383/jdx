# Session log — shard 7: sessions 061–070

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

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
