# Task board

**This file is the single source of truth for "what do I do next".**
`docs/PROGRESS.md` says what *happened*; this file says what *remains*.

---

## How to use this board (read before picking a task)

1. Open `docs/PROGRESS.md` and read the **CURRENT STATE** block at the top. It names the
   active milestone and, usually, the exact task to pick up.
2. Come here, find the **lowest-numbered task whose status is `TODO` and whose `Depends`
   are all `DONE`**. That is your task. If several qualify, prefer the lower number.
3. Set its status to `WIP` **and commit that change before you start coding**, so a
   concurrent contributor does not duplicate your work.
4. Do the task. Satisfy every line of its **Acceptance** list — they are the definition of
   done, not aspirations.
   **Plus the standing test bar (D-020, `docs/TESTING.md` §13):** tiers 1 and 2 green; tier 3
   green if you touched artifact reading, indexing or rendering; and the new behaviour is
   covered by at least one test family that *generates* cases — property, differential,
   metamorphic, fault-injection or corpus — not only hand-written examples. `core` is
   written **test-first**.
5. Set status to `DONE`, append a session entry to the newest `docs/progress/` shard,
   distill any new lessons into the newest `docs/lessons/` shard (D-023/D-024), update
   `docs/PROGRESS.md`'s CURRENT STATE, commit.
6. If you discover new work, **add a task here** rather than doing it silently. New tasks get
   the next free number, even if they belong to an earlier milestone.

**Status values:** `TODO` · `WIP` · `DONE` · `BLOCKED (reason)` · `DROPPED (reason)`

**If you are blocked by a question only the owner can answer:** mark the task
`BLOCKED (question)`, add the question to the **Open questions** section at the bottom of
this file *and* to `docs/PROGRESS.md`, then pick the next unblocked task. Do not guess.

**Milestones after the current one are deliberately coarse.** Expand the next milestone's
tasks into detail blocks when you start it — writing fine-grained tasks for M6 today would
be fiction.

---

## Status summary

| Milestone | Goal | Status |
|---|---|---|
| **M0** | Skeleton: build, launcher, `doctor`, `version`, **test spine** | WIP |
| **M1** | Read path: model, refs, `show`/`outline`/`members --inherited` | TODO |
| **M2** | Index: SQLite, `search`, workspaces, auto-discovery | TODO |
| **M3** | Bodies: sources, JavaParser, Vineflower, `body`/`source`/`doc` | TODO |
| **M4** | Graph: `usages`/`hierarchy`/`callers`/`calls`/`samples` | TODO |
| **M5** | Kotlin: `@Metadata` + PSI source parsing | TODO |
| **M6** | Serving: daemon, MCP, HTTP, `batch` | TODO |
| **M7** | Polish: token budgets, AppCDS, mutation gates, docs, install | TODO |

**T-001 through T-019, T-053 through T-071 are `DONE`.** M0's test spine is
complete; T-057…T-060 unblock as their milestones land. M1 (read path) is complete;
M2 hardening is complete (T-070 closed the tier-2 half of T-066).
**M3 has started: T-071 (`sources` `SourceRoot` access, first slice of T-020) is DONE;
T-021 (JavaParser body extraction over that seam) is DONE; T-022 (`jdx body`
over that seam) is DONE; T-023 (`jdx source` over that seam) is DONE.**

---

# M0 — Skeleton

Goal: a build that produces a runnable `jdx` binary that can report on its own environment.
Everything after this assumes it exists.

### T-001 — Gradle multi-project skeleton · `DONE` (session 2)
**Depends:** — · **Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `*/build.gradle.kts`

Create the Gradle wrapper and the module structure from CLAUDE.md §4: `core`, `index`,
`sources`, `decompile`, `cli`, `mcp`, `server`, `app`.

- Kotlin JVM plugin; **toolchain = Java 21** (build must not require the local JDK 26).
- A version catalog in `gradle/libs.versions.toml`. **Every** dependency version lives there
  — no inline versions in module build files, ever.
- Convention plugin or shared `subprojects {}` block for: Kotlin compiler options
  (`-Xjsr305=strict`, explicit API mode for `core`), JUnit 5 test wiring, and warnings-as-
  errors off for now (turn on in M7).
- Empty placeholder source sets so `./gradlew build` succeeds on a clean checkout.

**Acceptance**
- [ ] `./gradlew build` succeeds from a clean clone with no network beyond dependency download
- [ ] `./gradlew projects` lists all eight modules
- [ ] Wrapper is committed (`gradle-wrapper.jar`, `.properties`, `gradlew`, `gradlew.bat`)
- [ ] `gradle/libs.versions.toml` exists and is the only place versions appear
- [ ] Wrapper pins a Gradle version available in `~/.gradle/wrapper/dists` (9.6.1 or 9.7.1)
      **or** downloads cleanly; note which in the PROGRESS entry

**Notes for whoever does this:** `JAVA_HOME` is unset on the owner's machine and `mvn`/
`gradle` are not on `PATH`. The wrapper is the only supported entry point. Do not add a task
that shells out to a system `gradle`.

---

### T-002 — Dependency-free `core` module foundations · `DONE` (session 3)
**Depends:** T-001 · **Files:** `core/src/main/kotlin/dev/jdx/core/model/*`

The domain model. **`core` must not depend on ASM, SQLite, JavaParser, or any IO.** It is the
vocabulary every other module speaks, and it must stay unit-testable with no fixtures.

Model at minimum:
- `TypeName` (binary name, FQN, simple name, package, nesting, array/primitive handling)
- `JvmDescriptor` and `GenericSignature` value types (parse/print; parsing lives here because
  it is pure string work, not IO)
- `ClassInfo`, `MemberInfo` (field/method/ctor), `Access` flags, `TypeKind`
  (class/interface/enum/record/annotation/object/companion)
- `SymbolRef` sealed hierarchy: `TypeRef`, `MemberRef`, `PackageRef`, `ModuleRef`
- `Provenance` (artifact, origin ∈ {bytecode, sources, decompiled-*, jrt}, file, line range)
- `Warning` with a **closed set of codes** (start with `SOURCES_VERSION_MISMATCH`,
  `DUPLICATE_FQN`, `UNSUPPORTED_CLASS_VERSION`, `CORRUPT_CLASS`, `MULTI_RELEASE_VARIANT`)

**Acceptance**
- [ ] `core` has zero third-party runtime dependencies except the Kotlin stdlib
- [ ] Every public type has a KDoc line saying what it represents and who produces it
- [ ] Round-trip tests: descriptor → parsed → printed is a fixed point for all primitives,
      arrays, nested generics, wildcards, and type variables
- [ ] `Warning` codes are an enum, not strings

---

### T-003 — Symbol reference parser and printer · `DONE` (session 5)
**Depends:** T-002 · **Files:** `core/.../ref/SymbolRefParser.kt`, `SymbolRefPrinter.kt`

Implement PROPOSAL.md §6 exactly. Generous input, canonical output.

**Acceptance**
- [x] Every row of the "Accepted forms" table in PROPOSAL.md §6 parses to the right thing
- [x] Property test: `parse(print(ref)) == ref` for generated refs
- [x] Parse failures return a structured error naming the offending position, not an exception
- [x] Ambiguity is *representable* (a ref may be under-specified) — resolution is a later,
      separate concern and must not leak into the parser
- [x] `Map.Entry`, `Map$Entry`, and `java.util.Map$Entry` all normalise identically

*Disambiguation rules for the generous grammar are recorded as D-025 (package vs nesting
on `.`, `.`-as-member-separator, descriptor-vs-param-list, globs). Supporting model
change: `MemberSymbolRef.parameterTypes` is now `List<TypeName>?` (`null` = no parameter
list, `[]` = zero parameters).*

---

### T-004 — `app` module: fat jar + `jdx` launcher script · `DONE` (session 6)
**Depends:** T-001 · **Files:** `app/build.gradle.kts`, `app/src/main/scripts/jdx`, `install.sh`

- Shadow/fat jar of `cli` and its dependencies.
- A POSIX `sh` launcher that: resolves a JDK (`JAVA_HOME` → `java` on `PATH` →
  `/usr/lib/jvm/default`), errors clearly if the JDK is too old, passes through all args,
  and applies one-shot JVM flags (`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto`).
- `install.sh` symlinks into `~/.local/bin`. **Must not** require root, and must refuse to
  overwrite an existing unrelated `jdx` without `--force`.

**Acceptance**
- [x] `./gradlew :app:installDist` (or equivalent) produces a working `./build/jdx`
- [x] Launcher works with `JAVA_HOME` unset (the owner's machine)
- [x] Launcher gives a human error, not a stack trace, on a missing/old JDK
- [x] `--version` round-trips through the launcher

*Implementation notes: fat jar is a hand-rolled `Jar` over the runtime classpath (no shadow
plugin — see the build-file comment for the revisit trigger); launcher-level failures exit
6 per D-026; cli was switched from clikt to clikt-core (the mordant flavor eagerly loads
JNA — JDK 22+ native-access warnings on stderr); tests are stub-JDK fault injection +
generated version-gate properties + install.sh suite + two real-JVM e2e tests.*

---

### T-005 — `jdx version` and `jdx doctor` · `DONE` (session 7)
**Depends:** T-004 · **Files:** `cli/.../commands/VersionCommand.kt`, `DoctorCommand.kt`

`doctor` is the project's self-diagnosis and the first thing a confused contributor or agent
should run. Report, each with OK/WARN/FAIL:
JDK version + path + whether `jrt-fs` is reachable; `javap` presence and version; cache dir
path, existence, writability, size; config dir; index DB presence, schema version, artifact
count; whether the Kotlin source module is installed (D-008); whether a daemon is running;
detected project/workspace for the CWD.

**Acceptance**
- [x] `jdx doctor` and `jdx doctor --json` both work and carry the same information
- [x] Exit code `0` when all OK, `6` when any FAIL
- [x] No check ever throws; every failure becomes a FAIL row with a readable reason
- [x] Output fits in ~25 lines — it is read by agents too

*Implementation notes: `DoctorService` (injectable `DoctorEnvironment`: paths, process
runner, runtime probe) + `DoctorReport` model shared by a text and a JSON renderer
(`cli/.../render/JsonEnvelope.kt`, the T-010 seed; minimal `{"jdx":1,ok,command,result}`
envelope). Severity policy and `--json`-in-both-positions are D-027. `--version` eager flag
kept; `jdx version` subcommand added. Test bar: example tests + an exhaustive 576-combination
environment fault-injection family (never-throws, exit-code law, text↔JSON parity,
run-twice determinism) + a generated `parseToolVersion` property + 3 real-JVM e2e tests.*

---

### T-006 — Fixture corpus · `DONE` (session 8)
**Depends:** T-001 · **Files:** `testfixtures/`, `core/src/test/kotlin/.../Fixtures.kt`

A `testfixtures` source set of deliberately nasty Java and Kotlin classes, compiled by Gradle
at test time into **both** a binary jar and a sources jar. Nine of the ten test families in
`docs/TESTING.md` lean on this, so it is built early and built well. See TESTING.md §11 for
the required contents.

Must cover: bounded/wildcard/recursive generics · bridge methods from covariant overrides ·
inner, static-nested, anonymous and local classes · records · sealed hierarchies · enums with
constant bodies · repeatable and array-valued annotations · varargs · `synchronized`/`native`
/`strictfp` · `@Deprecated(forRemoval=true)` · package-private and private members · a class
compiled `-g:none` · **a class whose static initialiser writes a marker file** (proves D-017:
the marker must never appear) · Kotlin: `suspend`, extension functions, default arguments,
`data`/`sealed`/`value`/`object`/companion, `@JvmName`, `@JvmStatic`, nullable types,
properties with custom accessors, typealiases, `inline`/`reified`.

**Acceptance**
- [x] `./gradlew :testfixtures:jar :testfixtures:sourcesJar` produces both jars
- [x] Builds are **byte-deterministic** across repeat runs (otherwise golden tests go flaky)
- [x] A `Fixtures` helper resolves both jar paths with no hard-coded absolute paths
- [x] Fixtures are **self-describing**: an `@ExpectedMembers({"public int add(int,int)", …})`
      annotation carries the expected truth, so adding a fixture adds coverage automatically
      (TESTING.md §5.2)
- [x] The static-initialiser marker fixture exists and a test asserts the marker is absent
- [x] `docs/TESTING.md` §11 and the README document how to add a fixture

---

### T-053 — Test tier infrastructure · `DONE` (session 9)
**Depends:** T-001 · **Files:** convention plugin / `build.gradle.kts`
*(Added in session 1 after D-020. Numbered T-053 per the board rule: new tasks take the next
free number even when they belong to an earlier milestone.)*

Wire the four tiers from `docs/TESTING.md` §2 so contributors can't accidentally put a slow
test in the fast loop.

- `test` — tier 1, **budget < 30 s**, fails the build if exceeded (report the slowest 10)
- `check` — tier 2, adds property, golden, fault-injection and parity suites, **< 3 min**
- `soak` — tier 3, JUnit-tagged, **excluded from `check`**, `-Pcorpus=<dir>` overridable
- `bench`, `mutationTest` — tier 4, on demand
- `testReport` — aggregated HTML across modules

**Acceptance**
- [x] `./gradlew check` passes on a machine with **no** local jar corpus
- [x] A test tagged `soak` cannot run in tier 1 or 2 (prove with a deliberate test)
- [x] Tier-1 duration is printed at the end of the run, with the slowest tests named
- [x] All four commands in TESTING.md §13 exist and do what that section says

*Implementation notes: tags are the mechanism (`tier2`/`soak`/`bench`; TESTING.md §2 table).
Per-module `tier2Test`/`soakTest`/`benchTest` `Test` tasks with `includeTags`; `test`
excludes all three plus `tier2`; `check` depends on `tier2Test`; root `soak`/`bench`/
`mutationTest`/`testReport` commands (bench/mutationTest are wired entry points whose
suites land in T-050/T-060). `verifyTier1Budget` finalises every `test` task: prints the
total and 10 slowest tests, fails over 30 s (`-Ptier1.budget` overrides). `FixtureCorpusTest`
and `LauncherScriptTest` moved to tier 2 (tier 1: 26 s → 2.6 s). `SoakExclusionProofTest`
asserts `-Djdx.tier=soak`, set only by `soakTest` — broken exclusion fails instead of
slowing the loop.*

---

### T-054 — Golden-file test infrastructure · `DONE` (session 17)
**Depends:** T-053 · **Files:** `testfixtures/src/testFixtures/.../golden/*`, `testfixtures/src/test/.../golden/*`

Helper comparing output to files under `src/test/resources/golden/`, rewritten by
`-Pgolden.update=true`.

**Acceptance**
- [x] Failure output is a readable **unified diff**, not two blobs
- [x] Update mode prints a summary of every file it rewrote, so a reviewer sees the blast
      radius before committing (TESTING.md §14)
- [x] Golden files are plain text, committed, and readable in a PR diff
- [x] An orphaned golden file (no test references it) fails the build

*Implementation notes (session 17): shared `GoldenFiles` + `UnifiedDiff`
(`testfixtures/src/testFixtures/.../testsupport/golden/`, via the
`java-test-fixtures` plugin — `src/main` would pollute the corpus scans, and a
9th module would break the T-001 eight-module contract). `UnifiedDiff` is a
dependency-free LCS renderer (`---`/`+++`/`@@` hunks, 3 context lines, 200-line
cap with omission trailer, GNU `-0,0` empty-side form, deletions before
insertions). `GoldenFiles.verifyAll` does compare-or-rewrite plus the orphan
check and prints one summary line per rewritten file. `-Pgolden.update` is now
wired once in the root build for every module's `tier2Test` (with
`showStandardStreams` in update mode so the summary reaches the console),
replacing the per-module blocks. `index`/`cli` golden suites migrated to the
helper; their 280 goldens rewrite byte-identically (zero git diff), proving no
behaviour change. Tests: 9 tier-1 (`UnifiedDiffTest`: 7 pinned + 2
thousand-case properties — empty-iff-equal, header/balance laws) + 7 tier-2
(`GoldenFilesTest` over `@TempDir`: mismatch/missing/orphan failures,
update-then-verify round-trip, property-driven mode). Gotcha recorded as
L-029: the plugin's jar lands in `build/libs` and trips the "exactly one
fixture jar" assertions — redirected to `build/test-fixtures-libs`. Remaining
duplication (`fixtureBinaryJar`/`fixtureClassNames` triplicated) filed as
T-063.*

---

### T-055 — Property-based test infrastructure and shared generators · `DONE` (session 21)
**Depends:** T-053, T-002 · **Files:** `core/src/test/kotlin/.../gen/*`

kotest-property wired in, with shared `Arb` generators for `TypeName`, `JvmDescriptor`,
`GenericSignature`, `ClassInfo` graphs (including **cyclic** ones), and member sets. Writing
these once pays for itself across every later property.

**Acceptance**
- [x] ≥ 1,000 cases per property in tier 1 without breaking the 30 s budget
- [x] The failing **seed is printed** and there is a documented one-line way to pin it as a
      regression test
- [x] Generators produce genuinely nasty values: nested generics, wildcards, type variables,
      arrays of arrays, `$` in identifiers, unicode identifiers, empty packages
- [x] The properties listed in TESTING.md §4 exist (or have tasks) — none silently dropped

*Implementation notes (session 21): new `gen/` files — `TypeNameGenerators`
(`arbTypeName`/`arbFieldTypeName`/`arbJvmDescriptor`, empty packages, unicode segments,
3-deep nesting whose binary names carry `$`, 1–3-dim arrays, void excluded from array
elements after the properties caught `[[[V]`), `SignatureGenerators` (all three wildcard
kinds, type variables incl. empty-class-bound params, nested generics, inner classes with
own args, arrays, void returns, throws; depth-0 base is non-recursive — eager Arb
construction overflows otherwise, L-036), `PropertySupport` (`JDX_PROPERTY_ITERATIONS`,
`pinnedConfig(seed)` + the seed workflow KDoc). New properties, all 1,000 cases:
`TypeNamePropertyTest` (binaryName + field-descriptor fixed points),
`JvmDescriptorPropertyTest` (descriptor fixed point + never-throws),
`GenericSignaturePropertyTest` (parse + parseClass fixed points + never-throws),
`print(parse(s))` stability in `SymbolRefPropertyTest`; `GeneratorsTest` pins the
nastiness coverage over fixed seeds + the `pinnedConfig` API. The three
`MemberListingPropertyTest` 500-case properties raised to 1,000. Real production bug
found and fixed: `JvmDescriptor.parse` threw `IllegalArgumentException` on malformed
`L...;` payloads instead of returning null (`parseDescriptorType` now catches it, L-037;
regression strings pinned in `JvmDescriptorTest`). TESTING.md §4 documents the pin
one-liner. Tier-1 aggregate 7.7 s → 18.0 s across 469 tests (still inside 30 s, but
headroom is now 12 s — the next property-adding task watches the slowest list).
Check green (586 tests) only with ambient `~/.config/jdx` shelved: 2 pre-existing
`ReadCommandsTest` failures come from the real-home `FileWorkspaceStore` default + the
`fx` active workspace on this machine — filed as T-066 (L-038), not fixed here.*

---

### T-056 — `javap` differential harness · `DONE` (session 23)
**Depends:** T-006, T-008 · **Files:** `index/src/test/kotlin/.../differential/*`

**The highest-value test in the project** (TESTING.md §5.1). Parse `javap -p -s` output into
a member set and diff it against `jdx members --declared --access all --include-synthetic
--json` for every fixture class and a sampled slice of the real corpus.

**Acceptance**
- [x] Runs over the whole fixture corpus in tier 2
- [x] Runs over a seeded random sample of the local jar corpus in tier 3
- [x] Disagreements report **which member and which field differs**, not just "sets differ"
- [x] Skips gracefully with a clear message when `javap` is absent (don't fail the build)
- [x] Known, documented `javap` quirks live in one allowlist file with a comment each —
      an unexplained entry in that file is a review blocker

*Implementation notes (session 23): `index/.../differential/` — `Javap.kt`
(find/run/parse, shared: the T-008 reader differential now delegates to it, its
7 tests green unmodified), `JavapQuirks.kt` (single allowlist; one entry:
`<clinit>` — `javap` reports `static {};`, the resolver never lists it by
design), `ServiceDifferential.kt` (one-class compare through `JdxService` with
`--declared` + access-all + `--include-synthetic` + limit 1M: presence,
per-base overload counts incl. bridges, `--json` ref carriage, truncation
refused). Comparison is on canonical refs with `:return`-suffix prefix
matching (refs erase return/field types by design; descriptor fidelity stays
with the reader-level check — said in the KDoc). Tier 2
(`JavapDifferentialTest`) covers all 35 fixture classes; tier 3
(`JavapCorpusSoakTest`, `@Tag("soak")`, seed 20260917, `-Djdx.soakSeed`
override, `-Pcorpus` via `jdx.corpusDir`) samples 30 jars × 4 classes;
exit-5/corrupt classes are counted skips, anything else fails with the
per-member report. The first soak run found a real production bug (exit 6 on
androidx jars: kotlinc `$`-nested supertype edges in generic signatures —
fixed test-first in `core`, see `MemberResolverTest` + L-042) and a sampler
bug of mine (raw zip entries bypass multi-release selection — fixed via
`ArtifactLoader`, L-043). First green soak: 104 classes, 0 skips, 0 failures
in 51 s.*

---

### T-057 — Fault-injection suite · `DONE` (session 26)
**Depends:** T-007 · **Files:** `index/src/test/kotlin/.../fault/*`

Generate malformed inputs programmatically rather than collecting them by hand. Full list in
TESTING.md §7.

**Acceptance**
- [x] Every case in TESTING.md §7 has a test
- [x] Each asserts **both** a documented exit code (D-015) **and** that the output names the
      problem
- [x] **No stack trace ever reaches stdout or stderr** — asserted, for every case
- [x] Zip-slip and zip-bomb cases prove the D-017 defences, not just that nothing crashed
- [x] Truncation cases are generated (cut at every 10 % boundary), not hand-picked

*Implementation notes (session 26): `index/.../fault/` — `FaultSupport` (stream
capture, trace-marker assertion, show/members three-law checkers) + `TruncationFaultTest`
(generated 10 %-step sweep through `JdxService.show`/`members`: every cut exits 5
naming the class, the uncut control exits 0) + `ZipFaultTest` (traversal entries
unlisted/unopenable, no escape file under the temp dir, D-017 marker absent after a
full hostile read, honest class in a hostile jar still exits 0; bombs proven at the
guards every production path funnels through — infinite stream vs `readCapped`,
declared/total/count caps, all naming artifact + "probable zip bomb"; corrupt zip
exits 5) + `ArtifactShapeFaultTest` (empty/resources-only/`X.class`-dir/module-info-only
jars exit 1; corrupt target and future-version exit 5; corrupt neighbour still exits 0;
missing/deleted/unmatched-glob artifacts exit 5 naming the path; duplicate FQN exits 0
with `DUPLICATE_FQN` naming both jars; MR conflict serves the newest applicable variant
with `MULTI_RELEASE_VARIANT`; `-g:none` `NoDebug` exits 0 with `arg0`/`arg1`; mismatched
sources still pair by stem and answer from bytecode, foreign stems never pair — pinning
the degrade half of T-028). Deferred with pointers, not dropped: decompiler
timeout/crash faults attach under T-026/T-027 (no engine to fault yet),
`SOURCES_VERSION_MISMATCH` detection is T-028. 27 tests, all `@Tag("tier2")`.
Soak note: `JavapCorpusSoakTest` reds on `$$` classes from corpus drift (compose/
firebase jars) — reproduced stashed-clean, T-065 family; the harness builds query
refs for unnameable classes instead of skipping them.*

---

### T-058 — Metamorphic test suite · `DONE` (session 27)
**Depends:** T-009 · **Files:** `core/src/test/kotlin/.../metamorphic/*`, `index/src/test/kotlin/.../metamorphic/*`

Relations that must hold between answers (TESTING.md §6). These catch the deep resolver and
index bugs that example-based tests never reach. Start with the resolver relations; extend as
`hierarchy`/`usages`/`callers` land in M4.

**Acceptance**
- [x] Every relation listed in TESTING.md §6 is implemented or has a task naming the
      milestone that will add it
- [x] Determinism relations (`run twice ⟹ identical bytes`, `index twice ⟹ identical rows`)
      are included — they protect the D-007 promise
- [x] Runs over the fixture corpus in tier 2 and a corpus sample in tier 3

*Implementation notes (session 27): `core/.../metamorphic/ResolverMetamorphicTest` (3
tier-1 properties, 1,000 cases each: declared ⊆ inherited, superclass members minus
private/overridden accounting for override collapse and field hiding in
`overriddenTypes`/`hiddenTypes`, run-twice determinism) + `index/.../metamorphic/MetamorphicTest`
(tier 2 over all 35 fixture classes: declared ⊆ inherited with full/default access,
superclass members minus private/overridden, search exact FQN finds T, show counts ==
declared counts, index fixture jar twice into independent SQLite stores ⟹ byte-identical
rows with `indexedAt` normalised, show/members/search run twice ⟹ byte-identical
stdout/JSON, tracked catalog for deferred relations M4/T-032, M4/T-030, M4/T-033,
M3/T-022) + `index/.../metamorphic/MetamorphicCorpusSoakTest` (tier 3 soak over seeded
sample from `~/.gradle/caches`, skipping unnameable `$$` names per T-065 and exit 5
unreadable entries; verified zero failures across 25 sampled jars). All tiers green.*

### T-061 — Shard the growing docs; add the lessons log (D-023, D-024) · `DONE` (session 4)
**Depends:** — · **Files:** `docs/PROGRESS.md`, `docs/progress/*`, `docs/DECISIONS.md`,
`docs/decisions/*`, `docs/LESSONS.md`, `docs/lessons/*`, `CLAUDE.md`, `CONTRIBUTING.md`

*(Owner-directed, added in session 4.)* Append-only logs are split into shard files
behind small entry files (index + rules), so agents read only what they need. Session
shards hold 10 sessions; lessons and decisions shards hold 25 entries each.

**Acceptance**
- [x] `docs/PROGRESS.md` holds only CURRENT STATE + shard index + template
- [x] Sessions 1–4 live in `docs/progress/sessions-001-010.md`; 1–3 byte-identical to
      the pre-shard file
- [x] `docs/LESSONS.md` exists, seeded with L-001…L-009 distilled from sessions 1–3
- [x] `docs/DECISIONS.md` is an index; D-001…D-024 live in `docs/decisions/D-001-025.md`
- [x] All cross-references (CLAUDE.md, CONTRIBUTING.md, this file) point to the new
      structure

---

# M1 — Read path

Goal: `jdx show` / `outline` / `members --inherited` answer correctly from real jars, with no
index and no sources. This milestone alone already beats `javap` for the agent's main loop.

### T-007 — Artifact loading and sources pairing · `DONE` (session 10)
**Depends:** T-002 · **Files:** `index/.../artifact/*`

Open jars, class dirs, and `jrt:/`. Implement the five sources-pairing rules from
PROPOSAL.md §5.2. Content-hash artifacts. Harden against zip-slip and zip bombs (D-017):
normalise entry names, reject traversal, cap decompressed size and entry count.

**Acceptance**
- [x] Reads a jar, a class directory, and the running JDK's `jrt:/` uniformly through one
      interface
- [x] Finds `gson-2.14.0-sources.jar` from `gson-2.14.0.jar` via the Gradle cache layout
- [x] Multi-release jars: picks the right variant for the running JDK and emits
      `MULTI_RELEASE_VARIANT`
- [x] A crafted zip-slip entry is rejected with a test proving it
- [x] Hash of an unchanged jar is stable across runs and processes

*Implementation notes (session 10): uniform `ArtifactRoot` interface (`classEntryPaths`,
`openClass`, `stableId`, `warnings`) with `JarArtifact`/`DirArtifact`/`JrtArtifact` behind
the `ArtifactLoader` dispatch; `ZipSafety` (normalisation + entry/total/per-read caps),
`ArtifactHash` (SHA-256/128 streaming file + deterministic dir hash), `MultiRelease`
(pure selector, manifest-gated), `SourcesPairing` (sealed `External`/`Embedded`/`Absent`;
explicit override wins as the word "override" demands; rules 2+3 share one stem-locked
version-dir scan so flat lib dirs never cross-pair). JRT strips module prefixes and maps
them via `moduleForClass` (first module wins + one `DUPLICATE_FQN` for split packages).
Tests: 28 tier-1 (incl. a 1,000-case normalisation property) + 21 tier-2 (crafted MR,
zip-slip, cache-layout, real-cache opportunistic, full-jar D-017 marker proof). The
`gson` acceptance is covered two ways: a fabricated Gradle-layout test (always) and an
opportunistic real-cache test (aborts, never fails, without the cache).*

---

### T-008 — ASM class reader → `ClassInfo`/`MemberInfo` · `DONE` (session 11)
**Depends:** T-007, T-002 · **Files:** `index/.../asm/*`

`ClassReader` with `SKIP_FRAMES` (**not** `SKIP_DEBUG` — parameter names live there).
Extract access, kind, generic signature, supertypes, interfaces, annotations, nesting,
deprecation, source-file name, members with descriptors/signatures/throws/annotations/
default values, and parameter names from `MethodParameters` then `LocalVariableTable`.

**Acceptance**
- [x] **Differential test vs `javap -p -s`**: for every fixture class, the member set and
      descriptors match exactly. This is the correctness oracle — make it a real test, not a
      manual check.
- [x] Class-file major versions up to the running JDK parse; newer ones emit
      `UNSUPPORTED_CLASS_VERSION` instead of throwing
- [x] A corrupt/truncated class file yields `CORRUPT_CLASS` and does not abort a whole jar
- [x] No class from an inspected jar is ever loaded into the JVM (D-017) — assert by running
      with a `SecurityManager`-free check that the fixture's static initialiser side-effect
      file was never created

*Implementation notes (session 11): `AsmClassReader` (`index/.../asm/`) returns a sealed
`ClassReadResult` (`Ok`/`UnsupportedVersion`/`Corrupt` — errors are values, never throws);
major-version pre-check against `Runtime.version().feature() + 44` plus an ASM-lag catch
for majors ASM itself rejects. Param names: `MethodParameters` when sizes match, else LVT
slot walk (long/double take two), else `null`s. Annotation/constant values rendered
deterministically (`"s"`, `Fqn.class`, `E.A`, `{1, 2}`, `@Fqn(k=v)`). Core model gained
three defaulted slots: `ClassInfo.deprecated`, `MethodInfo.annotationDefault`,
`FieldInfo.constantValue` (test-first, `MemberDefaultsTest`). Tests: 14 tier-1
(in-memory ASM-built classes via `AsmTestClasses`) + 7 tier-2 (javap differential over
every fixture class, whole-jar read, corrupt-neighbour isolation, D-017 marker proof,
JRT `Object`/`HashMap` smoke on major-70 JDK-26 bytes, flag spot-checks).*

---

### T-009 — Member resolution with inheritance and generic substitution · `DONE` (session 12)
**Depends:** T-008 · **Files:** `core/.../resolve/MemberResolver.kt`

Implement PROPOSAL.md §9.3 step by step. This is **the highest-value algorithm in the
project** — it is what saves an agent from walking hierarchies by hand. Treat it accordingly:
heavily tested, heavily commented, no cleverness.

**Acceptance**
- [x] Linearisation is cycle-safe and deterministic
- [x] `class StringList extends ArrayList<String>` reports `boolean add(String)`, not `add(E)`
- [x] Overridden members collapse to the nearest declaration, with the overridden type
      recorded as metadata
- [x] JLS visibility respected from the querying perspective (private supertype members
      excluded; package-private only when same package)
- [x] Bridge/synthetic hidden by default, shown with `--include-synthetic`
- [x] Spot-check against IntelliJ: `java.util.HashMap` inherited completion list matches

*Implementation notes (session 12): `MemberResolver` (`core/.../resolve/`) — BFS
linearisation (superclass then interfaces, first-visit-wins, Object moved last),
transitive generic environments with raw-erasure and method-type-param shadowing,
override collapse by name+erased-descriptor (fields by name), JLS visibility from the
target's package, synthetic/bridge filtering, ctors never inherited, `<clinit>` never
listed, missing supertypes skipped+reported. Enabling fix: `GenericSignature.parseClass`
(a lone-superclass class Signature is a ClassSignature by construction — `parse` reads
it as a field, L-024) + `AsmClassReader` uses it. Tests: 16 tier-1 examples, 6
thousand-case properties (determinism, declared-superset, no-private-leak,
single-collapse, synthetic-monotonicity, cycle-termination), 4 parser + 1 reader
tier-1 tests, 4 tier-2 JRT tests over real `java.util.HashMap` (Map-API spot-check,
determinism, no-leak, concrete `StringMap` substitution).*

---

### T-010 — Text and JSON renderers · `DONE` (session 13)
**Depends:** T-009 · **Files:** `core/.../render/*`

Two renderers over one result model, per PROPOSAL.md §8. Text follows the layout in D-007.
JSON follows the `{"jdx":1, ok, command, query, result, truncated, warnings, provenance}`
envelope.

**Acceptance**
- [x] Anything present in text output is also present in JSON (no text-only information)
- [x] Deterministic: identical bytes for identical input, sorted, no absolute paths, no
      timestamps, no hashes in default output
- [x] ANSI only when `stdout` is a TTY; piping yields plain text
- [x] Truncation never cuts mid-entity and always reports `shown`/`total`/`hint`
- [x] Golden tests for both renderers on every fixture

*Implementation notes (session 13): `core/.../render/` — `Truncation`
(entity-preserving cut + `shown`/`total`/`hint`), `SignatureLines` (one-line
FQN signatures: generics, varargs `...`, `argN` fallback, throws, defaults,
const values), `Listing` (`MemberListing` grouped by declaring type in
linearisation order, kind-then-name sort, Object collapsed to one pinned
summary line, `declaredOnly` outline mode, `:return` ref disambiguation only
for bridge siblings), `Envelope` (hand-rolled JSON — core stays
dependency-free; outside-checked by a cli test parsing with
kotlinx.serialization), `Errors` (exit-1 not-found + did-you-mean, exit-2
ambiguous with candidates, both renderers), `Ansi` (color flag, bold headers;
adapters pass TTY-ness in). New `UNRESOLVED_SUPERTYPE` warning for missing
hierarchy edges (PROPOSAL §16 list updated). Layout choices recorded as D-028
(FQN over Appendix-A simple names, per-group canonical reading, golden
hermeticity rule). Tests: 52 tier-1 (examples + 5 thousand-case properties
reusing the T-009 graph generators: determinism, truncation law, text⊆JSON,
prefix preservation, no-escapes-or-paths) + 3 cli tier-1 JSON-validity tests +
1 tier-2 golden test over all 35 fixture classes (70 files, orphan check,
`-Pgolden.update=true` rewrite wired in `index/build.gradle.kts` — seed of
T-054). `WarningCode` closed-set test + PROPOSAL §16 updated for the new code.*

---

### T-011 — `jdx show`, `jdx outline`, `jdx members` · `DONE` (session 14)
**Depends:** T-010, T-003 · **Files:** `cli/.../commands/*`

Wire the three read commands through Clikt. **No logic in the command classes** (D-004) —
they parse flags, call `JdxService`, hand the result to a renderer, map to an exit code.

**Acceptance**
- [x] All flags from PROPOSAL.md §7.1 for these commands are implemented or explicitly
      rejected with a "not yet implemented" message naming the task that will add them
      (`--with-doc` → T-025, `--sort name|declaring` → T-062)
- [x] `--inherited` is the default for `members`; `java.lang.Object` members collapse to one
      line by default (and `--from java.lang.Object` expands them — L-027)
- [x] Exit codes follow D-015; ambiguity follows D-016 with candidate listing
- [x] `jdx members java.util.HashMap` works with **no** flags, via the JDK jrt root
- [x] Golden tests for each command, text and JSON (210 files over all 35 fixture classes)

*Implementation notes (session 14): `index/.../service/JdxService` (D-004 facade:
classpath-ordered roots, exact/short-name lookup, did-you-mean, DUPLICATE_FQN,
lazy per-class ASM reads, all §7.1 filters, exit 1–6 outcomes) + `core/.../render/
ClassCard` (show card) + `ErrorResult.Generic` (exit 3/4/5/6 text+JSON) + three thin
Clikt commands (`--jars` repeatable, `--no-jdk`, `--json` both positions,
`--no-color`, explicit `exitProcess` — clikt-core's hook is a no-op, L-026).
Tests: 20 tier-1 command tests (injected query+exit), 6 index filter tests + 1 new
monotonicity property, 15 tier-2 behavioural tests (JDK zero-config, outline ==
members --declared, determinism, D-017 marker, failure exits) + 3×70 goldens
(hermetic: --no-jdk, fixed artifact label). Real bug caught: --from Object vs
collapse (L-027). Known cosmetic: annotation cards print `extends Object`, faithful
to the model (T-008 normalises only interfaces).*

---

### T-062 — Member sort orders (`--sort name|declaring`) · `DONE` (session 31)
**Depends:** T-011 · **Files:** `core/.../render/Listing.kt`, `index/.../service/JdxService.kt`,
`cli/.../commands/ReadCommands.kt`, `ReadCommandSupport.kt`

`members`/`outline` row ordering beyond the default: `--sort name` (flat
name-first order across kinds/groups) and `--sort declaring` (declaring-type
order semantics where they differ from linearisation). Decide the exact layouts,
extend `MemberListingOptions`, and pin both with goldens.

**Acceptance**
- [x] `--sort name|kind|declaring` all accepted by `members` and `outline`
- [x] Text and JSON agree on the order; determinism property holds per order
- [x] Golden files cover all three orders on at least one generic, one nested
      and one Kotlin fixture class

*Implementation notes (session 31): layouts decided as D-033 — `kind` byte-
identical to the T-010 default (linearisation-major, kind-minor, signature-then-
ref); `declaring` alphabetical by declaring binary name, kind-minor, same rows;
`name` strictly flat (global member-name → signature → ref → declaring → depth
order, run-length groups so headers stay truthful and may repeat). `MemberRow`
gained `memberName` (raw name, `<init>` as-is; absent from JSON — the ref already
carries it, D-007 unchanged). Plumbing: `JdxService.MemberFilters.sort` (default
`KIND`) so the thin-adapter `MemberQuery` arity stays stable (D-004); CLI maps
`--sort` via `ReadCommandSupport.sortOf` into filters for both commands. Tests:
6 core examples (`MemberSortTest`) + 2 consolidated thousand-case properties
(shared-law per random sort; layout laws over all three sorts — one `checkAll`
row covers all orders instead of tripling the loops, keeping the 1,000-case
minimum inside the 30 s tier-1 budget at 23.6 s) + CLI tier-1 sort-plumbing tests
+ tier-2 `SortOrdersGoldenTest` (18 files: Generics, TrafficLight$1, KotlinMembers
× 3 sorts × text/JSON; `kind` byte-identical to the existing default goldens) +
tier-2 service tests (every sort exit 0, deterministic, same row set, outline
covered). `check` green (shelved `~/.config/jdx`, T-066); soak green except the
pre-existing stashed-clean `JavapCorpusSoakTest` `$$` red (T-065).*

---

### T-012 — JDK stdlib root via `jrt-fs` + `src.zip` · `DONE` (session 15)
**Depends:** T-007 · **Files:** `index/.../artifact/JdkLayout.kt`, `JrtArtifact.kt`, `ArtifactLoader.kt`, `cli/.../service/DoctorService.kt`

Expose the running JDK's modules as a root, paired with `$JAVA_HOME/lib/src.zip` as its
sources. Appended to every workspace unless `--no-jdk` (D-006).

**Acceptance**
- [x] `jdx show java.util.HashMap` works with zero configuration
- [x] JDK source is found when `src.zip` is present, absent gracefully when it is not
      (some distributions omit it) — emit a WARN row in `doctor`, not an error
- [x] Module name is reported as the artifact (e.g. `java.base`)

*Implementation notes (session 15): the `jrt:/` root, zero-config reads and the module
label already existed from T-007/T-011 — this task closed the remaining gap, `src.zip`
discovery. New `JdkLayout` (`index/.../artifact/`): `findSrcZip(javaHome, envJavaHome)`
tries `java.home/lib/src.zip` then `$JAVA_HOME/lib/src.zip` (first file wins, `java.home`
wins ties; never throws), shared by `JrtArtifact.jdkSources` and the `doctor`
`jdk-sources` row so the two can never disagree. `ArtifactLoader.openJdk` and
`DoctorEnvironment.system()` both default the env home from the live `$JAVA_HOME`;
`DoctorEnvironment` gained `javaHomeEnv` (default `null`, so existing callers compile).
Tests: 8 tier-1 `JdkLayoutTest` (7 examples over fabricated homes + one 500-case
property: result is null or a real file under a searched home, java-home-first,
never throws) + 3 tier-2 `ArtifactLoaderTest` pairing tests (fabricated-home,
env-fallback, neither) + 3 `DoctorServiceTest` tests (env-fallback OK, java-home
precedence, absent WARN naming locations) + 1 tier-2 JSON test pinning
`provenance[0].artifact == "java.base"` for `show java.util.HashMap`.*

---

# M2 — Index

### T-013 — `IndexStore` interface + SQLite implementation · `DONE` (session 18)
**Depends:** T-008 · Schema in PROPOSAL.md §10.3. WAL mode, schema versioning with a
migration path, artifact-scoped rows (D-013). **Keep SQLite behind the interface** — no SQL
outside the implementation package.

*Implementation notes (session 18): `index/.../store/IndexStore.kt` (interface over
`ClassInfo` + content hashes — `upsertArtifact`/`findArtifactByHash`/`listArtifacts`/
`deleteArtifactByHash`/`replaceClasses`/`findClassesByFqn`→`ClassHit`/`loadClass`/
`listClassFqns`/`classCount`, plus `StoredArtifact.needsReindex` and
`SqliteSchemaVersion.CURRENT = 1`) + `index/.../store/sqlite/SqliteIndexStore.kt`
(the only file containing SQL: WAL + `busy_timeout`, `PRAGMA user_version` migration
chain that refuses newer files by name, full §10.3 tables + indices, one-transaction
per-artifact replacement with batched prepared statements). Deliberate v1 schema
extensions, documented on the class: `class.fqn` holds the binary name (`$`-joined,
the key the live reader matches on), `super_fqn`/`outer_fqn` denormalised text
(`super_id`/`outer_id` stay NULL until T-014 can link cross-artifact edges),
`member.constant_value` beside `default_value`, declaration order via `ORDER BY id`;
signatures/descriptors stored as text and re-parsed (`parseClass` for classes, L-024),
param names as a hand-rolled JSON array (nulls significant), annotation maps as
sorted-key JSON objects. Tests (all `@Tag("tier2")`, 16): `IndexStoreTest` (12 —
every fixture class round-trips exactly via ASM, hostile unicode/quote/newline
values, per-artifact scoping, hash-ordered duplicates, delete cascade, reopen
persistence), `IndexStorePropertyTest` (500-case store→load fixed point +
200-case store-twice determinism over generated nasty classes), `sqlite/
SqliteContractTest` (WAL PRAGMA, newer-schema refusal naming both versions —
the only tests allowed raw SQL, said in their KDoc). Next: T-014 builds the
parallel indexer on `upsert → replaceClasses`; `DoctorService.indexCheck` still
reports presence-only until then.*

### T-014 — Parallel indexer · `DONE` (session 19)
**Depends:** T-013 · Virtual-thread fan-out across artifacts, batched transactions,
content-hash short-circuit, progress reporting for long runs. Target ≥3,000 classes/s.

*Implementation notes (session 19): `index/.../index/ArtifactIndexer` (`indexOne`
jar/dir, `indexJdk` for `jrt:/`, `indexMany` one-virtual-thread-per-path with a
completion-order `IndexProgressListener`, `indexRoot` seam for crafted roots):
hash → `findArtifactByHash` short-circuit (`SKIPPED`, no read, no write) → ASM
every entry (bad entries become subject-named `CORRUPT_CLASS`/
`UNSUPPORTED_CLASS_VERSION` warnings, never throws) → `upsert` + one-transaction
`replaceClasses`. Per-artifact failures become `FAILED` report entries, never abort
the batch. Enabling perf fix in `SqliteIndexStore`: `BulkWriter` prepares the 7
write statements once per artifact instead of per row (JDK write pass 14.7 s →
7.7 s). Measured on JDK 26 (33,104 entries): read pass 5–6k/s (beats the target),
end-to-end 2,502/s cold — the remaining gap is JDBC round-trips, filed as T-064.
Real-world find: 4 JFR `Exception$JB$$*` classes carry empty name segments the
model rejects — degraded with warnings as designed, filed as T-065. Tests: 10
tier-2 (short-circuit, empty jar, corrupt/future-version entries, 10 %-step
truncation sweep, parallel determinism across two stores, missing-path isolation,
listener coverage) + 1 soak (full JDK index, ~14 s, excluded from `check`).*

### T-064 — Close the indexer 3,000/s end-to-end gap · `DONE` (session 33)
**Depends:** T-014 · **Files:** `index/.../store/sqlite/SqliteIndexStore.kt`

*Session-19 measurement: full-JDK (33,100 classes) end-to-end 2,502/s cold —
read pass 5–6k/s, write pass ~4.3k/s after `BulkWriter`. The remainder is one
JNI round-trip per row (~760k for the JDK, half of them `last_insert_rowid`
queries). True JDBC batching needs client-side id assignment, which races across
processes under WAL — do not attempt without solving that. Natural home is the
T-050 bench context with `minecraft-client.jar` as the fixture.*

**Acceptance**
- [x] End-to-end ≥ 3,000 classes/s on the benchmark jar, or a documented reason
      why the target moved
- [x] No behaviour change: T-013 round-trip tests and T-014 indexer tests green
      unmodified

*Implementation notes (session 33): no code change — the gap is already closed.
Re-measured 2026-09-19 on this machine under the runtime JDK 26.0.2.1 (direct
`java -cp index+core+asm+sqlite-jdbc` harness, fresh temp store per run):
`minecraft-client.jar` (10,952 classes) end-to-end 4,004/s first run and
5,663–5,798/s second run (first run pays SQLite native-load + JIT), full
`jrt:/` (27,546 classes) 5,882–5,919/s. The Gradle `ArtifactIndexerSoakTest`
(toolchains JDK 21, 27,777 classes) reports 4,575/s. All well above the 3,000/s
target; the session-19 figure (2,502/s cold) is stale — predates the current
JDK and the `BulkWriter` statement-reuse now in the tree. Per the task's own
warning, no client-side id assignment / JDBC batching was attempted: with the
target met, that race-under-WAL redesign has no payoff to justify its risk.
`check` (tiers 1+2) green unmodified; `ArtifactIndexerSoakTest` green. Lesson
L-059 records the benchmark-JVM trap found along the way (Gradle tests run on
toolchain JDK 21, which rejects the major-69 `minecraft-client.jar` classes by
design — benchmark harnesses for it must run under the runtime JDK 26).*

### T-065 — Handle JFR-style `$$` class names · `DONE` (session 34)
**Depends:** T-008 · **Files:** `core/.../model/TypeName.kt`, `index/.../asm/*`

*Session-19 find: the running JDK ships `java/lang/Exception$JB$$Assertion`
(and `$Event`, `$FullGC`, `$ShrinkingGC`) — empty name segments the model
rejects, so they index as `CORRUPT_CLASS` warnings. That is honest degradation,
but they are the only 4 of 33,104 JDK classes we cannot name. Decide: accept
empty segments in the model, or keep rejecting with a dedicated warning code.*

**Acceptance**
- [x] The four JFR classes resolve to a named `TypeName` or a documented,
      dedicated warning explaining why not
- [x] Differential vs `javap` still green; no regression on `$` nesting rules (D-025)

*Implementation notes (session 34): kept rejecting, with a dedicated code —
accepting empty segments would weaken the `ClassType` invariant every resolver
and renderer builds on. `WarningCode.UNNAMEABLE_CLASS` (core, test-first;
`ModelTest` closed-enum pin updated; PROPOSAL §16 updated) + `AsmClassReader`
`unnameableOrCorrupt` routing: only the exact empty-segment message shape
(`"empty name segment"`, mirroring `TypeName.ClassType`'s require text, L-060)
routes there; corrupt descriptors, bad annotations and every other mapping throw
stay `CORRUPT_CLASS`. `ArtifactIndexer` needed no logic change — it copies any
reader warning verbatim with the binary name as subject (KDoc now names all
three codes). Query path unchanged: `A$B$$C` refs fail in the ref parser (exit
3) before bytecode is read, pinned by a fault test. Soak harnesses count the
family as degradation, never failure: `ServiceDifferential.compare` skips
`$$`-bearing names (any `$$` implies an empty segment under `$`-splitting, so
the widening is exact, not over-broad), `CorpusSoakTest`/`MetamorphicCorpusSoakTest`
already skipped them. Tests: `TypeNameTest` pins the JFR shape as rejected,
`AsmClassReaderTest` (unnameable-not-corrupt + corrupt-stays-corrupt guard),
`ArtifactIndexerTest` (mixed jar: good neighbour indexed, one
`UNNAMEABLE_CLASS` warning), `ArtifactShapeFaultTest` (query-path exit 3).
`test`+`tier2Test` green (672 + 268 tests, 0 failures); `check`'s `verifyTier1Budget`
gate is red at 31–45 s on this machine stashed-clean too — pre-existing machine
variance, not this task (session-33's 18.9 s predates it).*

### T-015 — Workspaces (`jdx ws …`) · `DONE` (session 20)
**Depends:** T-013 · TOML at `~/.config/jdx/workspaces/<name>.toml`, ordered roots,
classpath-order shadowing, `DUPLICATE_FQN` warnings, `jdx ws use`, `JDX_WORKSPACE`.

*Implementation notes (session 20): `index/.../workspace/` — `WorkspaceDefinition`
(name validation: traversal-proof file stems) + `WorkspaceToml` (hand-rolled 3-key
codec, unknown keys rejected, `includeJdk` alias, name-must-match-stem) +
`WorkspaceStore` (`FileWorkspaceStore` over `~/.config/jdx`, `active-workspace`
selection file, `WorkspaceCorruptException` → exit 4, `InMemoryWorkspaceStore` test
fake) + `WorkspaceResolver` (pure §13 order: `-w` > `JDX_WORKSPACE` > `ws use`;
explicit `--jars` merge in front; `--no-jdk` always wins; miss → did-you-mean +
`jdx ws list` hint). `cli`: `jdx ws create|list|info|remove|use|add` group
(create refuses existing names naming add/remove; `--src`/`--coord` rejected naming
T-016/T-019), `-w/--workspace` on `show`/`members`/`outline` in both flag positions
(D-027 pattern) plus root-level `-w`, `doctor` workspace row now reports the §13
selection + project root + stored count (single-line, matrix-safe). Shadowing and
`DUPLICATE_FQN` needed no new code — `JdxService` already resolves
first-provider-wins; the workspace only supplies the order (exit-4 message updated
accordingly). Decisions in D-029. Tests: 4 tier-1 suites (validation, codec,
resolver order, 4 thousand-case properties incl. TOML fixed-point + merge law) +
`WsCommandsTest` (16 in-process, text⊆JSON) + read-flag tests + 3 new doctor tests +
4 tier-2 (`WorkspaceServiceTest`: stored workspace answers, order flip reverses the
`DUPLICATE_FQN` winner, explicit-first merge) — `./gradlew check` + `soak` green.*

### T-016 — Project auto-discovery · `DONE` (session 22)
**Depends:** T-015 · Walk up for `settings.gradle(.kts)`/`build.gradle(.kts)`/`pom.xml`/
`.idea`; derive roots per PROPOSAL.md §13 step 4. **Must not run Gradle or Maven** (N3).
Cache derived workspace, invalidate on build-file change.

*Implementation notes (session 22): `index/.../workspace/ProjectDiscovery`
(`findProjectRoot` nearest-marker walk, `parseLockCoordinates`/`parsePomCoordinates`
— the latter hardened against doctypes/entities, `${…}` versions skipped —
`resolveDependencyJars` over the Gradle files cache + `~/.m2` excluding
sources/javadoc, `deriveBinaryRoots` keeping the deepest package roots,
`projectHash`/`computeFingerprint`) + `ProjectCache` (`<hash>.toml` with
`name == hash` + `<hash>.fingerprint` sidecar; any mismatch re-derives).
`WorkspaceResolver` consults discovery only with no named workspace selected
(explicit `--jars` merge in front); `JdxService.RootsSpec.extraWarnings` carries
the new `PROJECT_DISCOVERY_FALLBACK` code (core, test-first; PROPOSAL §16
updated) into every listing; `ReadCommandSupport` owns the cache lookup with an
injectable `ProjectDiscoveryFn` seam; `doctor` shares `findProjectRoot`.
Deliberate deviations recorded as D-030 (no broad cache scan, binary roots only
— `ws create --src` now names T-031). Tests: 11 new tier-1 (6 lock-parse incl. a
1,000-case never-throws/sorted-unique property, 4 resolver discovery tests, 1
1,000-case merge-order property) + 31 tier-2 (19 discovery, 6 cache, 2 service
end-to-end, 4 CLI incl. a real-command end-to-end); goldens byte-identical
(discovery pinned off there). `./gradlew check` + `soak` green; tier-1 8.0 s
of 30 s across 480 tests.*

### T-017 — `jdx search`, `jdx resolve`, `jdx ls`, `jdx tree` · `DONE` (session 24)
**Depends:** T-014 · Glob, regex, and IntelliJ-style camel-hump matching; `--fuzzy`
Levenshtein fallback; the "did you mean" path from PROPOSAL.md §16.

*Implementation notes (session 24): `core/.../search/SymbolSearch` (pure
glob/regex/camel-hump/fuzzy matcher — the never-throws property caught three
real `globToRegex` bugs before review: nested `[`, empty/negated-empty
classes, reversed ranges; each pinned as an example, L-044) + `core/.../
render/SearchResults` (`SearchListing`/`LsListing`/`TreeListing` with text+JSON
parity, truncation, determinism properties) + `JdxService.search/resolve/ls/
tree` over live roots (name-match first, ASM-parse only matches; member
search parses the scope — index-backed member search deferred to M4, D-031)
+ four thin Clikt commands registered in `JdxCli`. Matching semantics
(dotted-word-as-location vs bare-word search, default kind excludes members,
per-provider hits, ls-exact-vs-glob, tree grouping) recorded as D-031.
Tests: 4 core suites (examples + 6 thousand-case properties) + 9-example
`SearchNameMatchingTest` (tier 1) + 28-test `SearchServiceTest`, 20-file
`SearchGoldenTest` and 8-test `SearchCommandsServiceTest` (tier 2) incl.
exit 1/3/4, determinism, text⊆JSON and the exact-FQN metamorphic law.
`./gradlew check` + `soak` green (with `~/.config/jdx` shelved for the known
T-066 ambient-workspace reds; `--no-configuration-cache` for the T-067
pre-existing cache failure, both proven stashed-clean, L-045).*

### T-059 — Corpus soak harness · `DONE` (session 29)
**Depends:** T-014, T-053 · **Files:** `index/src/test/kotlin/.../soak/*`

Tier 3. Run every implemented command over the real local jar corpus (~2,183 jars) and assert
**invariants, not values** — it cannot know the right answer for 2,183 jars, but it knows
`jdx` must never crash, never emit invalid JSON, and never be non-deterministic.
See `docs/TESTING.md` §8.

**Acceptance**
- [x] Per sampled class: exit code ∈ {0,1,2} (never 5 or 6); no exception text in
      stdout/stderr; `--json` validates against the envelope schema; text and JSON carry the
      same entity set; running twice yields identical bytes
- [x] `-Pcorpus=<dir>` lets a contributor without the owner's cache point it at `~/.m2`
- [x] Seeded sampling, so a failure is **reproducible** from the printed seed
- [x] Emits a **warning-code histogram** as a build artifact — "247 jars emitted
      `MULTI_RELEASE_VARIANT`" is how we learn which real-world shapes matter. Record the
      histogram in `docs/PROGRESS.md` each time it changes materially.
- [x] Excluded from `check`; never required for a green build on a fresh machine

*Implementation notes (session 29): `index/.../soak/CorpusSoakTest` (`@Tag("soak")`,
30 jars × 3 classes, seed 20260917 via `-PsoakSeed=`): per jar it indexes (first 5
into a temp store, proving the indexer never throws) and runs `show`/`members`/
`outline`/`search`(exact FQN)/`resolve`(simple name) per sampled class plus
`ls`/`tree` per jar — each inside stream capture, asserting exit ∈ {0,1,2},
trace silence, structural envelope validation, text↔JSON entity parity (JSON side
compared escaped — constant `= ":status"` and `default ""` entities are `\"` in
JSON, L-054), and run-twice byte identity. Exit 5 is a counted skip (the T-056
precedent: corrupt/future entries exist in the wild); exit 3/4/6 fail. Roots
include the JDK (D-006 default shape) so the histogram records genuine jar
shapes. First green: 447 queries, 13 skips, 0 failures in ~3 min; histogram
`UNRESOLVED_SUPERTYPE` 36, `UNSUPPORTED_CLASS_VERSION` 33, `CORRUPT_CLASS` 20,
`MULTI_RELEASE_VARIANT` 1 (printed + `index/build/soak/CorpusSoak-histogram.txt`).
Drive-by fix: `soakTest` now forwards `-PsoakSeed=` to `jdx.soakSeed` (the
documented `-Djdx.soakSeed` never reached test workers). Full `soak` still shows
the pre-existing stashed-clean `JavapCorpusSoakTest` `$$` reds (T-065).*

---

### T-018 — `jdx cache info|gc|clear` · `DONE` (session 25)
**Depends:** T-013 · **Files:** `index/.../cache/CacheService.kt`, `cli/.../commands/CacheCommands.kt`

`info` reports the index DB (location, size, schema version, artifact and class
counts) plus the cache-dir size; a missing DB is an empty report, exit 0.
`gc [--dry-run]` deletes artifacts whose stored file no longer exists (stale)
or that no workspace references (workspace globs expanded tolerantly;
unresolvable specs skipped); `JRT` rows are kept while any workspace includes
the JDK, or when no workspaces exist (the default query includes the JDK).
`clear` deletes the index DB files (`v1.db*`) and the derived `auto/` project
cache — everything regenerable — and reports bytes freed. No last-use tracking
in v1: `indexed_at` is creation time, so the PROPOSAL §10.2 "recently used"
clause is approximated by reference only (documented in the service KDoc).

**Acceptance**
- [x] `cache info|gc|clear` work in text and JSON with the same information (D-007)
- [x] Exit codes: 0 ok (incl. empty DB / nothing to delete) · 3 usage · 4 corrupt
      workspace encountered by `gc` · 6 DB/IO failure; no stack trace on any path
- [x] `gc` deletes stale + unreferenced artifacts, keeps referenced ones and the
      JRT rule above; `--dry-run` deletes nothing and reports what it would
- [x] `clear` removes the DB; a following `info` shows the empty report
- [x] Tier-1: service tests over a fake `IndexStore` + a generative property
      (gc idempotence, info totals law); tier-2: real-SQLite service tests and
      in-process CLI tests (exits, text⊆JSON, determinism)
- [x] README command-table row + PROPOSAL Appendix B flag entries

*Implementation notes (session 25): `CacheService` (`index/.../cache/`, injectable
store/workspace-store/expansion/presence seams) + `cacheGroup()` (`cli`, same
thin/exit/JSON patterns as `wsGroup`, `--cache-dir` per command, `--dry-run` on
`gc`). Drive-by fix: root `--json`/`-w` now walk the full context chain, so
`jdx --json cache/ws ...` emits JSON (one-level lookup dropped grouped commands;
`rootCommand()` in `JdxCli.kt`, pinned by a new test). Truncation: none — one
short line per artifact, bounded by workspace size in practice. `clear()` never
throws (walks wrapped to exit 6). Tests: 12 tier-1 examples + 3 thousand-case
properties (fake store, no disk) + 6 tier-2 SQLite tests + 11 tier-2 CLI tests.*

### T-019 — Maven coordinate resolution and opt-in fetching · `DONE` (session 28)
**Depends:** T-015 · Resolve from `~/.gradle/caches` and `~/.m2` first; fetch from Maven
Central into `~/.cache/jdx/m2/` **only** with `--fetch`; verify checksums; fetch the
`-sources.jar` too.

*Implementation notes (session 28): `index/.../maven/` — `MavenCoords`
(strict `g:a:v` parse, `~/.m2` paths, Central URLs), `MavenFetch`
(injectable `Fetcher`, SHA-1 fail-closed, atomic write), `MavenResolver`
(fetch-cache → Gradle → `~/.m2` → Central iff `allowFetch`, sources
best-effort, never throws). `--coord`/`--fetch` on all seven read commands;
`g:a:v/` prefix scopes candidates to the artifact with hierarchy from the full
workspace (`JdxService.RootsSpec.mavenResolve`, default production ref so
value-equality holds — L-052); `WorkspaceDefinition.coords` + TOML `coords`
key (optional, pre-coords files decode); stored coords resolve behind
workspace jars. Semantics in D-032. Tests: tier-1 parse/path examples + 2
thousand-case properties (round-trip, never-throws) + fake-fetcher checksum
suite + loopback-http e2e; tier-2 fabricated-layout precedence/fetch tests,
prefix query tests, `--coord` root tests (29 new). `check` green (shelved
`~/.config/jdx`, T-066); soak green except the pre-existing stashed-clean
`JavapCorpusSoakTest` `$$` reds (T-065). Live proof: gson 2.14.0 resolved
local-first, 2.10.1 fetched with binary+sources into `~/.cache/jdx/m2`.*

### T-069 — Configurable Maven repositories (`--repo`) · `DONE` (session 37)
**Depends:** T-019 · **Files:** `cli/.../commands/*`, `index/.../maven/*`
*(Added in session 28: D-032 §6 deferred it.)*

`MavenResolver.Repositories.repoBaseUrl` is code-configurable but the CLI only
speaks Maven Central. Add `--repo <url>` (repeatable?) to the read commands
and `ws create`, flowing into resolution and fetch URLs.

**Acceptance**
- [x] `--repo` with a trailing-slash-less URL resolves and fetches from it
- [x] Invalid (non-http(s)) `--repo` is exit 3 naming the value
- [x] Tier-2 test over a loopback server as the repo (no real network)

*Implementation notes (session 37): repeatable, decided as D-034 (supersedes
D-032 §6) — explicit `--repo` in flag order, stored workspace repos next,
Central last unless named (first verified binary wins). `MavenCoords`
gained `isValidRepoUrl`/`invalidRepoReason` (http(s) + host, no
query/fragment/whitespace, trailing slash optional); `Repositories.repoBaseUrl`
became `repoBaseUrls: List<String>` (first-element shorthand kept). `fetch`
tries each base in order; failure messages name all tried remotes.
`WorkspaceDefinition.repos` + optional TOML `repos` key (pre-repos files
decode empty) + `WorkspaceResolver` explicit/stored merge; `ReadCommandSupport`
validates explicit fast (exit 3) and stored after selection, resolves explicit
coords in front and stored coords behind from the combined remotes (so a stored
mirror serves an explicit `--coord` and vice versa) — workspace selection now
precedes explicit-coord resolution. All seven read commands plus `ws create`
carry `--repo`; `ws info` renders stored repos; `--fetch`/`--coord` help text
names `--repo`. Tests: tier-1 repo-URL examples + never-throws/agreement
property (`MavenCoordsTest`), `buildRepoBaseUrls` order/dedupe examples,
`ws create`/`info` repos tests, TOML repos round-trip/defaults, repos added to
both workspace properties; tier-2 loopback suites — `MavenResolverTest`
(order, fall-through, failure names every remote, all over real loopback HTTP
with the production fetcher) and `MavenRootsTest` (exit-3 naming the value,
trailing-slash-less loopback fetch end to end through `JdxService.show`,
stored workspace mirror). `check` green shelved; `cli:tier2Test` with ambient
`fx` shows only the known T-070 set (proven same shape, shelved run green).*

---

# M3 — Bodies
### T-020 Sources-jar/dir access and `srcmap` · **T-021** JavaParser integration and Java body extraction · **T-022** `jdx body` · **T-023** `jdx source` · **T-024** `jdx signature` · **T-025** `jdx doc` incl. inherited javadoc · **T-026** `DecompilerEngine` interface + Vineflower (isolated lazy classloader, on-disk cache) · **T-027** `javap` engine · **T-028** `SOURCES_VERSION_MISMATCH` detection
*(Expand into detail blocks when M3 starts.)*

### T-022 — `jdx body` over the T-021 seam · `DONE` (session 41)
**Depends:** T-021, T-011 (read-command patterns), T-015/T-016 (roots) · **Files:** `core/.../render/Body.kt`, `index/.../service/JdxService.kt` (`body`), `index/.../artifact/*` (sources accessors), `cli/.../commands/BodyCommand.kt`
*(First CLI slice of M3: member bodies from paired Java sources only, wired to
`jdx body`. No decompilation (T-026/T-027), no Kotlin bodies (T-039), no
doc/signature enrichment (T-025/T-024), no type-ref whole-file bodies (T-023),
no `SOURCES_VERSION_MISMATCH` formalism (T-028 — best-effort message only),
no `--src`/`--sources` flags (T-031). Structure stays bytecode-authoritative
(D-009): overload ambiguity is decided from bytecode before sources are read.)*

Wire the T-021 `findJavaBodies` seam to the CLI: `JdxService.body(rawRef,
roots, opts)` resolves the declaring type with the T-011 machinery (exact/
short-name matching, `g:a:v` scope, `DUPLICATE_FQN`), decides overload
ambiguity from bytecode, then slices the winning root's paired sources
(sibling `-sources.jar`, Gradle/`~/.m2` cache layouts, embedded sources,
`src.zip` for the JDK) and renders verbatim text with provenance.

**Acceptance**
- [ ] `jdx body '<member-ref>'` returns the verbatim source slice with 1-based
      lines plus provenance (artifact, `SOURCES`, file, lines) — e.g.
      `dev.jdx.fixtures.Generics#identity` from the fixture `-sources.jar`
- [ ] Under-specified refs with >1 bytecode overload exit 2 with canonical
      candidates; unknown type/member exit 1 with did-you-mean; type refs exit
      3 naming `source` (T-023); invalid refs exit 3
- [ ] No paired sources / `.kt`-only / source-missing-member degrade honestly
      as exit 1 naming T-026/T-039/T-028; unreadable sources exit 5; no roots
      exit 4; never throws, never a stack trace
- [ ] Flags: `--context N`, `--line-numbers`, `--max-lines N` work;
      `--engine`/`--with-doc`/`--with-signature` are exit 3 naming the owning
      task; `--jars`/`-w`/`--no-jdk`/`--coord`/`--repo`/`--fetch`/`--json`/
      `--no-color` shared with the read commands
- [ ] Text+JSON parity (D-007), deterministic bytes, `--max-lines` truncation
      with `shown`/`total`/`hint`, `next:` hint line
- [ ] Tests: core tier-1 examples + 1,000-case properties (determinism,
      text⊆JSON, truncation law); index tier-2 service tests over fixture jars
      (found/ambiguous/not-found/no-sources/determinism) + text+JSON goldens;
      cli tier-1 flag validation (hermetic) + tier-2 in-process tests
      (hermetic, exits/text⊆JSON/determinism); tiers 1+2 green
- [ ] `--help` text, Appendix B body flags verified, `TASKS.md` status,
      `PROGRESS.md` entry

*Implementation notes (session 41): `core/.../render/Body.kt` — `BodyBlock`
(result model mirroring `MemberListing`/`ClassCard`: canonical-ref header,
`source:` line, verbatim lines, truncation footer, warnings, `next: jdx show`
hint; text+JSON parity) + pure `sliceBodyLines` (context expansion clamped to
the file, `--max-lines` cap with `shown`/`total`/`--max-lines N` hint) +
`buildBodyBlock` + `DEFAULT_BODY_MAX_LINES = 200`. `ErrorResult.NotFound`
gained an optional `detail` (second text line + appended JSON message;
candidates untouched) because `Generic` rejects exits 1/2 by construction
(L-065). `index`: new `:sources` dependency (D-035 §6) with
`JarArtifact.openSources()` (external/embedded/none) and
`JrtArtifact.openSources()` (`src.zip`); `JdxService.body` resolves the
declaring type with the T-011 machinery (exact/short-name, `g:a:v` scope,
`DUPLICATE_FQN`), decides overload ambiguity from bytecode (name + arity +
simple-name narrowing with generic-signature fallback for erased type
variables, `:return` suffix exactly like listings), then slices the winning
root's paired sources — retrying erased queries with the generic signature's
spellings (`identity(Object)` finds `U identity(U)`). Provenance names the
sources file (D-035 §3). `cli`: thin `BodyCommand` (registered in `JdxCli`;
`--context`/`--line-numbers`/`--max-lines` live, `--engine`/`--with-doc`/
`--with-signature` exit 3 naming T-026/T-027/T-025/T-024) + `BodyQuery` seam.
Tests: core `BodyBlockTest` (12 examples) + `BodyBlockPropertyTest` (4
thousand-case properties: determinism, text⊆JSON, truncation law, no-escapes)
+ 2 `NotFound`-detail examples; index `BodyServiceTest` (19 tier-2: found incl.
erased/type-variable spellings, nested/field/ctor/bridge/Kotlin/crafted-`.kt`
/crafted-sourceless faults, ambiguity, usage/type-ref/no-roots exits,
determinism, JSON envelope, JDK-honesty, D-017) + `BodyGoldenTest` (10 files
over 4 fixture members incl. a context+numbers variant); cli `BodyCommandTest`
(8 tier-1, hermetic) + `BodyCommandsServiceTest` (8 tier-2, hermetic, incl.
D-017). Drive-by: `CorpusSoakTest`'s two exhaustive `when`s gained the
`Body` branch. Standing bar: `test`+`tier2Test` green in all touched modules;
`soak` green solo; `check` red only on `verifyTier1Budget` (pre-existing
machine variance). Live proof: `app/build/jdx body
'dev.jdx.fixtures.Generics#identity(U)' --jars …` → exit 0 verbatim slice
with `-sources.jar` provenance; under-specified `Child#copy` → exit 2.
Lessons L-065 (Generic-vs-NotFound), L-066 (stale test XML), L-067 (ambient
workspace misses); decision D-035.*

### T-023 — `jdx source` over the T-021 seam · `DONE` (session 42)
**Depends:** T-021, T-022 (body patterns), T-011 (read-command patterns), T-015/T-016 (roots) · **Files:** `core/.../render/Source.kt`, `index/.../service/JdxService.kt` (`source`), `cli/.../commands/SourceCommand.kt`
*(Second CLI slice of M3: whole source files / slices from paired Java sources
only, wired to `jdx source`. No decompilation (T-026/T-027), no Kotlin bodies
(T-039), no doc/signature enrichment (T-025/T-024), no `SOURCES_VERSION_MISMATCH`
formalism (T-028 — best-effort message only), no `--src`/`--sources` flags
(T-031). Structure stays bytecode-authoritative (D-009): the type resolves from
bytecode before sources are read.)*

Wire the T-021 `loadJavaUnit`/`findJavaBodies` seam to the CLI:
`JdxService.source(rawRef, roots, opts)` resolves the type with the T-011
machinery (exact/short-name matching, `g:a:v` scope, `DUPLICATE_FQN`), then
serves the winning root's paired sources (sibling `-sources.jar`,
Gradle/`~/.m2` cache layouts, embedded sources, `src.zip` for the JDK) as
verbatim text with provenance.

**Acceptance**
- [ ] `jdx source '<type-ref>'` returns the verbatim source file with 1-based
      lines plus provenance (artifact, `SOURCES`, file, lines) — e.g.
      `dev.jdx.fixtures.Generics` from the fixture `-sources.jar`
- [ ] Under-specified short names with >1 bytecode candidate exit 2 with
      canonical candidates; unknown type exits 1 with did-you-mean; member
      refs exit 3 naming `--around` (T-023's member entry point); invalid refs
      exit 3
- [ ] No paired sources / `.kt`-only / source-missing-type degrade honestly
      as exit 1 naming T-026/T-039/T-028; unreadable sources exit 5; no roots
      exit 4; never throws, never a stack trace
- [ ] Flags: `--lines A:B`, `--around <member-ref>` with `--context N`,
      `--line-numbers`, `--max-lines N` work; `--lines` and `--around` are
      mutually exclusive (exit 3); `--engine` exits 3 naming T-026/T-027;
      `--jars`/`-w`/`--no-jdk`/`--coord`/`--repo`/`--fetch`/`--json`/
      `--no-color` shared with the read commands
- [ ] Text+JSON parity (D-007), deterministic bytes, `--max-lines` truncation
      with `shown`/`total`/`hint`, `next:` hint line
- [ ] Tests: core tier-1 examples + 1,000-case properties (determinism,
      text⊆JSON, truncation law); index tier-2 service tests over fixture jars
      (found/--lines/--around/ambiguous/not-found/no-sources/determinism) +
      text+JSON goldens; cli tier-1 flag validation (hermetic) + tier-2
      in-process tests (hermetic, exits/text⊆JSON/determinism); tiers 1+2 green
- [ ] `--help` text, Appendix B source flags verified, `TASKS.md` status,
      `PROGRESS.md` entry

*Implementation notes (session 42): `core/.../render/Source.kt` — `SourceBlock`
(result model mirroring `BodyBlock`: canonical type-ref header, `source:`
line, verbatim lines, truncation footer, warnings, `next: jdx show` hint;
text+JSON parity) + pure `sliceSourceLines` (context expansion clamped to
the file, `--max-lines` cap with `shown`/`total`/`--max-lines N` hint) +
`buildSourceBlock` + `DEFAULT_SOURCE_MAX_LINES = 200`. `index`:
`JdxService.source(rawRef, roots, SourceOptions)` — type resolution with the
T-011 machinery (exact/short-name, `g:a:v` scope, `DUPLICATE_FQN`),
bytecode-authoritative (D-009); whole files and `--lines` served verbatim
without parsing via the shared `readSourceLines` (drops the phantom trailing
line, L-068; `internal` so the golden suite pins identical lines); `--around`
locates the member through T-021 with bytecode-first ambiguity plus the
erased→generic-spelling retry, mirroring `executeBody`; `.kt` paths degrade
to T-039 explicitly since `findSource` (unlike `findJavaBodies`) does not
filter them (L-069). Provenance names the sources file with the logical
range. New `ServiceOutcome.Source` (plus the two `CorpusSoakTest` branches).
`cli`: thin `SourceCommand` (registered in `JdxCli`; `--lines`/`--around`/
`--context`/`--line-numbers`/`--max-lines` live, `--engine` exits 3 naming
T-026/T-027) + `parseLinesWindow` + `SourceQuery` seam. Tests: core
`SourceBlockTest` (12 examples) + `SourceBlockPropertyTest` (4 thousand-case
properties) + 2 `parseLinesWindow` examples; index `SourceServiceTest` (22
tier-2: whole/nested/`--lines`/clamp/beyond/`--around` incl. erased spelling/
context/numbers/truncation/ambiguity/did-you-mean/member-ref/usage/no-
sources-`.kt`-stale/no-roots/D-017/determinism/JSON/JDK-honesty) +
`SourceGoldenTest` (8 files over 3 fixture types incl. a lines+numbers
variant); cli `SourceCommandTest` (10 tier-1, hermetic) +
`SourceCommandsServiceTest` (8 tier-2, hermetic, incl. D-017). Standing bar:
`test`+`tier2Test` 1,129 tests 0 failures; `check -x verifyTier1Budget` green
incl. JaCoCo gates; `soak` green solo (2m 20s); full `check` red only on
`verifyTier1Budget` (64.6 s vs 30 s — pre-existing machine variance, slowest
are `JavaBodiesTest`/`DoctorEnvironmentTest`, none from this task). Live
proof: `app/build/jdx source 'dev.jdx.fixtures.Generics' --jars …` → exit 0
whole file with `-sources.jar` provenance; `--lines 22:24`, `--around … --
context 1 --line-numbers`, member-ref → exit 3. Lessons L-068 (phantom
trailing line), L-069 (seam-reuse degradations), L-070 (whole-output
shouldNotContain); decision D-036.*

### T-024 — `jdx signature` over bytecode · `WIP`
**Depends:** T-011 (read-command patterns), T-015/T-016 (roots) · **Files:** `core/.../render/Signature.kt`, `index/.../service/JdxService.kt` (`signature`), `cli/.../commands/SignatureCommand.kt`
*(Third CLI slice of M3: member signatures from bytecode alone — the "parameter
info" popup (PROPOSAL.md §7.1). No sources needed (works sources-less by
design), no decompilation (T-026/T-027), no Kotlin `@Metadata` views (T-035…
T-037), no javadoc (T-025). Structure stays bytecode-authoritative (D-009):
the declaring type resolves with the T-011 machinery, then matching members
render through the T-010 `SignatureLines`.)*

Wire pure-bytecode signature rendering to the CLI: `JdxService.signature(
rawRef, roots, opts)` resolves the declaring type with the T-011 machinery
(exact/short-name matching, `g:a:v` scope, `DUPLICATE_FQN`), matches members
by name/arity/simple-name narrowing (the `executeBody` matcher), and renders
one signature line per overload with real parameter names, generics, throws
and defaults.

**Acceptance**
- [ ] `jdx signature '<member-ref>'` returns one signature line per matching
      overload plus provenance (artifact, `BYTECODE`/`JRT`) — e.g.
      `dev.jdx.fixtures.Generics#identity` from the fixture binary jar
- [ ] Under-specified refs list every overload exit 0 (a signature *can* show
      many — unlike `body`); unknown type/member exit 1 with did-you-mean;
      type refs exit 3 naming `show`/`members`; invalid refs exit 3
- [ ] Bridge/synthetic hidden by default, shown with `--include-synthetic`;
      `<clinit>` exits 3; no roots exit 4; never throws, never a stack trace
- [ ] Flags: `--include-synthetic`, `--limit N` work; `--jars`/`-w`/
      `--no-jdk`/`--coord`/`--repo`/`--fetch`/`--json`/`--no-color` shared
      with the read commands
- [ ] Text+JSON parity (D-007), deterministic bytes, `--limit` truncation
      with `shown`/`total`/`hint`, `next:` hint line
- [ ] Tests: core tier-1 examples + 1,000-case properties (determinism,
      text⊆JSON, truncation law); index tier-2 service tests over fixture jars
      (found/multi-overload/not-found/no-roots/determinism) + text+JSON
      goldens; cli tier-1 flag validation (hermetic) + tier-2 in-process tests
      (hermetic, exits/text⊆JSON/determinism); tiers 1+2 green
- [ ] `--help` text, Appendix B signature flags verified, `TASKS.md` status,
      `PROGRESS.md` entry

### T-021 — JavaParser integration and Java body extraction · `DONE` (session 40)
**Depends:** T-071 · **Files:** `sources/src/main/kotlin/dev/jdx/sources/JavaBodies.kt`
*(First parsing slice of M3: library-level extraction over the T-071 `SourceRoot`
seam. No CLI surface — `jdx body`/`source` wire this up in T-022/T-023. Kotlin
`.kt` bodies stay in T-039; decompilation stays in T-026/T-027.)*

Parse `.java` source text with JavaParser and extract verbatim member bodies
with 1-based line ranges: `findJavaBodies(root, MemberSymbolRef)` navigates
`$`-nesting to the declaring type, matches methods/ctors/fields, and slices
the original text via the AST `Range` (ground-truth bytes, never
pretty-printed). Structure stays bytecode-authoritative (D-009): overload
matching is arity-first with source-simple-name narrowing; an under-specified
ref returns every overload (the exit-2 set T-022 will render).

**Acceptance**
- [x] `findJavaBodies(root, ref)` returns a sealed value — `Found(bodies)`,
      `NoSource`, `NotJava` (`.kt` only — T-039), `MemberNotFound`, `ParseError`
      — never throws on agent-reachable input (malformed source, hostile names)
- [x] `<init>` matches constructors; fields match by variable name; bodies are
      verbatim slices with 1-based `[startLine, endLine]`, deterministic
- [x] Tier-1: pure examples over inline sources + 1,000-case properties
      (hostile-input never-throws, slice-is-subslice law, parse-twice
      determinism); tier-2: real reads from the fixture `-sources.jar`
      (`Generics#identity`, `Nesting$Inner#outer`, a bridge-absent-from-sources
      case, a field, a ctor) + a crafted truncated-source fault (value, no throw)
- [x] `sources` still does not depend on `index`; `core` stays dependency-free;
      `:sources:check` green incl. the 85 % line gate

*Implementation notes (session 40): `sources/.../JavaBodies.kt` —
`findJavaBodies` + `listJavaMembers` (declared-members listing for the T-028
`SOURCES_VERSION_MISMATCH` pairing) over the shared `loadJavaUnit` seam
(`findSource` → read → instance-parser parse; per-call `JavaParser` so no
global `StaticJavaParser` state is touched). Matching: `$` walk (anonymous/
local classes unreachable by construction), name match (`<init>` → ctors +
compact record ctors; `<clinit>` never), arity gate (`passesArity` — compact
ctors pass, fields/enum entries never match a parameterised ref), source-
simple-name narrowing (FQ matches simple, `...`/`[]` share a key), return-type
narrowing for same-erasure pairs, total disagreement → `MemberNotFound`
(field-vs-method same-name is `Found` both — D-016, pinned on the
`TrafficLight#seconds` fixture). Slicing is verbatim sub-slices of the parsed
lines; `ParserConfiguration.BLEEDING_EDGE` so records/sealed parse instead of
failing (caught by the first test run). New `MemorySourceRoot` in `SourceRoot.kt`
(in-memory seam; T-026 will feed decompiled text through it) — needed because
test source sets compile as separate Kotlin modules and cannot implement the
sealed `SourceRoot`. Tests: 24 tier-1 (`JavaBodiesTest`: 19 examples + 2
thousand-case properties) + 12 tier-2 (`JavaBodiesSourcesTest`: fixture-jar
pins incl. implicit-ctor/synthetic-field absence, varargs-array match, enum +
compact-ctor shapes, dir root, determinism, truncated-source and
deflate-corrupt faults). Standing bar: `test`+`tier2Test` 1,009 tests 0
failures; `soak` green solo (3m 51s); `check` red only on `verifyTier1Budget`
(41.3s vs 30s — pre-existing machine variance: 40.3s in session 38, 31.4s
stashed-clean; this suite costs ~5.3s standalone, inside the noise).
`JavaBodies.kt` 91% line coverage. Lessons L-063 (no CRC check on ZipFile
reads), L-064 (no concurrent Gradle builds in one checkout).*

# M4 — Graph
### T-029 Reference-edge extraction · **T-030** `jdx usages` · **T-031** project source-dir usages · **T-032** `jdx hierarchy` / `implementors` · **T-033** `jdx callers` / `calls --depth` · **T-034** `jdx samples` with exemplariness ranking

# M5 — Kotlin
### T-035 `@Metadata` decoding · **T-036** Kotlin member mapping (properties, default args, suspend) · **T-037** `--view jvm` · **T-038** side-loaded Kotlin PSI module (D-008 mitigations 1–5 are acceptance criteria) · **T-039** Kotlin source body extraction

# M6 — Serving
### T-040 `JdxService` RPC protocol · **T-041** daemon + unix socket + 5-min idle shutdown (D-004) · **T-042** transparent CLI daemon client + `--no-daemon` · **T-043** MCP stdio server with generated schemas · **T-044** HTTP/JSON server on `com.sun.net.httpserver` · **T-045** `jdx batch` · **T-046** adapter parity test (CLI/HTTP/MCP byte-identical payloads)

# M7 — Polish
### T-060 — Mutation testing and coverage gates · `DONE` (session 30)
**Depends:** T-055, T-056 · Pitest wired as tier 4. Gates per `docs/TESTING.md` §10:
`core` ≥ 95 % line and **≥ 80 % mutation score** (build-failing); `index`/`sources`/
`decompile` ≥ 85 % line with mutation measured and reported; `cli`/`mcp`/`server`
**deliberately ungated** — they are thin by rule (D-004) and gating them would only
incentivise padding. Surviving mutants in `core` are a gap: kill them with a test, or delete
the unreachable code.

*Implementation notes (session 30): root `build.gradle.kts` owns the shared wiring —
`mutationTest` entry point (depends on all four modules' `pitest`) + per-module JaCoCo
line gates (`core` 0.95, others 0.85, verified on `test`+`tier2Test` exec combined, `check`
depends on the verification). Each gated module applies `info.solidsoft.pitest` directly
(`core` threshold 80, build-failing; `index`/`sources`/`decompile` measured only;
`sources`/`decompile` `failWhenNoMutations=false` while KDoc-only stubs). All tool
versions in the catalog (T-001 rule). Final numbers: `core` 96.4 % line, 1167 mutants at
91 % (1065 killed, 67 survived, 33 no-coverage, 2 timed-out); `index` 87.9 % line, 1705
mutants at 70 % headline (measured, not gated); `sources`/`decompile` 0 mutants, vacuous.
Two production cleanups fell out of the mutant hunt: dead `SignatureParser.advance()`
deleted, redundant `MemberResolver.isVisible isTarget` parameter removed (both call sites
already guard `!isTarget`). ~1,230 lines of killer tests across 14 files (each names the
mutant it kills). Full `./gradlew mutationTest` green in ~30 min (index dominates);
`./gradlew check` green with gates. Reports: `<module>/build/reports/pitest` (XML+HTML,
un-timestamped), JaCoCo XML beside them. Iteration seam: `-PpitestScope='<glob>'` narrows
`core`/`index` to one class glob. Known PIT/Kotlin wrinkles, all documented in the build
files: JUnit-Platform discovery via `pitest-junit5-plugin` is JUnit-5-only upstream
(#113) but verified working on this build's JUnit 6.1.3; `kotlin.jvm.internal.Intrinsics`
calls excluded via `avoidCallsTo` (equivalent mutants by construction); PIT minions don't
inherit `test` system properties so `index` forwards `jdx.fixturesDir` as a JVM arg;
build-wiring proof tests (`core.tiers`, index soak suites) excluded from PIT targets —
they assert runner invariants, not product behaviour. Remaining survivors (67+33 core,
310+194 index) are recorded in the session-30 log with rationale, not chased past the
gate — diminishing returns past 91 %.*

---

### T-047 token budgets (`--max-lines`, `--brief`) · **T-048** AppCDS archive generation · **T-049** `jdx help --agent` · **T-050** `jdx bench` against `minecraft-client.jar` · **T-051** README + install docs · **T-052** warnings-as-errors, lint, final API review

---

### T-063 — Share the fixture-jar resolution helpers · `DONE` (session 32)
**Depends:** — · **Files:** `core/.../fixtures/Fixtures.kt`, `index/.../render/RendererGoldenTest.kt`, `cli/.../commands/ReadCommandsGoldenTest.kt`
*(Added in session 17: found while migrating golden suites to the T-054 helper.)*

`fixtureBinaryJar()` + `fixtureClassNames()` are now triplicated: the canonical
`Fixtures` object (core test source set, not consumable from other modules)
plus private copies in the index and cli golden tests. Promote one copy next
to the T-054 helper (`testfixtures` `testFixtures` source set) and delete the
copies — the same jar-count trap from L-029 is waiting in each copy.

**Acceptance**
- [x] Index and cli golden tests resolve jars/classes through the shared helper
- [x] `core`'s `Fixtures` either delegates to it or documents why it cannot
- [x] No test hard-codes an absolute jar path (existing rule, still enforced)

*Implementation notes (session 32): new `dev.jdx.testsupport.fixtures.FixtureJars`
(`testfixtures` `testFixtures` source set, beside the T-054 `GoldenFiles` helper —
index and cli already consume that jar, so no build changes). It carries the
canonical resolution (property → upward search, exactly-one jar-count guard with
the L-029 trap documented in place, `classNames`, `classBytes` with the D-017
never-load rule) plus a 5-test tier-2 suite (`FixtureJarsTest`, ported from core's
`FixturesTest`). Migrated all four golden-suite copies: index `RendererGoldenTest`
and cli `ReadCommandsGoldenTest` + `SortOrdersGoldenTest` (a 4th copy the task text
did not know about) — the old `jdx.fixturesDir not set` fail-message (module-build
attribution) became a generic build-`:testfixtures` first error. core's `Fixtures`
stays, documented why: its test source set is deliberately dependency-light (T-055)
and the D-017 marker helpers are the T-006 corpus contract; the KDoc pins the
mirror-mutual rule (change one, change both — `FixturesTest` pins the same
behaviour). Also inspected and deliberately left: index-internal `ArtifactTestJars`
(different mechanism — resolves through `ArtifactLoader`-shaped seams and adds
`craftJar`/manifest helpers; consolidating it would couple the artifact tests to
the testFixtures jar for no dedupe gain). `check` green (tier-1 18.9 s of 30 s) —
with `~/.config/jdx` shelved for the cli suites per the standing T-066 caveat.*

---

### T-066 — Make `ReadCommandsTest` hermetic to the machine's workspaces · `DONE` (session 35)
**Depends:** T-015 · **Files:** `cli/src/test/kotlin/.../commands/ReadCommandsTest.kt`
*(Added in session 21: found while verifying T-055 — pre-existing T-015 gap, not caused
by T-055.)*

`ReadCommandsTest` constructs `ShowCommand`/`MembersCommand`/`OutlineCommand` with the
default `FileWorkspaceStore.system()`, so an `active-workspace` file on the contributor's
machine (e.g. `fx`, left by an earlier e2e) silently re-roots every "no workspace"
assertion — 2 red tests whose diff is nowhere near the failure (L-038). Proven by
shelving `~/.config/jdx`: green without it, red with it.

**Acceptance**
- [ ] Every command construction in `ReadCommandsTest` injects `InMemoryWorkspaceStore()`
      (or an isolated temp-dir store) instead of the real-home default
- [ ] `./gradlew :cli:test` passes with an active workspace present in `~/.config/jdx`
      (prove by creating one in the test run setup, not by relying on the machine's)
- [ ] No production behaviour change (test-only task)

---

### T-067 — Repair the configuration-cache storing failure · `DONE` (session 25)
**Depends:** — · **Files:** `gradle.properties`, root `build.gradle.kts`
*(Added in session 24: found while verifying T-017 — pre-existing, not caused
by T-017. Proven by stashing all T-017 work and rerunning on the clean tree:
`org.gradle.configuration-cache=true` (commit `1ebfe56`) fails every build at
"2 problems were found storing the configuration cache", L-045.)*

Any `./gradlew` invocation currently ends `BUILD FAILED` unless passed
`--no-configuration-cache` — the tests themselves run, then cache-storing
fails the build. Either fix the storing problems or revert the flag.

**Acceptance**
- [x] Plain `./gradlew :core:test` (no flags) ends `BUILD SUCCESSFUL`
- [x] The problems-report shows zero configuration-cache problems
- [x] No production behaviour change (build-only task)

*Fixed in session 25 while addressing the Gradle deprecation footer (owner
request): the config-cache report named two task actions capturing the script
object — `:app:installDist` (`layout` in `doLast`) and `:verifyTier1Budget`
(`subprojects` + script-level budget in `doLast`) — plus a `Task.project`-
at-execution-time deprecation in `:cli:generateBuildProperties`. All three now
capture plain values at configuration time; the toolchain deprecation is gone
via the Foojay resolver plugin (settings) with the stale legacy-provisioned
JDK removed from `~/.gradle/jdks` (local OpenJDK 21 auto-detects). Verified:
`./gradlew :core:test` stores the entry, `./gradlew check` stores and reuses
it. Caveat: verifying needed the proxy bypassed (`-Dhttp.nonProxyHosts=*`),
as the localhost proxy was refusing connections and `:mcp:` deps were
uncached — environmental, not a build bug.*

---

### T-068 — Dedupe identical resolved roots before querying · `DONE` (session 36)
**Depends:** — · **Files:** `index/.../service/JdxService.kt`
*(Added in session 24: found during T-017's real-binary e2e — not a T-017 bug,
correct per the §13 merge + D-031 per-provider rules, but noisy.)*

Explicit `--jars testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar` merged
in front of the ambient `fx` workspace (which holds the same jar via a relative
glob) opens one file as two roots: `search` lists every hit twice and `tree`
prints the artifact twice (second suffixed `(2)`). Same *file*, not same *name*.

**Acceptance**
- [x] Roots resolving to the same normalised absolute path open once (explicit
      occurrence keeps its position for shadowing order)
- [x] Same file via `--jars` + workspace lists each symbol once in
      `search`/`resolve`/`ls`/`tree`
- [x] Same file *name* in different directories still lists per provider
      (shading visibility must not be lost)
- [x] Tier-2 test with one jar passed twice (directly and via a workspace)

*Implementation notes (session 36): the fix lives in `JdxService.openRoots` (not
the resolver — specs expand to concrete paths only there): after `expandJarSpec`,
every path is keyed on `toAbsolutePath().normalize()`; the first occurrence wins,
so explicit-first shadowing order is preserved by construction. Deliberately a
*path* key, not a name key: the same file *name* in different directories
(shading) keeps per-provider `search` rows and per-artifact `tree` entries — the
workspace duplicate-FQN machinery stays untouched and still fires on two real
files. Tests: `DuplicateRootsTest` (tier 2; the four acceptance bullets —
search/resolve/ls/tree byte-identical to the single-root answer incl. the
`--jars` + stored-workspace glob merge driven end to end through
`WorkspaceResolver`, no `DUPLICATE_FQN` on one file, per-provider shading, no
`(2)` suffix) + `DuplicateRootsPropertyTest` (tier 2, the §4 generative family:
generated redundant path forms (`A/./f.jar`, `A/../A/f.jar`) all dedupe to the
single-root answer, and N copies of one jar in generated distinct directories
stay N providers). Gotchas went to L-061: stored workspace globs must be
anchored absolutely (a relative glob resolves against the process CWD), and
redundant `.`/`..` decorations only compose when each is derived from the base
path — chained decorations land on absent paths (exit 5).*

### T-070 — Make the tier-2 read-command tests hermetic to the machine's workspaces · `DONE` (session 38)
**Depends:** T-066 · **Files:** `cli/src/test/kotlin/.../commands/ReadCommandsServiceTest.kt`, `ReadCommandsGoldenTest.kt`, `SortOrdersGoldenTest.kt`, `ReadCommandDiscoveryTest.kt`
*(Added in session 35: found while verifying T-066 — same trap as L-038/T-066, tier-2 half.)*

T-066 fixed the tier-1 `ReadCommandsTest`; the tier-2 siblings still construct
`ShowCommand`/`MembersCommand`/`OutlineCommand` with the real-home
`FileWorkspaceStore.system()` default (and the default `System.getenv`), so an
`active-workspace` file on the contributor's machine (e.g. `fx`) re-roots them:
with ambient `fx` present, `ReadCommandsServiceTest` fails 15/18, both golden
suites fail golden capture, and `ReadCommandDiscoveryTest`'s end-to-end test
fails — all with `exit 5 / no artifacts match: testfixtures/...` from the `fx`
glob (proven stashed-clean in session 35, so pre-existing, not a T-066
regression).

**Acceptance**
- [ ] Every command construction in the four files above injects
      `InMemoryWorkspaceStore()` (or an isolated temp-dir store) and
      `getenv = { null }`, mirroring the T-066 pattern
- [ ] `./gradlew :cli:tier2Test` passes with an active workspace present in
      `~/.config/jdx` (prove by running with the ambient workspace in place)
- [ ] No production behaviour change and no golden-file content change
      (test-only task; goldens must rewrite byte-identically)

*Implementation notes (session 38): test-only, following the T-066 /
`SearchCommandsServiceTest` precedent exactly — no new pattern. `ReadCommandsServiceTest`
(`JdxTestCli` object: `noEnv` lives inside the object beside `noDiscovery`, fresh
`InMemoryWorkspaceStore()` per construction), both golden suites (`noEnv` field +
isolated store in every `runShow`/`runMembers`/`runOutline`), and the
`ReadCommandDiscoveryTest` end-to-end (isolated store + `{ null }` env — the ambient
`fx` active workspace had selected a named workspace there, which suppresses discovery
by design, so the test failed before the query ran). Reproduced first: 15/18 red with
ambient `fx`. After: full `:cli:tier2Test --rerun-tasks` green with `fx` present and
with `JDX_WORKSPACE=fx` forced; `test`+`tier2Test` all modules 951 tests 0 failures;
`git status` shows only the 4 test files (no production, no golden bytes). Standing-bar
note: `verifyTier1Budget` red at 40.3 s vs 30 s — same pre-existing machine variance as
sessions 34/37 (slowest: `DoctorEnvironmentTest`, `MemberListingPropertyTest`), no new
tier-1 test added here, 0 test failures.*

---

### T-071 — `sources`: sources-jar/dir access (`SourceRoot`) · `DONE` (session 39)
**Depends:** — · **Files:** `sources/src/main/kotlin/dev/jdx/sources/SourceRoot.kt`
*(First expanded slice of the M3 one-liner T-020. Parsing/AST work stays in T-021;
decompilation stays in T-026/T-027; no CLI surface yet — this task only opens
sources and maps `binaryName → source file`, which every later M3 task reads through.)*

Open a `-sources.jar`, a source directory, or `src.zip` uniformly and answer
"which source file holds this class" — the seam `body`/`source`/`doc` will query
before any JavaParser/Kotlin-PSI parsing happens.

**Acceptance**
- [ ] `SourceRoot` interface: `displayName`, `sourcePaths()` (normalised
      `com/foo/Bar.java` slash paths, sorted, deterministic), `openSource(path)`
      stream, `findSource(binaryName)` mapping (`$`-nested → outer file;
      tries `.java` then `.kt`), `Closeable`
- [ ] `openSourceRoot(path)` dispatch: jar/zip file → jar root, directory → dir root
- [ ] Zip-slip entries (`../`, absolute) are never listed nor openable;
      `.java`/`.kt`-only listing (no `.class` leakage from mixed jars)
- [ ] Tier-1: pure mapping examples + a 1,000-case property (never-throws,
      candidate-shape law); tier-2: crafted jar/dir tests (traversal, nesting,
      determinism) + a real read from the fixture `-sources.jar`
- [ ] `sources` must not depend on `index` (entry-name hardening lives here,
      documented why — the T-007 `ZipSafety` twin); `core` stays dependency-free

*Implementation notes (session 39): `sources/.../SourceRoot.kt` — sealed
`SourceRoot` (`displayName`, sorted `.java`/`.kt`-only `sourcePaths`,
`openSource`, `findSource` with `.java`-first candidates from the pure
`sourceCandidatesFor`; linear scan, `srcmap` indexing deferred per PROPOSAL
§10.4) + `JarSourceRoot` (normalised→raw entry map built once, so `openSource`
cannot be smuggled a raw `../`) + `DirSourceRoot` (walk + `startsWith` containment
check) + `openSourceRoot` dispatch + `SourceReadException`. `DirSourceRoot`
initially served any regular file under the dir; a failing tier-2 test pinned
the parity fix (only listed source kinds are servable). Entry-name hardening
twins `ZipSafety` locally with the no-`index`-dependency rationale in the KDoc.
Tests: 6 tier-1 (`SourcePathMappingTest`: 4 examples + 2 thousand-case
properties — nesting-collapse/`java`-first law, hostile-input never-throws) +
4 tier-2 (`SourceRootTest`: crafted hostile jar/dir, dispatch, real fixture
`-sources.jar` read incl. `Nesting$Inner → Nesting.java`). `sources/build.gradle.kts`
gained kotest-property + the `jdx.fixturesDir`/dependsOn wiring both suites need.
Standing bar: `test`+`tier2Test` 973 tests 0 failures; `:sources:check` green
incl. the 85 % line gate. Full `check` red only on `verifyTier1Budget`
(pre-existing machine variance per sessions 34/37/38 — slowest suites are
`JdkLayoutTest`/`DoctorEnvironmentTest`, none from this task; `:sources` tier-1
contributes 2.0 s). Next slice: T-021 (JavaParser body extraction over this seam).*

---

## Open questions

Add here when blocked. Format: `Q-nnn`, the question, why it blocks, and what you did instead.

*(none open)*

**Resolved**
- ~~**Q-001 — GitHub repository name?**~~ Answered 2026-09-13: **`jdx`**. Repo created at
  <https://github.com/MohammadMD1383/jdx>. See D-018.
