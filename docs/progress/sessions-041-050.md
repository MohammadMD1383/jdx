# Sessions 041–050

Newest first. Each entry follows the template at the bottom of `docs/PROGRESS.md`.

---

## Session 46 — 2026-09-20 — T-027 `javap` engine done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `e4c1466` (claim) + closing commit (this session)

### Goal
Implement T-027, the second and last decompiler slice of M3: `--engine javap`
for `body`/`source` (PROPOSAL.md §11.2's *Show Bytecode* action), wired through
the T-026 `DecompilerEngine` seam with `DECOMPILED_JAVAP` provenance.

### What I did
- Claimed T-027 first: expanded the M3 one-liner into a detail block
  (`docs/TASKS.md`) and committed the `WIP` (`e4c1466`) before coding.
- Probed real `javap -c -p -s` output first: members are blank-separated with
  a `descriptor:` line each, but there is **no** blank line between the class
  declaration and the first member (L-077); `javap -version` prints to stdout
  on JDK 26, stderr on older JDKs (merged stream).
- `decompile/...` (new): `DecompilerId.JAVAP` + `JavapDecompiler` (staged
  class bytes + `javap -c -p -s -classpath <stagedDir> <binary>`, 30 s budget,
  binary resolved explicit → `$JAVA_HOME/bin` → `java.home/bin` → `PATH` via
  the injectable `JavapEnvironment`, disk cache
  `<sha256>-javap-<javap -version>.java` with the version probed once per
  instance) + `JavapOutput.kt` (pure `splitJavapSections`/`findJavapSection`/
  `javapMemberName`: header-filter-then-blank-split, erased-descriptor match)
  + shared `Staging.kt` (`stagedEntryPath`/`stageClassFile`/`deleteStagedDir`,
  extracted from `VineflowerDecompiler` byte-identically;
  `VineflowerStagingTest` now pins the shared function).
- `index` (`JdxService`): `BodyOptions`/`SourceOptions` gain `javapDecompiler`
  (default production; data-class equality keeps the CLI `shouldBe` pins
  green, L-076) + `javapBodyOutcome`/`javapSourceOutcome`/`javapAroundOutcome`
  (forced engine skips sources; sections pair by erased descriptor, first
  declaration-order match wins for bridge pairs; whole/`--lines` via the
  shared `fileSourceOutcome`; `AroundMatch.Ready` carries the bytecode-first
  `matches` so `--around` never re-matches) + `decompileClassText` branches:
  Vineflower failures exit 1 naming the now-working `--engine javap` hatch
  (`, T-027` dropped everywhere), `javap` failures name only their cause.
  No auto-fallback (filed as T-073); `.kt`-only default path still names
  T-039; absent-from-disassembly names T-028.
- `cli`: `body`/`source` map `--engine javap` to `JAVAP` with updated help
  text; `validateBodyFlags`/`validateSourceFlags` accept both engines.
- Tests (no goldens: TESTING.md §5.3, plus JDK-version dependence): decompile
  tier-1 `JavapOutputTest` (5 examples + 2 thousand-case properties —
  synthesised member-find law, never-throws/slice/determinism; 0.3 s) +
  tier-2 `JavapDecompilerTest` (real engine over fixture bytes, a
  counting-wrapper cache proof, timeout/missing-binary/hostile faults,
  resolution order); index tier-2 (forced, skip-paired-sources,
  bytecode-first ambiguity, fake-failure, T-028, determinism, JSON — both
  commands); cli tier-1 (both-engines plumbing) + tier-2 (end-to-end
  disassembly). Updated the four pins that named T-027 as unimplemented.
- Verified: `test`+`tier2Test` green incl. JaCoCo gates; `soak` green solo
  (2m 35s); `verifyTier1Budget` red at 65.6 s — pre-existing machine variance
  (slowest: `JavaBodiesTest`, none from this task). Live proof via
  `app/build/jdx` (see below).

### Decisions made
- D-039 (`javap` engine semantics: forced-only + T-073 filed, descriptor
  match, header-filter splitting, `doctor`-mirroring resolution, versioned
  cache, shared windowing, no goldens) in `docs/decisions/D-026-050.md`.

### Tasks moved
- T-027: TODO → WIP (`e4c1466`) → DONE. Filed T-073 (Vineflower→javap
  auto-fallback, TESTING.md §7). T-028/T-072 stay TODO.

### Lessons distilled
- **L-077** — `javap` prints no blank line between the class header and the
  first member (filter header lines before blank-splitting); `javap -version`
  goes to stdout on JDK 26, stderr elsewhere (read merged).

### What works now (and how to verify it yourself)
```bash
./gradlew :decompile:test :decompile:tier2Test -x verifyTier1Budget  # engine + parser
./gradlew :index:tier2Test --tests "dev.jdx.index.service.BodyServiceTest" \
  --tests "dev.jdx.index.service.SourceServiceTest" -x verifyTier1Budget
./gradlew :cli:test :cli:tier2Test -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
./gradlew soak -x verifyTier1Budget   # green (2m 35s solo)
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
./app/build/jdx body 'dev.jdx.fixtures.Generics#identity(java.lang.Object)' --jars "$JAR" --no-jdk --engine javap
# exit 0: member section with `disassembled by javap from testfixtures-… ⚠ reconstructed`
./app/build/jdx source 'dev.jdx.fixtures.Generics' --jars "$JAR" --no-jdk --engine javap --lines 1:8
# exit 0: whole disassembly window with truncation footer
./app/build/jdx source 'dev.jdx.fixtures.Generics' --jars "$JAR" --no-jdk --engine javap --around 'dev.jdx.fixtures.Generics#identity(java.lang.Object)' --context 1 --line-numbers
# exit 0: member section expanded by context
./app/build/jdx body 'dev.jdx.fixtures.TrafficLight#seconds' --jars "$JAR" --no-jdk --engine javap
# exit 2: field/method ambiguity still decided from bytecode
```
Note: run the `jdx` proofs from the repo dir — from elsewhere the ambient
`fx` workspace (relative glob, T-066 trap) can exit 5 before the explicit
`--jars` root is read.

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: `verifyTier1Budget` red on
  this machine (machine variance, 0 test failures); `doc` has no decompiled
  path by design; soak runs no body/source queries so corpus disassembly is
  unexercised in tier 3. Deliberately not done here: Vineflower→javap
  auto-fallback (T-073).

### Open questions / blockers
- None.

### Next action
- **T-028** (`SOURCES_VERSION_MISMATCH` detection — the last M3 one-liner
  before M4; T-072 `--with-doc` and T-073 auto-fallback filed TODO).

---

## Session 45 — 2026-09-20 — T-026 Vineflower decompiler fallback done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `d3cbe04` (claim) + closing commit (this session)

### Goal
Implement T-026, the first decompiler slice of M3: a `DecompilerEngine`
interface in `decompile` with a Vineflower implementation (single-class,
workspace jars as library context, on-disk cache, wall-clock timeout, lazy
isolated loading), falling back to it from `JdxService.body`/`source` when no
paired sources exist. `--engine vineflower` forces the path; `--engine javap`
stays parked on T-027.

### What I did
- Probed the Vineflower 1.12.0 builder API first with a throwaway
  `javac`/`java` program (L-075): directory inputs report via
  `saveClassFile` (not `saveClassEntry`); single classes enter via a staged
  temp dir + `DirectoryContextSource` (`SingleFileContextSource` is
  package-private); result keys are slash-joined qualified names.
- `decompile/...` (new): `DecompilerEngine` (sealed
  `Decompiled`/`Failed`, never throws) + `VineflowerDecompiler` (cache
  `~/.cache/jdx/decompile/<sha256>-vineflower-<version>.java`, 30 s
  daemon-worker timeout with abandon-on-expiry, child-first isolated runner
  with in-process fallback, serialised calls, `VINEFLOWER_VERSION` pinned
  against the engine) + `VineflowerRunnerImpl` (staged temp dir, workspace
  jars as `libraries`) + `DecompileCache` (atomic writes, hostile-label
  sanitising). Cache and engine are data classes (L-076).
- `core/.../render/SourceLine.kt` (new): shared `renderSourceLine`
  (`decompiled by vineflower from … ⚠ reconstructed`, README contract),
  wired into `Body` and `Source` text (test-first: wrote the failing tests,
  watched them fail, then implemented).
- `index`: new `:decompile` edge + `ArtifactRoot.libraryPath` (jar/dir
  paths; `jrt:/` none) + `BodyOptions`/`SourceOptions` (`engine:`
  forced-or-auto, `decompiler:` injectable defaulting to production) +
  `decompileClassText`/`decompiledBodyOutcome`/`decompiledSourceOutcome`/
  `decompiledAroundOutcome`/`fileSourceOutcome` (shared windowing) +
  `matchAroundMember` (one bytecode-matching path for both origins) +
  `splitTextLines` (one numbering for both origins). Mid-task correction:
  first routed present-root-but-fileless to decompile, then reverted — the
  T-023 `stale sources` test pins that shape as T-028 mismatch, and rightly
  so (D-038 §1).
- `cli`: `--engine vineflower` live on `body` and `source` (help text
  updated); `javap` still exit 3 naming T-027.
- `docs/PROPOSAL.md` Appendix B: `source` row gains the missing
  `--line-numbers --max-lines --engine vineflower|javap` (pre-existing gap
  from T-023, fixed while verifying engine flags).
- Tests: decompile tier-1 (key/staging/version pins + 2 properties) + tier-2
  (real engine over fixture bytes incl. T-021 slice reuse, cache-hit,
  zero-timeout, hostile faults); index tier-2 (fallback/forced/fake-failure/
  scripted-mismatch/determinism/JSON — deliberately no goldens per TESTING
  §5.3); cli tier-1 (flag plumbing incl. options equality) + tier-2
  (end-to-end incl. the system-cache path). Rewrote the two `unpaired binary`
  tests from degrade-pins to fallback-pins; narrowed the JDK-honesty
  `shouldNotContain "Exception"` to `"Exception in thread"` (L-070: the
  decompiled `ArrayList` legitimately names exception types).
- Verified: `check -x verifyTier1Budget` green incl. JaCoCo gates; `soak`
  green solo (2m 38s; it runs no body/source queries, so decompile is
  unexercised there). Live proof via `app/build/jdx` (see below).

### Decisions made
- D-038 (Vineflower decompilation semantics: no-root fallback rule, `.kt`
  stays T-039, engine failure is exit 1 naming T-027, binary-naming
  provenance + reconstructed label, lazy child-first loading, cache layout,
  no decompiled goldens) in `docs/decisions/D-026-050.md`.

### Tasks moved
- T-026: TODO → WIP (`d3cbe04`) → DONE. T-027/T-028 stay M3 one-liners;
  T-072 stays TODO.

### Lessons distilled
- **L-075** — probe a new third-party API with a throwaway program before
  building on it (Vineflower's `saveClassFile` vs `saveClassEntry`,
  package-private single-file source).
- **L-076** — options/config holders compared in tests need value equality:
  default to `data class`.

### What works now (and how to verify it yourself)
```bash
./gradlew :decompile:test :decompile:tier2Test -x verifyTier1Budget  # engine + seam reuse
./gradlew :index:tier2Test --tests "dev.jdx.index.service.BodyServiceTest" \
  --tests "dev.jdx.index.service.SourceServiceTest" -x verifyTier1Budget
./gradlew :cli:test :cli:tier2Test -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
./gradlew soak --rerun-tasks          # green (2m 38s)
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
unzip -p "$JAR" dev/jdx/fixtures/Generics.class > /tmp/opencode/Generics.class
mkdir -p /tmp/opencode/barejar/dev/jdx/fixtures && cp /tmp/opencode/Generics.class /tmp/opencode/barejar/dev/jdx/fixtures/
cd /tmp/opencode/barejar && jar cf /tmp/opencode/bare.jar dev && cd ~/projects/java-source-cli
./app/build/jdx body 'dev.jdx.fixtures.Generics#identity(java.lang.Object)' --jars /tmp/opencode/bare.jar --no-jdk
# exit 0: decompiled slice with `decompiled by vineflower from bare.jar … ⚠ reconstructed`
./app/build/jdx source 'dev.jdx.fixtures.Generics' --jars /tmp/opencode/bare.jar --no-jdk --max-lines 8
# exit 0: decompiled file with truncation footer
./app/build/jdx body 'dev.jdx.fixtures.Generics#identity(java.lang.Object)' --jars /tmp/opencode/bare.jar --no-jdk --engine javap
# exit 3 naming T-027
```
Note: run the `jdx` proofs from the repo dir — from elsewhere the ambient
`fx` workspace (relative glob, T-066 trap) can exit 5 before the explicit
`--jars` root is read.

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: `verifyTier1Budget` red on
  this machine (machine variance, 0 test failures); `doc` has no decompiled
  path by design (decompiled text carries no javadoc); soak runs no
  body/source queries so corpus decompilation is unexercised in tier 3.

### Open questions / blockers
- None.

### Next action
- **T-027** (`javap` engine: `--engine javap` for `body`/`source` + the
  decompiler fault-injection cases deferred from T-057).

---

## Session 44 — 2026-09-20 — T-025 `jdx doc` incl. inherited javadoc done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `b56a6ed` (claim) + closing commit (this session)

### Goal
Implement T-025, the fourth M3 CLI slice: `jdx doc` — rendered javadoc for
types and members from paired Java sources, with inherited docs from the
nearest documenting supertype (PROPOSAL.md §7.1). No decompilation (T-026/
T-027), no Kotlin KDoc (T-039), no `SOURCES_VERSION_MISMATCH` formalism
(T-028). Split `--with-doc` enrichment out as T-072 before starting.

### What I did
- `sources/.../JavaDocs.kt` (new): `findTypeDoc`/`findMemberDocs` over the
  shared T-021 `loadJavaUnit` seam, reusing `matchByName`/`narrowBySignature`
  so `body` and `doc` agree on declarations. Raw `JavadocComment` content +
  the comment's own range. Blank comments count as undocumented
  (`MemberNotFound`; new `TypeUndocumented` distinct from `TypeNotFound`).
- `core/.../render/Doc.kt` (new): hand-rolled `renderJavadoc` (conservative
  known-tag HTML strip, inline-tag unwrap, `{@inheritDoc}`
  replacement-or-drop, block tags as a tidy block) + `DocBlock`/
  `buildDocBlock` (`--max-lines 200`, `inheritedFrom` label + JSON key).
- `index/.../service/JdxService.kt`: `doc()`/`DocOptions`/`ServiceOutcome.Doc`
  + `executeDoc`/`memberDocOutcome`/`typeDocOutcome`/`findInheritedMemberDoc`
  (BFS superclass-then-interfaces walk, each supertype read from its own
  providing root) + `supertypeChain`/`sourceDeclaresMember`/`docLines`/
  `docOutcome`/`docSubjectOf` helpers. Bytecode-authoritative (D-009):
  overload ambiguity from bytecode first (under-specified multi exits 2,
  like `body`), erased→generic-spelling retry, then sources.
- `cli/.../commands/DocCommand.kt` (new) + `DocQuery` seam + `JdxCli`
  registration: `--inherited` (default)/`--no-inherited` (exclusive, exit 3),
  `--raw`, `--max-lines`, all shared roots flags.
- Repointed the parked `--with-doc` exit-3 messages (`BodyCommand`,
  `ReadCommandSupport`, both `ReadCommands` help texts) at T-072 and updated
  the two pinning tests (`BodyCommandTest`, `ReadCommandsTest`).
- Appendix B `doc` row gains `--max-lines`. README needed no change (doc row
  already in the command table).
- Tests: core `JavadocRenderTest` (13) + `DocBlockTest` (8) + 4 thousand-case
  properties; sources `JavaDocsTest` (16 incl. never-throws + determinism);
  index `DocServiceTest` (42 tier-2: fixture pins + crafted `doc.*`
  4-level corpus in `DocCaseJars` + coord/duplicate/corrupt/truncated/
  renamed/stripped faults) + `DocGoldenTest` (8 files) + 2 new soak
  branches; cli `DocCommandTest` (5 tier-1) + `DocCommandsServiceTest`
  (7 tier-2, incl. D-017). New `MethodBuilder.invoke` test helper for a
  valid crafted `<init>`.
- Real findings while testing: single-line `/** x */` comments keep a
  leading space (renderer now trims it); `GrandChild` needed its own
  undocumented-then-inheritDoc `greet` to pin transitivity; the crafted
  `Base` gained a documented ctor and a `Traffic` enum for the
  CONSTRUCTOR/ENUM_ENTRY subjects.
- Caught a genuine coverage gap, not a flake: the fresh `doc` code left the
  index line gate at 0.84 vs the 0.85 minimum (bundle was borderline). Closed
  it honestly with the fault-path tests above (DUP/corrupt/coord/ambiguous/
  mismatch/ParseError/inheritDoc arms) — no gate moved, no test weakened.
- Verified: `./gradlew check -x verifyTier1Budget` green incl. all JaCoCo
  gates (1,271 tests, 0 failures); `./gradlew soak` green solo (4m 12s).
  `check` red only on `verifyTier1Budget` (pre-existing machine variance).
  Live proof via `app/build/jdx` (see below).

### Decisions made
- D-037 (`jdx doc` semantics: exit-2 overloads, methods-only inheritance,
  D-009-strict undeclared members, `{@inheritDoc}` first-paragraph
  substitution, conservative HTML strip, comment-range provenance,
  `TypeNotFound` vs `TypeUndocumented`, flags, T-072 split) in
  `docs/decisions/D-026-050.md`.

### Tasks moved
- T-025: TODO → WIP (`b56a6ed`) → DONE. T-072 filed (TODO):
  `--with-doc` for `body`/`members`/`outline` over the T-025 seam.

### Lessons distilled
- **L-072** — Kotlin nests block comments: no literal `/**` inside KDoc
  (bit twice — once in `sources`, once more in `core` before I internalised it).
- **L-073** — `when` over a sealed result must list every branch even as a
  statement (NO_ELSE_IN_WHEN), unreachable ones included with a comment.
- **L-074** — split unknown vs undocumented in the seam instead of
  re-deriving it outside.

### What works now (and how to verify it yourself)
```bash
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
./app/build/jdx doc 'dev.jdx.fixtures.Generics' --jars "$JAR" --no-jdk
# exit 0: rendered class doc with `-sources.jar` provenance
./app/build/jdx doc 'dev.jdx.fixtures.Generics#identity(U)' --jars "$JAR" --no-jdk
# exit 1: no javadoc comment ... nor any documenting supertype
./app/build/jdx doc 'dev.jdx.fixtures.CovariantOverrides$Child#copy' --jars "$JAR" --no-jdk
# exit 2 with two :return-suffixed candidates
./gradlew :core:test --tests "dev.jdx.core.render.JavadocRenderTest" \
  --tests "dev.jdx.core.render.DocBlockTest" \
  --tests "dev.jdx.core.render.DocBlockPropertyTest" \
  :sources:test --tests "dev.jdx.sources.JavaDocsTest" \
  :index:tier2Test --tests "dev.jdx.index.service.DocServiceTest" \
  --tests "dev.jdx.index.render.DocGoldenTest" \
  :cli:test --tests "dev.jdx.cli.commands.DocCommandTest" \
  :cli:tier2Test --tests "dev.jdx.cli.commands.DocCommandsServiceTest" \
  -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
./gradlew soak --rerun-tasks          # green (4m 12s)
```
Note: bare `jdx doc java.util.…` misses while the ambient `fx` workspace
(`include_jdk = false`) is selected — by design (L-067); the JDK path is
covered in `DocServiceTest` (exit 0 where `src.zip` exists, else exit 1 +
T-026 detail).

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: `verifyTier1Budget` red on
  this machine; sessions 24–28 + 36–44 unpushed before this session's push
  (push needs owner go-ahead per D-012 — granted for this session).

### Open questions / blockers
- None.

### Next action
- **T-026** (`DecompilerEngine` + Vineflower — expand into a detail block when
  started; T-027/T-028 remain M3 one-liners; T-072 `--with-doc` TODO).

---

## Session 43 — 2026-09-20 — T-024 `jdx signature` over bytecode done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `1093bfa` (claim) + closing commit (this session)

### Goal
Implement T-024, the third M3 CLI slice: `jdx signature` — member
signatures from bytecode alone (the parameter-info popup, PROPOSAL.md
§7.1). No sources needed (sources-less jars answer by design), no
decompilation (T-026/T-027), no Kotlin `@Metadata` views (T-035…T-037), no
javadoc (T-025).

### What I did
- `core/.../render/Signature.kt` (new): `SignatureBlock` result model
  (`signatures of Declaring#name` header, one bare signature line per
  overload in declaration order, `source:` line, truncation footer,
  warnings, `next: jdx show` hint; text+JSON parity like `BodyBlock`) +
  `buildSignatureBlock` (via `truncateEntities`, `--limit N` hint) +
  `DEFAULT_SIGNATURE_LIMIT = 50`.
- `index`: `JdxService.signature(rawRef, roots, SignatureOptions)` — type
  resolution with the T-011 machinery (exact/short-name, `g:a:v` scope,
  `DUPLICATE_FQN`), bytecode-authoritative (D-009); members match with the
  shared `matchBytecodeMembers`, bridge/synthetic filtered unless
  `includeSynthetic`. Under-specified names list every overload exit 0 (a
  signature can show many, unlike a body). New
  `ServiceOutcome.SignatureList` (+ the two `CorpusSoakTest`
  exhaustive-`when` branches).
- `cli`: thin `SignatureCommand` (registered in `JdxCli`;
  `--include-synthetic`/`--limit` live, shared roots flags) +
  `SignatureQuery` seam in `ReadCommandSupport`.
- `body --with-signature` (parked on this task) now works instead of
  exiting 3: `BodyOptions.withSignature` renders the matched bytecode line
  as `  signature:` between the source line and the slice
  (`BodyBlock.signature`, optional JSON key, off by default — existing
  goldens unaffected).
- Real bug caught by the golden review (fixed, pinned): refs were zipped
  from the sorted `canonicalMemberRefs` against declaration-ordered
  matches, swapping `:return` suffixes (bridge JSON showed `Child copy()`
  with a `:Base` ref; field/method refs swapped too). Split into
  `orderedMemberRefs` (pair in source order) + sorted wrapper for
  ambiguity lists — `body`/`source` behaviour byte-identical (L-071).
- Tests: core `SignatureBlockTest` (9 examples) +
  `SignatureBlockPropertyTest` (4 thousand-case properties: determinism,
  text⊆JSON, truncation law, no-escapes) + 3 `BodyBlockTest` header tests;
  index `SignatureServiceTest` (21 tier-2: generics/erased-spelling/ctor/
  field+method/nesting/bridges/limit/usage/no-roots/determinism/JSON +
  two alignment regression tests) + `SignatureGoldenTest` (10 files over 4
  fixture members incl. synthetic-bridge and limit variants — diff read
  before accepting) + `BodyServiceTest` header test; cli
  `SignatureCommandTest` (4 tier-1, hermetic) +
  `SignatureCommandsServiceTest` (8 tier-2, hermetic, incl. D-017) +
  `BodyCommandTest`/`BodyCommandsServiceTest` updates for the live flag.
- Verified: full `./gradlew check -x verifyTier1Budget` green incl. JaCoCo
  gates (1,181 tests, 0 failures/errors across all result XML); full
  `check` red only on `verifyTier1Budget` (pre-existing machine variance —
  slowest are `DoctorEnvironmentTest`/`JavaBodiesTest`, none from this
  task). Live proof via `app/build/jdx` (see below).
- Docs: T-024 DONE with notes; PROGRESS.md CURRENT STATE + items 16/18;
  Appendix B gains the `signature` row; L-071 distilled.

### Decisions made
- None (no new D-nnn). Under-specified member refs list all overloads exit
  0 rather than exit 2: a signature block can hold many rows, so listing is
  not guessing (D-016); type-level ambiguity still exits 2.
- Signature entries stay in declaration order (methods-then-fields as the
  matcher returns), not ref-sorted: deterministic from fixed class bytes,
  and overload families read naturally.

### Tasks moved
- T-024: TODO → WIP (`1093bfa`) → DONE.

### Lessons distilled
- **L-071** — never zip a sorted view against its unordered source (pair in
  source order via `orderedMemberRefs`, then sort the pairs if needed).

### What works now (and how to verify it yourself)
```bash
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
./app/build/jdx signature 'dev.jdx.fixtures.Generics#identity(U)' --jars "$JAR" --no-jdk
# exit 0: `public U identity(U value)`, bytecode provenance, next hint
./app/build/jdx signature 'dev.jdx.fixtures.CovariantOverrides$Child#copy' --jars "$JAR" --no-jdk --include-synthetic --json
# exit 0: both overloads, `:return` refs aligned with their signatures
./app/build/jdx body 'dev.jdx.fixtures.Generics#identity(U)' --jars "$JAR" --no-jdk --with-signature
# exit 0: `  signature: public U identity(U value)` above the slice
./gradlew check -x verifyTier1Budget  # green incl. gates (1,181 tests, 0 failures)
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: `verifyTier1Budget` red on
  this machine; sessions 24–28 + 36–42 unpushed before this session's push
  (push needs owner go-ahead per D-012).

### Open questions / blockers
- None.

### Next action
- **T-025** (`jdx doc` incl. inherited javadoc — expand into a detail block
  when started; T-026…T-028 remain M3 one-liners).

---

## Session 42 — 2026-09-20 — T-023 `jdx source` over the T-021 seam done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `e01141b` (claim) + closing commit (this session)

### Goal
Implement T-023, the second M3 CLI slice: wire the T-021 seam to `jdx
source` — whole source files / slices from paired Java sources only. No
decompilation (T-026/T-027), no Kotlin bodies (T-039), no doc/signature
enrichment (T-025/T-024), no `SOURCES_VERSION_MISMATCH` formalism (T-028 —
best-effort message only), no `--src`/`--sources` flags (T-031).

### What I did
- `core/.../render/Source.kt` (new): `SourceBlock` result model (canonical
  type-ref header, `source:` line, verbatim lines, truncation footer,
  warnings, `next: jdx show` hint; text+JSON parity like `BodyBlock`) + pure
  `sliceSourceLines` + `buildSourceBlock` + `DEFAULT_SOURCE_MAX_LINES = 200`.
- `index`: `JdxService.source(rawRef, roots, SourceOptions)` — type
  resolution with the T-011 machinery (exact/short-name, `g:a:v` scope,
  `DUPLICATE_FQN`), bytecode-authoritative (D-009). Whole files and `--lines`
  windows served verbatim without parsing via the shared `readSourceLines`
  helper; `--around` locates the member through T-021 with bytecode-first
  ambiguity plus the erased→generic-spelling retry, mirroring `executeBody`.
  New `ServiceOutcome.Source` (+ the two `CorpusSoakTest` exhaustive-`when`
  branches).
- `cli`: thin `SourceCommand` (registered in `JdxCli`; `--lines`/`--around`/
  `--context`/`--line-numbers`/`--max-lines` live, `--engine` exits 3 naming
  T-026/T-027) + `parseLinesWindow` + `SourceQuery` seam in
  `ReadCommandSupport`.
- Tests: core `SourceBlockTest` (12 examples) + `SourceBlockPropertyTest` (4
  thousand-case properties) + `parseLinesWindow` examples; index
  `SourceServiceTest` (22 tier-2) + `SourceGoldenTest` (8 files over 3
  fixture types incl. a lines+numbers variant — diff read before accepting);
  cli `SourceCommandTest` (10 tier-1, hermetic) +
  `SourceCommandsServiceTest` (8 tier-2, hermetic, incl. D-017).
- Real bugs caught by the tests (all fixed, all pinned): (1) serving a
  newline-terminated file via `split('\n')` reported a phantom 39th line —
  fixed with the shared `readSourceLines` trailing-line drop (L-068);
  (2) `.kt`-only roots were served raw as a Java answer (exit 0) because
  `findSource` — unlike `findJavaBodies` — does not filter them — fixed with
  an explicit T-039 degradation (L-069); (3) the D-017 test's
  `shouldNotContain "Exception"` failed on correct output because
  `StaticInitMarker.java` catches `Exception` in its own text — dropped in
  favour of exit-0 + absent-marker proof (L-070).
- Verified: `:core:test`, `:index:test+tier2Test`, `:cli:test+tier2Test`
  green (1,129 tests, 0 failures/errors across all result XML); full
  `./gradlew check -x verifyTier1Budget` green incl. JaCoCo gates;
  `./gradlew soak` green (2m 20s). Full `check` red only on
  `verifyTier1Budget` (64.6 s vs 30 s — pre-existing machine variance;
  slowest are `JavaBodiesTest`/`DoctorEnvironmentTest`, none from this task).
  Live proof via `app/build/jdx` (see below).
- Two self-inflicted edit wounds, both repaired byte-identically before
  committing (verified with `git diff`): an `edit` whose `newString`
  truncated the T-022 implementation notes, and an `edit` that deleted the
  `BodyQuery` typealias instead of appending after it. Lesson: never reuse a
  large block as edit context — anchor on the smallest unique string, and
  `git diff` immediately after every docs edit.

### Decisions made
- D-036 (`jdx source` output semantics: type-scoped with `--around` entry,
  verbatim-no-parse files/windows, `--lines` clamping/beyond-EOF, trailing-
  line drop, body-matching presentation, bytecode-first `--around`
  ambiguity) in `docs/decisions/D-026-050.md`.

### Tasks moved
- T-023: TODO → WIP (`e01141b`) → DONE.

### Lessons distilled
- **L-068** — `split('\n')` invents a phantom last line on
  newline-terminated files (one shared file→lines helper).
- **L-069** — reusing a seam below the old caller's level must re-check
  every degradation branch (the `.kt` bypass).
- **L-070** — whole-output `shouldNotContain` fails on words the file
  legitimately contains (D-017 proof is exit + absent marker).

### What works now (and how to verify it yourself)
```bash
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
./app/build/jdx source 'dev.jdx.fixtures.Generics' --jars "$JAR" --no-jdk
# exit 0: whole file, `-sources.jar` provenance, 1-38
./app/build/jdx source 'dev.jdx.fixtures.Generics' --jars "$JAR" --no-jdk --lines 22:24
# exit 0: identity window only
./app/build/jdx source 'dev.jdx.fixtures.Generics' --jars "$JAR" --no-jdk \
  --around 'dev.jdx.fixtures.Generics#identity(java.lang.Object)' --context 1 --line-numbers
# exit 0: member slice with numbers
./app/build/jdx source 'dev.jdx.fixtures.Generics#identity(Object)' --jars "$JAR" --no-jdk
# exit 3 naming --around
./gradlew :core:test --tests "dev.jdx.core.render.SourceBlock*" \
  :index:tier2Test --tests "dev.jdx.index.service.SourceServiceTest" \
  --tests "dev.jdx.index.render.SourceGoldenTest" :cli:test \
  --tests "dev.jdx.cli.commands.SourceCommandTest" :cli:tier2Test \
  --tests "dev.jdx.cli.commands.SourceCommandsServiceTest" -x verifyTier1Budget
./gradlew check -x verifyTier1Budget  # green incl. gates
./gradlew soak                        # green (2m 20s)
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: `verifyTier1Budget` red on
  this machine; sessions 24–28 + 36–42 unpushed (push needs owner go-ahead
  per D-012).

### Open questions / blockers
- None.

### Next action
- **T-024** (`jdx signature` — expand into a detail block when started;
  T-025…T-028 remain M3 one-liners).

---

## Session 41 — 2026-09-20 — T-022 `jdx body` over the T-021 seam done
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `4339da4` (claim) + closing commit (this session)

### Goal
Implement T-022, the first M3 CLI slice: wire the T-021 `findJavaBodies` seam
to `jdx body` — member bodies from paired Java sources only. No decompilation
(T-026/T-027), no Kotlin bodies (T-039), no doc/signature enrichment
(T-025/T-024), no type-ref whole-file bodies (T-023), no
`SOURCES_VERSION_MISMATCH` formalism (T-028 — best-effort message only), no
`--src`/`--sources` flags (T-031).

### What I did
- `core/.../render/Body.kt` (new): `BodyBlock` result model (canonical-ref
  header, `source:` line, verbatim lines, truncation footer, warnings,
  `next: jdx show` hint; text+JSON parity like `MemberListing`/`ClassCard`) +
  pure `sliceBodyLines` (context expansion clamped to the file, `--max-lines`
  cap with `shown`/`total` hint) + `buildBodyBlock` + `DEFAULT_BODY_MAX_LINES
  = 200`. `ErrorResult.NotFound` gained an optional `detail` (second text
  line + appended JSON message; candidates untouched).
- `index`: new `:sources` dependency; `JarArtifact.openSources()`
  (external/embedded/absent) + `JrtArtifact.openSources()` (`src.zip`);
  `JdxService.body(rawRef, roots, BodyOptions)` — declaring-type resolution
  with the T-011 machinery (exact/short-name, `g:a:v` scope,
  `DUPLICATE_FQN`), overload ambiguity from bytecode (name + arity +
  simple-name narrowing with a generic-signature fallback for erased type
  variables; `:return` suffix exactly like listings), then the winning root's
  paired sources sliced via T-021 — retrying erased queries with the generic
  signature's spellings (`identity(Object)` finds `U identity(U)`).
  Provenance names the sources file. New `ServiceOutcome.Body`.
- `cli`: thin `BodyCommand` (registered in `JdxCli`; `--context`/
  `--line-numbers`/`--max-lines` live, `--engine`/`--with-doc`/
  `--with-signature` exit 3 naming T-026/T-027/T-025/T-024) + `BodyQuery`
  seam in `ReadCommandSupport`.
- Tests: core `BodyBlockTest` (12 examples) + `BodyBlockPropertyTest` (4
  thousand-case properties) + 2 `NotFound`-detail examples; index
  `BodyServiceTest` (19 tier-2) + `BodyGoldenTest` (10 files over 4 fixture
  members incl. a context+numbers variant — diff read before accepting); cli
  `BodyCommandTest` (8 tier-1, hermetic) + `BodyCommandsServiceTest` (8
  tier-2, hermetic, incl. D-017). Drive-by: `CorpusSoakTest`'s two exhaustive
  `when`s gained the `Body` branch.
- Real bugs caught: `Generic` rejects exits 1/2 by construction (my "no
  sources" path threw → exit 6; fixed with `NotFound.detail`, L-065);
  erased-descriptor queries missed generic members in *both* directions
  (bytecode matching needed the generic-signature fallback AND source lookup
  the spelling retry — the first green run hid it because tests used `(U)`);
  golden `kind` expectations for `memberName`… (none — goldens verified clean).
- Verified: `:core:test`, `:index:test+tier2Test`, `:cli:test+tier2Test`
  green; full `./gradlew check -x verifyTier1Budget --rerun-tasks` green
  (incl. JaCoCo gates); `./gradlew soak --rerun-tasks` green (2m 23s).
  `check` red only on `verifyTier1Budget` (pre-existing machine variance).
  Live proof via `app/build/jdx` (see below).
- Non-bug mystery logged as L-067: a bare `ArrayList` miss from the CLI was
  the ambient `fx` workspace (`include_jdk = false`), not the service —
  `doctor`'s workspace row diagnosed it.

### Decisions made
- D-035 (`jdx body` output semantics: erased printed refs, bytecode-first
  ambiguity, sources-file provenance, type-refs → T-023, presentation
  defaults, the new `index`→`sources` edge) in `docs/decisions/D-026-050.md`.

### Tasks moved
- T-022: TODO → WIP (`4339da4`) → DONE.

### Lessons distilled
- **L-065** — message-carrying exit-1 needs its own error shape (`Generic`
  rejects 1/2; extend, don't widen the `require`).
- **L-066** — stale test-result XML outlives a failed compile (trust the task
  result first; kotest `map` needs an explicit import).
- **L-067** — ambient workspace explains "impossible" CLI misses
  (`user.home` ignores `$HOME`; check `doctor`'s workspace row first).

### What works now (and how to verify it yourself)
```bash
JAR=testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar
./app/build/jdx body 'dev.jdx.fixtures.Generics#identity(U)' --jars "$JAR" --no-jdk
# exit 0: erased header, verbatim slice, `-sources.jar` provenance
./app/build/jdx body 'dev.jdx.fixtures.CovariantOverrides$Child#copy' --jars "$JAR" --no-jdk
# exit 2 with two :return-suffixed candidates
./gradlew :core:test :index:tier2Test --tests "dev.jdx.index.service.BodyServiceTest" \
  --tests "dev.jdx.index.render.BodyGoldenTest" :cli:test \
  :cli:tier2Test --tests "dev.jdx.cli.commands.BodyCommand*" -x verifyTier1Budget
./gradlew check -x verifyTier1Budget --rerun-tasks  # green incl. gates
./gradlew soak --rerun-tasks                        # green (2m 23s)
```
Note: bare `jdx body java.util.…` misses while the ambient `fx` workspace
(`include_jdk = false`) is selected — by design (L-067); the JDK path is
covered in `BodyServiceTest` (exit 1 + T-026 detail where `src.zip` is absent).

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: `verifyTier1Budget` red on
  this machine; sessions 24–28 + 36–41 unpushed before this session's push
  (push needs owner go-ahead per D-012 — granted for this session).

### Open questions / blockers
- None.

### Next action
- **T-023** (`jdx source` — expand into a detail block when started;
  T-024…T-028 remain M3 one-liners).
