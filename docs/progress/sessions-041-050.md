# Sessions 041–050

Newest first. Each entry follows the template at the bottom of `docs/PROGRESS.md`.

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
