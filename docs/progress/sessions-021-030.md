# Session log — shard 3: sessions 21–30

Append-only session log, **10 sessions per shard** (D-023). `docs/PROGRESS.md` holds the
`CURRENT STATE` handoff, the shard index, and the entry template — this file only holds
entries. Newest entry first; never edit or delete a past entry — if one turned out to be
wrong, say so in a *new* entry.

---

## Session 24 — 2026-09-17 — `search`/`resolve`/`ls`/`tree` (T-017)

**Agent:** Muse Spark (via opencode) · **Branch:** none (on `main`) ·
**Commits:** `55a03de` (claim), this session's work (to commit with this entry)

### Goal
Implement T-017, the lowest-numbered unblocked TODO (depends T-014 DONE):
`jdx search`, `jdx resolve`, `jdx ls`, `jdx tree` with glob, regex and
IntelliJ-style camel-hump matching, `--fuzzy` Levenshtein fallback and the
§16 did-you-mean path — behind the standing test bar (tiers 1+2 green, tier 3
green for index/rendering changes, one generative family minimum).

### What I did
1. Claimed T-017 (`TODO`→`WIP`, commit `55a03de`) before coding.
2. **Core, test-first:** `core/.../search/SymbolSearch.kt` (pure
   glob/regex/camel-hump/fuzzy matcher) with `SymbolSearchTest` (15 examples)
   written first (RED on missing symbol, GREEN after). Then
   `core/.../render/SearchResults.kt` (`SearchHit`/`SearchListing`,
   `PackageEntry`/`LsTypeEntry`/`LsListing`, `TreeNode`/`ArtifactTree`/
   `TreeListing`, all with text+JSON parity, truncation, deterministic order)
   with `SearchResultsTest` + `SearchResultsPropertyTest` (determinism,
   truncation law, text⊆JSON, tree node-count law).
3. **Generative law paid off immediately (L-044):** the 1,000-case
   never-throws property failed three consecutive runs, each a real
   `PatternSyntaxException` in `globToRegex`: `[F[ßN]` (nested `[` is no legal
   Java class), `[]`/`[^]` (empty/negated-empty classes are unclosed), and a
   reversed range (`[Q-9]` is illegal in regex, literal in globs). Fixed by
   escaping `[`/`\`/`&` inside classes, literal `[]`, literal leading `^`,
   and `-`-as-range-only-between-ascending-alphanumerics; each pinned as an
   example. Three extra full-property reruns (fresh seeds) green.
4. **Index:** `JdxService.search/resolve/ls/tree` over live roots (name-match
   on entry names first, ASM-parse only matches — except member search, which
   parses the scope and is documented slow pending index-backed search).
   Two service bugs found test-first and fixed: exact-FQN search returned the
   `$`-nested family too (substring), breaking the TESTING.md metamorphic law
   — fixed by the dotted-word-names-a-location rule; `PersonRecord` missed by
   `--kind class` (it is a `RECORD` — test bug, fixed to `RECORD`).
   `JdxService.levenshtein` now delegates to `SymbolSearch` (one impl).
5. **CLI:** `SearchCommands.kt` (`SearchCommand`, `ResolveCommand`,
   `LsCommand`, `TreeCommand` — thin, D-004), query typealiases in
   `ReadCommandSupport`, registration in `JdxCli`.
6. **Tests (tier 2):** `SearchServiceTest` (28 tests: modes, kinds incl.
   JDK-module via real JRT, `--in`/`--package`, fuzzy, truncation, exits
   1/3/4, determinism, text⊆JSON, exact-FQN metamorphic),
   `SearchGoldenTest` (20 files over 10 queries, hermetic via jar-name
   sanitising to `fixture-corpus.jar` + `--no-jdk`; diff read before keeping —
   kinds/refs/counts all honest), `SearchCommandsServiceTest` (8 in-process
   CLI tests incl. JSON-parses-with-same-hits). New CLI tests inject
   `InMemoryWorkspaceStore()` + null `getenv` + null discovery — the first
   run exited 5 everywhere from the ambient `fx` workspace, i.e. the T-066
   trap live (L-038); hermetic from the start, unlike the suite T-066 fixes.
7. **Verification:** `./gradlew check` green (tiers 1+2) and `./gradlew soak`
   green — both only with `~/.config/jdx` shelved (known T-066 ambient reds)
   and `--no-configuration-cache` (T-067 pre-existing cache-storing failure).
   Both workarounds proven stashed-clean on the unmodified tree (L-045):
   2 `ReadCommandsTest` reds with config present / green shelved; config-cache
   "2 problems storing" fails even `:core:test` on the clean tree.
8. **Real-binary e2e** (`:app:installDist` → `app/build/jdx`): `search`,
   `--json`, `ls`, `tree --counts`, `resolve 'Generics#identity'` and a
   miss (exit 1) all behave. First run listed every hit twice — same jar via
   explicit `--jars` *and* the ambient `fx` workspace (which globs the fixture
   jar): correct per the §13 merge + D-031 per-provider rules, but noisy;
   filed as T-068 (dedupe identical resolved roots). Hermetic rerun (active
   workspace shelved) is clean, with an honest `PROJECT_DISCOVERY_FALLBACK`.

### Decisions made
- **D-031** (proposed-by-implementer): search semantics — mode order
  (regex > glob > plain), dotted-word-as-location vs bare-word search,
  default kind excludes members (perf), per-provider hits instead of
  `DUPLICATE_FQN`, resolve-exact semantics, ls-exact-vs-glob, tree grouping,
  live-roots (no persistent index yet).

### Tasks moved
- T-017: TODO → WIP (`55a03de`) → DONE (this session).
- T-067 (new): configuration-cache storing failure — pre-existing, filed, not
  fixed here.
- T-068 (new): dedupe identical resolved roots — e2e observation, correct
  per-design today, filed as polish.

### Lessons distilled
- L-044 (tooling): never-throws regex property first; pin each shrink as an
  example.
- L-045 (build): stash-and-rerun to prove red-cause ownership before fixing.

### What works now (and how to verify it yourself)
```bash
./gradlew :app:installDist --no-configuration-cache  # then use app/build/jdx
mv ~/.config/jdx /tmp/shelved-jdx  # dodge the known T-066 ambient reds
./gradlew check --no-configuration-cache   # tiers 1+2, green
./gradlew soak --no-configuration-cache    # tier 3, green
mv /tmp/shelved-jdx ~/.config/jdx
./gradlew :index:tier2Test --tests "dev.jdx.index.service.SearchServiceTest" --no-configuration-cache
./gradlew :index:tier2Test --tests "dev.jdx.index.render.SearchGoldenTest" --no-configuration-cache
./gradlew :cli:tier2Test --tests "dev.jdx.cli.commands.SearchCommandsServiceTest" --no-configuration-cache
```

### What is broken / half-done
- Nothing from T-017. Member search over JDK-scale workspaces parses the whole
  scope per query (documented in D-031 §5); index-backed member search waits
  for the M4 graph work.
- Pre-existing, not mine: T-066 (ambient workspace reds 2 tier-1 tests),
  T-067 (config-cache storing fails every build without the flag).

### Open questions / blockers
- None.

### Next action
- **T-018** (`jdx cache info|gc|clear`) — lowest unblocked TODO (depends T-013
  DONE). Note: it will need the same `--no-configuration-cache` + shelved-home
  workarounds until T-066/T-067 land.

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

## Session 25 — 2026-09-17 — `cache info|gc|clear` (T-018) + Gradle deprecations and T-067
**Agent/Author:** Muse Spark (opencode) · **Commits:** `2594773` (claim), `59110db` (T-018), `1a7d601` (build/T-067)

### Goal
Implement T-018 (`jdx cache info|gc|clear`, lowest unblocked TODO), then address the
Gradle deprecation footer at the owner's end-of-session request — which turned into
closing T-067 (configuration-cache storing failure).

### What I did
- **T-018:** `index/.../cache/CacheService.kt` (policy: `info` sizes/counts with empty-DB
  report; `gc [--dry-run]` collecting stale + unreferenced artifacts, JRT kept while any
  workspace includes the JDK or no workspaces exist, corrupt workspace → exit 4, DB/IO →
  exit 6, never throws; `clear` wiping `v1.db*` + `auto/`), `cli/.../commands/CacheCommands.kt`
  (thin group mirroring `wsGroup`: `--cache-dir`, `--dry-run`, text+JSON parity), registered
  in `JdxCli`. PROPOSAL Appendix B line added (README already had the row).
- **Drive-by fix (e2e find):** `jdx --json cache info` printed text — `effectiveJson`/`effectiveWorkspace`
  read only one context parent, so grouped commands (`ws list`, `cache info`) lost root flags.
  Added `rootCommand()` chain walk in `JdxCli.kt`; fixes `ws` too, pinned by a new test (L-048).
- **Tests:** 12 tier-1 examples (`CacheServiceTest` over `FakeIndexStore`, no disk) + 3
  thousand-case properties (`CacheServicePropertyTest`: info totals, dry-run purity +
  determinism, gc idempotence + reference agreement) + 6 tier-2 SQLite tests
  (`CacheServiceStoreTest`, real fixture jar via `ArtifactIndexer`) + 11 tier-2 CLI tests
  (`CacheCommandsTest`: exits, text⊆JSON, determinism, root-position JSON, root-CLI wiring).
- **Gradle deprecations:** problems report held exactly one deprecation (toolchain
  auto-provisioning without repositories). Declared Foojay resolver 1.0.0 in settings
  (version literal — catalog accessors don't resolve in settings scripts, L-046) and moved
  the stale legacy-provisioned JetBrains JDK out of `~/.gradle/jdks` (backup at
  `/tmp/stale-jdk-backup`); local OpenJDK 21 auto-detects, diagnostic gone, no download.
- **T-067:** the config-cache report JSON named the real culprits — `:app:installDist`
  (`layout` in `doLast`) and `:verifyTier1Budget` (`subprojects` + script-level budget in
  `doLast`) capturing the script object — plus a `Task.project`-at-execution-time
  deprecation in `:cli:generateBuildProperties`. All capture plain values at configuration
  time now. Verified T-067 acceptance: plain `./gradlew :core:test` stores the entry,
  `./gradlew check` stores and reuses it, zero config-cache problems. Caveat: verifying
  needed `-Dhttp.nonProxyHosts='*'` — the localhost proxy was refusing connections and
  `:mcp:` deps were uncached, which fails config-cache *storing* (resolution) with an
  unrelated network error. Environmental, not a build bug.
- **Not pushed** (D-012: no remote push without owner go-ahead in this session).

### Decisions made
- T-018 semantics recorded in its TASKS.md detail block: reference = expanded workspace
  jar specs (tolerant skip); `clear` = DB files + `auto/`; no last-use tracking in v1
  (PROPOSAL "recently used" approximated by reference only, said in the service KDoc).
- No new D-nnn (all within existing contracts); T-067 fix is build-only.

### Tasks moved
- T-018: TODO → WIP (`2594773`) → DONE (`59110db`)
- T-067: TODO → DONE (`1a7d601`, verified with proxy bypass)

### Lessons distilled
- L-046 (catalog accessors absent in settings), L-047 (config-cache report JSON diagnosis),
  L-048 (root-flag chain walk for grouped commands), L-049 (stale provisioned JDK).

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx ~/.config/jdx.shelved  # T-066 ambient-workspace workaround, still open
./gradlew check --no-configuration-cache   # tiers 1+2 green (BUILD SUCCESSFUL)
./gradlew :core:test                       # config cache stores; repeat reuses (T-067 proof)
./gradlew :app:installDist --no-configuration-cache -q
app/build/jdx cache info                   # empty-DB report, exit 0
app/build/jdx --json cache info            # JSON via root-position flag (was text before fix)
app/build/jdx cache gc --dry-run; app/build/jdx cache clear
mv ~/.config/jdx.shelved ~/.config/jdx
```

### What is broken / half-done
- T-066 (ambient `fx` workspace reds 2 `ReadCommandsTest`s) still open — shelve workaround stands.
- T-067 verification needed proxy bypass; with the flaky localhost proxy, config-cache
  *storing* can still fail on `:mcp:` dependency resolution (network, not build logic).
- `jdx index` CLI does not exist yet — the index DB on user machines is populated only by
  tests/soak until the indexer gets its command (M2 follow-up, already tasked).

### Open questions / blockers
None. Push needs owner go-ahead (D-012) — two commits (`59110db`, `1a7d601`) unpushed.

### Next action
**T-019** (Maven coordinate resolution) or **T-057/T-058** (fault-injection/metamorphic
suites) — lowest unblocked TODOs after T-018; or **T-066** (small, unblocks green `check`
on this machine). Next lessons shard `L-051-075.md` opens at the next lesson (one free slot left).

## Session 26 — 2026-09-17 — Fault-injection suite (T-057)
**Agent/Author:** Muse Spark (opencode) · **Commits:** `d2d9ed5` (claim), `f22fcbd` (T-057), this entry's docs commit

### Goal
Implement T-057, the lowest-numbered unblocked TODO (depends T-007 DONE): the
fault-injection family from TESTING.md §7, asserting the D-015 exit code, the
problem-naming output, and stream silence for every malformed input.

### What I did
1. Claimed T-057 (`TODO`→`WIP`, commit `d2d9ed5`) before coding.
2. **New suite** `index/src/test/kotlin/dev/jdx/index/fault/` (all `@Tag("tier2")`,
   27 tests, green first run):
   - `FaultSupport.kt` — stream capture (restores `out`/`err` even on throw),
     trace-marker assertion (`\tat `, `at dev.jdx.`/`java.`/`kotlin.`/`org.`,
     `Exception in thread`, `Caused by:`), and `checkShow`/`checkMembers`
     three-law checkers (documented exit + problem named in *both* renderings +
     silence on both streams; JDK excluded so faults stay hermetic).
   - `TruncationFaultTest.kt` — generated 10 %-step sweep (10..90) of real
     fixture bytes through `show` + `members`: every cut exits 5 naming the
     class with `"code":5` in JSON; uncut control + empty-class edge included.
   - `ZipFaultTest.kt` — traversal entries unlisted/unopenable with entry-naming
     errors, no escape file under the temp dir, D-017 marker absent after a full
     hostile read, honest class in a hostile jar still exits 0; bombs proven at
     the guards (`readCapped` vs an infinite zero stream, declared/total/count
     caps, all naming artifact + "probable zip bomb"); corrupt zip exits 5.
   - `ArtifactShapeFaultTest.kt` — empty/resources-only/`X.class`-dir/
     module-info-only jars exit 1; corrupt target + future-version exit 5
     (reader-level `CORRUPT_CLASS`/`UNSUPPORTED_CLASS_VERSION` pinned too);
     corrupt neighbour still exits 0 (lazy reads); missing/deleted/unmatched-glob
     artifacts exit 5 naming the path; duplicate FQN exits 0 with `DUPLICATE_FQN`
     naming both jars (first wins); MR conflict serves the newest applicable
     variant (`Nesting` bytes win over `Generics` base) with
     `MULTI_RELEASE_VARIANT`; `-g:none` `NoDebug` exits 0 with `arg0`/`arg1`;
     mismatched sources pair by stem and answer from bytecode, foreign stems
     never pair (pins the degrade half of T-028).
3. **Deferred with pointers, not dropped:** decompiler timeout/crash faults attach
   under T-026/T-027 (no engine to fault yet); `SOURCES_VERSION_MISMATCH`
   detection is T-028. Both named in the suite KDoc and the TASKS.md notes.
4. **Verification:** new suite 27/27 green; `./gradlew check` green with
   `~/.config/jdx` shelved (tiers 1+2; tier-1 12.1 s of 30 s across 542 tests).
   `./gradlew soak` reds in `JavapCorpusSoakTest`: the seeded sample now reaches
   `$$` classes (compose/firebase jars) and the harness builds query refs for
   unnameable classes instead of skipping them — **reproduced on the
   stashed-clean tree** (pre-existing corpus drift, T-065 family), not mine.
5. **Not pushed** (D-012: no remote push without owner go-ahead in this session).

### Decisions made
- None (no new D-nnn). Fault-to-exit mapping follows D-015 as written:
  unreadable target → 5, nothing queryable → 1, degraded-but-answered → 0 +
  warning. Decompiler/sources deferrals reuse existing task IDs (T-026/T-027/T-028).

### Tasks moved
- T-057: TODO → WIP (`d2d9ed5`) → DONE (`f22fcbd`)

### Lessons distilled
- L-050 (prove the funnel, not the mountain). Shard `L-026-050.md` now full;
  opened `L-051-075.md` and updated the `LESSONS.md` index.

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx ~/.config/jdx.shelved  # T-066 ambient-workspace workaround, still open
./gradlew :index:tier2Test --tests "dev.jdx.index.fault.*"  # 27/27 green
./gradlew check                           # tiers 1+2 green (BUILD SUCCESSFUL)
mv ~/.config/jdx.shelved ~/.config/jdx
```

### What is broken / half-done
- T-066 (ambient `fx` workspace reds 2 `ReadCommandsTest`s) still open — shelve workaround stands.
- Soak: `JavapCorpusSoakTest` fails on `$$`-named classes from newer cache jars
  (e.g. `LambdaParameterVisitor$references_delegate$lambda$0$$inlined$…`,
  `Segment$Internal$$serializer`); proven stashed-clean. The harness should skip
  classes the model cannot name (fuel for T-065); filed here, not fixed (one task
  per session).
- Decompiler timeout/crash + `SOURCES_VERSION_MISMATCH` faults still unwritten —
  owned by T-026/T-027/T-028, which extend this suite.

### Open questions / blockers
None. Push needs owner go-ahead (D-012) — three commits
(`d2d9ed5`, `f22fcbd`, this docs commit) unpushed.

### Next action
**T-058** (metamorphic suite — lowest unblocked TODO) or **T-019** (Maven
coordinates). Next progress shard `sessions-031-040.md` opens after 4 more sessions.

## Session 27 — 2026-09-17 — Metamorphic test suite (T-058)
**Agent/Author:** Antigravity · **Commits:** `9c42bf7` (claim), this session's work

### Goal
Implement T-058, the lowest-numbered unblocked TODO (depends T-009 DONE): the metamorphic
test suite from TESTING.md §6, covering relations between answers over generated class
graphs in tier 1, all 35 fixture classes in tier 2, and a seeded real-jar corpus sample
in tier 3.

### What I did
1. Claimed T-058 (`TODO`→`WIP`, commit `9c42bf7`) before coding.
2. **`core/.../metamorphic/ResolverMetamorphicTest.kt`** (3 tier-1 properties, 1,000 cases each):
   - Relation 1: `members(T, --inherited) ⊇ members(T, --declared)` (declared methods and fields
     always survive into inherited resolution at depth 0).
   - Relation 2: `members(T, --inherited) ⊇ members(super(T), --inherited) minus private/overridden`
     (accessible supertype members are present directly or tracked via `overriddenTypes`/`hiddenTypes`
     when overridden or shadowed by a nearer declaration in BFS linearisation).
   - Relation 10 (resolver determinism): `resolve(T) twice ⟹ identical ResolvedMembers`.
3. **`index/.../metamorphic/MetamorphicTest.kt`** (tier 2 over all 35 fixture classes):
   - Relation 1: declared rows are a strict subset of inherited rows for every fixture class under
     both full access/synthetic and default flags.
   - Relation 2: inherited members include accessible superclass members minus overridden methods
     and shadowed fields.
   - Relation 6: exact FQN search returns exactly the queried type across all fixture classes.
   - Relation 7: `show` card member counts match declared members under all access & synthetic flags.
   - Relation 9: indexing the fixture jar twice into independent SQLite stores produces identical
     rows across all tables (`indexedAt` normalised to 0).
   - Relation 10: `show`, `members --inherited`, and `search` run twice ⟹ byte-identical stdout & JSON.
   - Catalog of deferred relations tracking M4/T-032 (`hierarchy`), M4/T-030 (`usages`),
     M4/T-033 (`callers`), and M3/T-022 (`body`).
4. **`index/.../metamorphic/MetamorphicCorpusSoakTest.kt`** (tier 3 soak):
   - Seeded random sampling across 25 real jars from `~/.gradle/caches`.
   - Skips unreadable classes (exit 5) and unnameable `$$` classes (T-065).
   - Verified Relation 1, Relation 6, Relation 7, Relation 9 (index determinism), Relation 10 (run-twice
     determinism): 0 failures across all sampled classes and jars (`BUILD SUCCESSFUL in 34s`).
5. Verified tier 1 (`./gradlew test`: 13.3s across 545 tests) and tier 2 (`./gradlew check`: green with
   shelve workaround for T-066).
6. **Not pushed** (D-012: no remote push without owner go-ahead in this session).

### Decisions made
- None (no new D-nnn). Relations follow TESTING.md §6 specification exactly. Deferred relations are
  explicitly catalogued and mapped to their planned milestones (M3/M4).

### Tasks moved
- T-058: TODO → WIP (`9c42bf7`) → DONE

### Lessons distilled
- L-051 (metamorphic inheritance relations must track collapse and timestamp non-determinism) in
  `docs/lessons/L-051-075.md`; tag index in `docs/LESSONS.md` updated.

### What works now (and how to verify it yourself)
```bash
./gradlew :core:test --tests "dev.jdx.core.metamorphic.*"       # tier-1 properties (3/3 green)
./gradlew :index:tier2Test --tests "dev.jdx.index.metamorphic.*" # tier-2 fixture suite (7/7 green)
./gradlew :index:soakTest --tests "dev.jdx.index.metamorphic.*"  # tier-3 corpus soak (green)
mv ~/.config/jdx ~/.config/jdx.shelved                          # T-066 ambient-workspace workaround
./gradlew check                                                 # tiers 1+2 green (BUILD SUCCESSFUL)
mv ~/.config/jdx.shelved ~/.config/jdx
```

### What is broken / half-done
- T-066 (ambient `fx` workspace reds 2 `ReadCommandsTest`s) still open — shelve workaround stands.
- Soak: `JavapCorpusSoakTest` pre-existing failure on `$$` classes from newer cache jars remains open
  under T-065; `MetamorphicCorpusSoakTest` avoids this by explicitly skipping unnameable classes.
- Deferred relations (hierarchy, usages, callers, body) await M3 and M4.

### Open questions / blockers
None. Push needs owner go-ahead (D-012).

### Next action
**T-019** (Maven coordinate resolution) — lowest unblocked TODO; or **T-059** (corpus soak harness) / **T-066** (make `ReadCommandsTest` hermetic). Next progress shard `sessions-031-040.md` opens after 3 more sessions.
