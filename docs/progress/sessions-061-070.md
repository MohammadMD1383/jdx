# Session log — shard 7: sessions 061–070

Append-only (D-023): 10 sessions per shard, newest first. Template and rules in
`docs/PROGRESS.md`.

---

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
