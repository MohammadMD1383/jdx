# Sessions 041–050

Newest first. Each entry follows the template at the bottom of `docs/PROGRESS.md`.

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
