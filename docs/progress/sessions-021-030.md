# Session log — shard 3: sessions 21–30

Append-only session log, **10 sessions per shard** (D-023). `docs/PROGRESS.md` holds the
`CURRENT STATE` handoff, the shard index, and the entry template — this file only holds
entries. Newest entry first; never edit or delete a past entry — if one turned out to be
wrong, say so in a *new* entry.

---

## Session 23 — 2026-09-17 — `javap` differential harness (T-056)

**Agent:** Muse Spark (via opencode) · **Branch:** none (on `main`) ·
**Commits:** `37b6909` (claim), this session's work (to commit with this entry)

### Goal
Implement T-056: parse `javap -p -s` into a member set and diff it against
`jdx members --declared --access all --include-synthetic --json`, tier 2 over
the whole fixture corpus plus tier 3 over a seeded real-corpus sample.
(Correction, recorded honestly: the board rule names the *lowest-numbered*
unblocked TODO, which is T-017, not T-056 — misread at pick-up time. T-056's
work is complete and green, so reverting would be waste; the next contributor
should take T-017 per the rule.)

### What I did
1. Claimed T-056 (`TODO`→`WIP`, commit `37b6909`) before coding.
2. Probed `javap -p -s` shapes on the fixture jar first: `static {};` carries
   `descriptor: ()V` (so the reader-level check stays exact while the
   *listing* check must allowlist `<clinit>` — the resolver drops it by
   design); bridges print as overloads differing only in return type (drives
   the `:return`-suffix prefix matching + per-base overload counts).
3. New package `index/src/test/.../differential/`:
   `Javap.kt` (find/run/parse, `null`-on-absent, never throws),
   `JavapQuirks.kt` (single allowlist, one commented entry: `<clinit>`),
   `ServiceDifferential.kt` (one-class compare through `JdxService`:
   presence, per-base overload counts, `--json` ref carriage, truncation
   refused; mismatch lines name member + descriptor + side),
   `JavapDifferentialTest.kt` (`@Tag("tier2")`, all 35 fixture classes),
   `JavapCorpusSoakTest.kt` (`@Tag("soak")`, seed 20260917,
   `-Djdx.soakSeed` override, corpus via `-Djdx.corpusDir`/`-Pcorpus`,
   exit-5/unreadable counted skips, everything else fails with the report).
   Comparison is on canonical refs (they erase return/field types by design;
   descriptor fidelity stays with the T-008 reader check — said in the KDoc).
4. Migrated `AsmClassReaderDifferentialTest` onto shared `Javap` (deleted ~60
   lines of copied parser); its 7 tests green unmodified — no behaviour change.
5. First soak run (104-class sample) found a **real production bug**: exit 6
   `name segment 'SavedStateRegistry$SavedStateProvider' contains a separator`
   on androidx jars. Root cause: kotlinc emits `$`-nested supertypes inside
   generic signatures; `GenericRef.toTypeName` built the edge unsplit.
   Fixed **test-first in `core`** (RED test reproducing the exact soak stack,
   then GREEN): split `$` at the model edge mirroring
   `typeNameFromBinaryName`, null on still-unmappable edges (the linearisation
   already skips those). The signature parser itself is untouched — its
   parse→print fixed point would break (`$` reprints as `.`).
6. Fixed my own sampler bug the same run surfaced: raw `ZipFile` entries
   bypass multi-release selection (`META-INF/versions/11/module-info` 404d).
   Sampling now goes through `ArtifactLoader.classEntryPaths()`.
7. Verification: `./gradlew check --offline` green (**631 tests tiers 1+2, 0
   failures**; run with `~/.config/jdx` shelved per the pre-existing T-066
   caveat, restored after); `./gradlew :index:soakTest --offline` green —
   **seed 20260917, 30 jars, 104 classes, 0 skipped, 0 failed in 51 s**.
   Full `./gradlew soak` (JDK-index suite) not re-run — untouched by this task.

### Decisions made
None (no new D-nnn). Deliberate comparison choices are recorded as KDoc on
`ServiceDifferential` (ref-level vs descriptor-level ownership,
`:return`-suffix prefix matching), and the single quirk as a commented entry
in `JavapQuirks.kt` per the task's review-blocker rule.

### Tasks moved
T-056: TODO → WIP (`37b6909`) → DONE (this commit).

### Lessons distilled
L-042 (`kotlin`): kotlinc `$`-nested supertype edges — split at the model
edge, never in the signature parser. L-043 (`tooling`): corpus samplers must
enumerate via `ArtifactLoader`, not raw zip entries.

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx /tmp/jdx-shelved   # only if an active workspace exists (see L-038); restore after
./gradlew :index:tier2Test --offline --tests "dev.jdx.index.differential.JavapDifferentialTest"  # 35 classes, ~17 s
./gradlew :index:soakTest --offline --tests "dev.jdx.index.differential.JavapCorpusSoakTest"      # 30 jars × 4 classes, ~51 s
./gradlew :index:tier2Test --offline --tests "dev.jdx.index.asm.AsmClassReaderDifferentialTest"   # shared-parser migration proof
./gradlew :core:test --offline --tests "dev.jdx.core.resolve.MemberResolverTest"                  # incl. the RED-first $-edge test
mv /tmp/jdx-shelved ~/.config/jdx
```

### What is broken / half-done
Nothing in T-056 scope. Known, owned elsewhere: T-066 (ambient-workspace
`ReadCommandsTest` reds — shelved around `check` here, unchanged); T-064/T-065
(indexer perf gap, JFR `$$` names) untouched. The soak's exit-5 skip bucket
stayed at 0 on this corpus — corrupt/future-version jars simply were not
sampled; fault-injection (T-057) owns those shapes deliberately.

### Open questions / blockers
None. **Push needs owner go-ahead (D-012)** — session 23 commits unpushed
(claim `37b6909` + this work).

### Next action
**T-017** (`search`/`resolve`/`ls`/`tree`) — the actual lowest unblocked TODO
(depends T-014 DONE); see the Goal correction above.

---

## Session 22 — 2026-09-16 — Project auto-discovery (T-016)
**Agent:** Muse Spark (via opencode) · **Branch:** none (on `main` at `c1e87fa`) ·
**Commits:** `c1e87fa` (claim), this session's work (to commit with this entry)

### Goal
Implement T-016 — the lowest-numbered unblocked TODO: walk up for Gradle/Maven
markers, derive binary roots (own classes + referenced dependency jars, never running
a build), cache the derived workspace with build-file invalidation.

### What I did
1. Claimed T-016 (`TODO`→`WIP`, commit `c1e87fa`) before coding.
2. **Test-first where it counts:** `core`'s closed-set `WarningCode` test extended
   first (RED), then `PROJECT_DISCOVERY_FALLBACK` added (GREEN); PROPOSAL.md §16
   updated in the same step per the `Warning` KDoc rule.
3. **New `index/.../workspace/ProjectDiscovery.kt`:** `findProjectRoot` (nearest
   marker, `settings.gradle.kts` first), `parseLockCoordinates` (regex over
   lockfiles + scripts), `parsePomCoordinates` (hardened DOM, `${…}` versions
   skipped), `resolveDependencyJars` (Gradle `files-2.1` + `~/.m2` layouts,
   sources/javadoc excluded, capped, sorted), `deriveBinaryRoots` (deepest
   package roots first, then jars), `projectHash`/`computeFingerprint`;
   **`ProjectCache`** (`<hash>.toml` + `<hash>.fingerprint`, mismatch → re-derive).
4. **Plumbing:** `WorkspaceResolver` takes discovery only with no named workspace
   (explicit `--jars` merge in front); `JdxService.RootsSpec.extraWarnings` carries
   resolution warnings into every listing; `ReadCommandSupport` owns lookup +
   caching behind an injectable `ProjectDiscoveryFn`; `doctor` shares the walk;
   `show --help` + `WorkspaceDefinition` KDoc updated; `ws create --src` now
   names T-031.
5. **Two real bugs caught by tests:** (a) deepest-vs-outermost class dirs —
   opening `build/classes` lists `java/main/…`-prefixed entries nothing matches
   (exit 1), so derivation keeps the deepest package roots (L-040); (b) the repo
   checkout itself is ambient state — tier-1 command tests and goldens grew
   `cli/build/classes` + the fallback warning, fixed with the `ProjectDiscoveryFn`
   seam (null in tier-1/pinned suites, real path covered once in tier-2; L-041).
   A stray `/*.jar` glob inside KDoc killed compilation with a far-away
   `Missing '}'` — Kotlin comments nest (L-039).
6. `./gradlew check --offline` green (11 new tier-1 incl. two 1,000-case
   properties — 480 tier-1 in 8.0 s of 30 s — plus 31 new tier-2),
   `./gradlew soak --offline` green, all 280 existing goldens byte-identical.
   Live smoke: fabricated `/tmp/fakeproj` answers `members` with zero flags
   (exit 0, labelled fallback warning, text+JSON agree); `doctor` names the root.
   Test-run pollution of the real `~/.cache/jdx/auto` (pre-seam run) removed.

### Decisions made
**D-030** (project auto-discovery semantics: no broad cache scan, N3 coordinate
sources, deepest roots, discovery-only-without-workspace, fingerprint sidecar,
binary roots only) in `docs/decisions/D-026-050.md`, indexed in `docs/DECISIONS.md`.

### Tasks moved
- T-016: TODO → WIP → DONE.

### Lessons distilled
**L-039** (nested Kotlin comments vs globs in KDoc), **L-040** (class-dir roots
must be package roots), **L-041** (the checkout is ambient discovery state;
seam it like the store) in `docs/lessons/L-026-050.md`; tag index updated.

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx /tmp/jdx-shelved   # only if an active workspace exists (L-038); restore after
./gradlew check --offline           # tiers 1+2 green (480 tier-1 + tier-2)
./gradlew soak --offline            # tier 3 green
mv /tmp/jdx-shelved ~/.config/jdx
# live zero-config read inside any Gradle/Maven project:
mkdir -p /tmp/fakeproj/build/classes/java/main && printf 'rootProject.name = "d"\n' > /tmp/fakeproj/settings.gradle
./gradlew :app:installDist --offline && ./app/build/jdx members '<YourClass>'   # run with CWD=/tmp/fakeproj
```

### What is broken / half-done
Nothing in T-016 scope. Known, owned elsewhere: T-066 (pre-existing
`ReadCommandsTest` store hermeticity — this task fixed the *discovery* half by
threading `ProjectDiscoveryFn`; the store half is still T-066's), T-064/T-065
(indexer perf gap, JFR `$$` names) untouched.

### Open questions / blockers
None. **Push needs owner go-ahead (D-012)** — session 22 commits unpushed
(claim `c1e87fa` + this work). `~/.config/jdx` shelved during verification;
restored afterwards (verify `ls ~/.config/jdx` shows `active-workspace`).

### Next action
**T-062** (`--sort name|declaring` — small, builds on the read path), or
**T-066** (finish `ReadCommandsTest` hermeticity now the discovery seam exists),
or **T-017** (`search`/`resolve`/`ls`/`tree` — next M2 behaviour).

## Session 21 — 2026-09-16 — Shared property-test generators + missing §4 properties (T-055)
**Agent:** Muse Spark (via opencode) · **Branch:** none (on `main` at `104b2d5`) ·
**Commits:** `104b2d5` (claim), this session's work (to commit with this entry)

### Goal
Implement T-055 — the lowest-numbered unblocked TODO: kotest-property shared generators
(`TypeName`/`JvmDescriptor`/`GenericSignature`) plus every TESTING.md §4 property at
≥1,000 cases, with seed-pinning documented.

### What I did
1. Claimed T-055 (`TODO`→`WIP`, commit `104b2d5`) before coding.
2. **Test-first, RED:** wrote `GeneratorsTest` + three property suites against
   non-existent `gen/` APIs (`Unresolved reference`, the right reason), then GREEN.
3. **New `core/.../gen/` files:** `TypeNameGenerators.kt` (`arbTypeName`,
   `arbFieldTypeName`, `arbJvmDescriptor`; empty packages, unicode segments, 3-deep
   nesting whose binary names carry `$`, 1–3-dim arrays), `SignatureGenerators.kt`
   (all three wildcard kinds, type variables incl. empty-class-bound params, nested
   generics, inner classes with own args, void returns, throws), `PropertySupport.kt`
   (`JDX_PROPERTY_ITERATIONS = 1_000`, `pinnedConfig(seed)` + seed-workflow KDoc).
4. **New properties, all 1,000 cases:** `TypeNamePropertyTest` (binaryName +
   field-descriptor fixed points), `JvmDescriptorPropertyTest` (descriptor fixed point
   + never-throws), `GenericSignaturePropertyTest` (`parse` + `parseClass` fixed points
   + never-throws), `print(parse(s))` stability in `SymbolRefPropertyTest`,
   `GeneratorsTest` (fixed-seed nastiness coverage + `pinnedConfig` API pin).
   Raised the three 500-case `MemberListingPropertyTest` properties to 1,000.
5. **Real production bug, caught by the new never-throws property on its first run:**
   `JvmDescriptor.parse("L1C)1JQLB)Q.;")` threw `IllegalArgumentException` (empty
   segment) instead of returning null — `parseDescriptorType` handed untrusted
   class-file text to throwing `typeNameFromBinaryName`. Fixed at that boundary
   (catch → null, matching its own KDoc); regression strings pinned in
   `JvmDescriptorTest`'s malformed list. Same run caught void-array generation
   (`[[[V]`); array elements now exclude `VOID` (L-037).
6. **Generator bugs the tests caught:** eager Arb construction overflows on any
   non-decreasing recursion (depth-0 base must build nothing recursive); no
   single-arity `Arb.bind` in kotest 6 (use `.map`); `Arb.of(emptyList())` throws
   (L-036).
7. `./gradlew check --offline` green (**586 tests**, 0 failures: 469 tier-1 + 117
   tier-2), `./gradlew soak --offline` green (38 s). Tier-1 aggregate 7.7 s → 18.0 s
   across 469 tests — inside the 30 s budget, but headroom is now 12 s.
8. **Pre-existing red, not mine:** 2 `ReadCommandsTest` failures come from this
   machine's real `~/.config/jdx/active-workspace` (`fx`, dated 15:51, a prior
   session's e2e leak) combined with the test's real-home `FileWorkspaceStore`
   default. Proven by shelving `~/.config/jdx`: green without it, red with it —
   all runs above used the shelved state. Filed as **T-066**, lessons as **L-038**;
   ambient state restored untouched. TESTING.md §4 documents the seed pin one-liner.

### Decisions made
None at D-level. Judgement calls in code KDoc: `$` appears only as the nesting
separator in generated names (bare segments forbid it per the `ClassInfo` invariant
and D-025); `checkAll(1_000, pinnedConfig(seed), arb)` is the pin form (verified
against the kotest 6 `checkAll(int, PropTestConfig, Gen)` overload); a left-behind
seed pin is a test that stopped generating (remove after the fix).

### Tasks moved
- T-055: TODO → WIP → DONE.
- T-066 added (TODO) — `ReadCommandsTest` hermeticity to machine workspaces.

### Lessons distilled
**L-036** (kotest 6 eager generators: recursion must bottom out at construction;
no 1-arity bind; `of(empty)` throws), **L-037** (null-on-malformed parsers must not
call throwing constructors; void is not a field type), **L-038** (command tests
must inject the workspace store) in `docs/lessons/L-026-050.md`; tag index updated.

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx /tmp/jdx-shelved   # only if an active workspace exists (see L-038); restore after
./gradlew check --offline           # tiers 1+2 green (586 tests, 0 failures)
./gradlew soak --offline            # tier 3 green
./gradlew :core:test --offline --tests "dev.jdx.core.gen.*" --tests "dev.jdx.core.model.*PropertyTest"
mv /tmp/jdx-shelved ~/.config/jdx
```

### What is broken / half-done
Nothing in T-055 scope. Known, owned elsewhere: T-066 (`ReadCommandsTest` red when
`~/.config/jdx/active-workspace` exists — this machine is in that state, so a bare
`./gradlew check` shows 2 failures in `:cli:test` until T-066 lands); T-064/T-065
(indexer perf gap, JFR `$$` names) untouched.

### Open questions / blockers
None. **Push needs owner go-ahead (D-012)** — session 21 commits unpushed
(claim `104b2d5` + this work).

### Next action
**T-016** (project auto-discovery) — lowest unblocked TODO; or **T-066** (small,
hermetic, unblocks a green `check` on this machine).
