# Session log — shard 7: sessions 061–070

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

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
