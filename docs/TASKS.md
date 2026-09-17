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

**T-001 through T-015, T-053, T-054, T-055 and T-061 are `DONE`.** M0's test spine is
complete; T-056…T-060 unblock as their milestones land. M1 starts at T-007.

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

### T-056 — `javap` differential harness · `WIP`
**Depends:** T-006, T-008 · **Files:** `index/src/test/kotlin/.../differential/*`

**The highest-value test in the project** (TESTING.md §5.1). Parse `javap -p -s` output into
a member set and diff it against `jdx members --declared --access all --include-synthetic
--json` for every fixture class and a sampled slice of the real corpus.

**Acceptance**
- [ ] Runs over the whole fixture corpus in tier 2
- [ ] Runs over a seeded random sample of the local jar corpus in tier 3
- [ ] Disagreements report **which member and which field differs**, not just "sets differ"
- [ ] Skips gracefully with a clear message when `javap` is absent (don't fail the build)
- [ ] Known, documented `javap` quirks live in one allowlist file with a comment each —
      an unexplained entry in that file is a review blocker

---

### T-057 — Fault-injection suite · `TODO`
**Depends:** T-007 · **Files:** `index/src/test/kotlin/.../fault/*`

Generate malformed inputs programmatically rather than collecting them by hand. Full list in
TESTING.md §7.

**Acceptance**
- [ ] Every case in TESTING.md §7 has a test
- [ ] Each asserts **both** a documented exit code (D-015) **and** that the output names the
      problem
- [ ] **No stack trace ever reaches stdout or stderr** — asserted, for every case
- [ ] Zip-slip and zip-bomb cases prove the D-017 defences, not just that nothing crashed
- [ ] Truncation cases are generated (cut at every 10 % boundary), not hand-picked

---

### T-058 — Metamorphic test suite · `TODO`
**Depends:** T-009 · **Files:** `core/src/test/kotlin/.../metamorphic/*`

Relations that must hold between answers (TESTING.md §6). These catch the deep resolver and
index bugs that example-based tests never reach. Start with the resolver relations; extend as
`hierarchy`/`usages`/`callers` land in M4.

**Acceptance**
- [ ] Every relation listed in TESTING.md §6 is implemented or has a task naming the
      milestone that will add it
- [ ] Determinism relations (`run twice ⟹ identical bytes`, `index twice ⟹ identical rows`)
      are included — they protect the D-007 promise
- [ ] Runs over the fixture corpus in tier 2 and a corpus sample in tier 3

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

### T-062 — Member sort orders (`--sort name|declaring`) · `TODO`
**Depends:** T-011 · **Files:** `core/.../render/*`, `cli/.../commands/*`
*(Added in session 14: T-011 accepts `--sort kind` (the current kind-then-name
layout) and rejects anything else naming this task.)*

`members`/`outline` row ordering beyond the default: `--sort name` (flat
name-first order across kinds/groups) and `--sort declaring` (declaring-type
order semantics where they differ from linearisation). Decide the exact layouts,
extend `MemberListingOptions`, and pin both with goldens.

**Acceptance**
- [ ] `--sort name|kind|declaring` all accepted by `members` and `outline`
- [ ] Text and JSON agree on the order; determinism property holds per order
- [ ] Golden files cover all three orders on at least one generic, one nested
      and one Kotlin fixture class

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

### T-064 — Close the indexer 3,000/s end-to-end gap · `TODO`
**Depends:** T-014 · **Files:** `index/.../store/sqlite/SqliteIndexStore.kt`

*Session-19 measurement: full-JDK (33,100 classes) end-to-end 2,502/s cold —
read pass 5–6k/s, write pass ~4.3k/s after `BulkWriter`. The remainder is one
JNI round-trip per row (~760k for the JDK, half of them `last_insert_rowid`
queries). True JDBC batching needs client-side id assignment, which races across
processes under WAL — do not attempt without solving that. Natural home is the
T-050 bench context with `minecraft-client.jar` as the fixture.*

**Acceptance**
- [ ] End-to-end ≥ 3,000 classes/s on the benchmark jar, or a documented reason
      why the target moved
- [ ] No behaviour change: T-013 round-trip tests and T-014 indexer tests green
      unmodified

### T-065 — Handle JFR-style `$$` class names · `TODO`
**Depends:** T-008 · **Files:** `core/.../model/TypeName.kt`, `index/.../asm/*`

*Session-19 find: the running JDK ships `java/lang/Exception$JB$$Assertion`
(and `$Event`, `$FullGC`, `$ShrinkingGC`) — empty name segments the model
rejects, so they index as `CORRUPT_CLASS` warnings. That is honest degradation,
but they are the only 4 of 33,104 JDK classes we cannot name. Decide: accept
empty segments in the model, or keep rejecting with a dedicated warning code.*

**Acceptance**
- [ ] The four JFR classes resolve to a named `TypeName` or a documented,
      dedicated warning explaining why not
- [ ] Differential vs `javap` still green; no regression on `$` nesting rules (D-025)

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

### T-017 — `jdx search`, `jdx resolve`, `jdx ls`, `jdx tree` · `TODO`
**Depends:** T-014 · Glob, regex, and IntelliJ-style camel-hump matching; `--fuzzy`
Levenshtein fallback; the "did you mean" path from PROPOSAL.md §16.

### T-059 — Corpus soak harness · `TODO`
**Depends:** T-014, T-053 · **Files:** `index/src/test/kotlin/.../soak/*`

Tier 3. Run every implemented command over the real local jar corpus (~2,183 jars) and assert
**invariants, not values** — it cannot know the right answer for 2,183 jars, but it knows
`jdx` must never crash, never emit invalid JSON, and never be non-deterministic.
See `docs/TESTING.md` §8.

**Acceptance**
- [ ] Per sampled class: exit code ∈ {0,1,2} (never 5 or 6); no exception text in
      stdout/stderr; `--json` validates against the envelope schema; text and JSON carry the
      same entity set; running twice yields identical bytes
- [ ] `-Pcorpus=<dir>` lets a contributor without the owner's cache point it at `~/.m2`
- [ ] Seeded sampling, so a failure is **reproducible** from the printed seed
- [ ] Emits a **warning-code histogram** as a build artifact — "247 jars emitted
      `MULTI_RELEASE_VARIANT`" is how we learn which real-world shapes matter. Record the
      histogram in `docs/PROGRESS.md` each time it changes materially.
- [ ] Excluded from `check`; never required for a green build on a fresh machine

---

### T-018 — `jdx cache info|gc|clear` · `TODO`
**Depends:** T-013 · Size reporting, eviction of artifacts no workspace references.

### T-019 — Maven coordinate resolution and opt-in fetching · `TODO`
**Depends:** T-015 · Resolve from `~/.gradle/caches` and `~/.m2` first; fetch from Maven
Central into `~/.cache/jdx/m2/` **only** with `--fetch`; verify checksums; fetch the
`-sources.jar` too.

---

# M3 — Bodies
### T-020 Sources-jar/dir access and `srcmap` · **T-021** JavaParser integration and Java body extraction · **T-022** `jdx body` · **T-023** `jdx source` · **T-024** `jdx signature` · **T-025** `jdx doc` incl. inherited javadoc · **T-026** `DecompilerEngine` interface + Vineflower (isolated lazy classloader, on-disk cache) · **T-027** `javap` engine · **T-028** `SOURCES_VERSION_MISMATCH` detection
*(Expand into detail blocks when M3 starts.)*

# M4 — Graph
### T-029 Reference-edge extraction · **T-030** `jdx usages` · **T-031** project source-dir usages · **T-032** `jdx hierarchy` / `implementors` · **T-033** `jdx callers` / `calls --depth` · **T-034** `jdx samples` with exemplariness ranking

# M5 — Kotlin
### T-035 `@Metadata` decoding · **T-036** Kotlin member mapping (properties, default args, suspend) · **T-037** `--view jvm` · **T-038** side-loaded Kotlin PSI module (D-008 mitigations 1–5 are acceptance criteria) · **T-039** Kotlin source body extraction

# M6 — Serving
### T-040 `JdxService` RPC protocol · **T-041** daemon + unix socket + 5-min idle shutdown (D-004) · **T-042** transparent CLI daemon client + `--no-daemon` · **T-043** MCP stdio server with generated schemas · **T-044** HTTP/JSON server on `com.sun.net.httpserver` · **T-045** `jdx batch` · **T-046** adapter parity test (CLI/HTTP/MCP byte-identical payloads)

# M7 — Polish
### T-060 — Mutation testing and coverage gates · `TODO`
**Depends:** T-055, T-056 · Pitest wired as tier 4. Gates per `docs/TESTING.md` §10:
`core` ≥ 95 % line and **≥ 80 % mutation score** (build-failing); `index`/`sources`/
`decompile` ≥ 85 % line with mutation measured and reported; `cli`/`mcp`/`server`
**deliberately ungated** — they are thin by rule (D-004) and gating them would only
incentivise padding. Surviving mutants in `core` are a gap: kill them with a test, or delete
the unreachable code.

---

### T-047 token budgets (`--max-lines`, `--brief`) · **T-048** AppCDS archive generation · **T-049** `jdx help --agent` · **T-050** `jdx bench` against `minecraft-client.jar` · **T-051** README + install docs · **T-052** warnings-as-errors, lint, final API review

---

### T-063 — Share the fixture-jar resolution helpers · `TODO`
**Depends:** — · **Files:** `core/.../fixtures/Fixtures.kt`, `index/.../render/RendererGoldenTest.kt`, `cli/.../commands/ReadCommandsGoldenTest.kt`
*(Added in session 17: found while migrating golden suites to the T-054 helper.)*

`fixtureBinaryJar()` + `fixtureClassNames()` are now triplicated: the canonical
`Fixtures` object (core test source set, not consumable from other modules)
plus private copies in the index and cli golden tests. Promote one copy next
to the T-054 helper (`testfixtures` `testFixtures` source set) and delete the
copies — the same jar-count trap from L-029 is waiting in each copy.

**Acceptance**
- [ ] Index and cli golden tests resolve jars/classes through the shared helper
- [ ] `core`'s `Fixtures` either delegates to it or documents why it cannot
- [ ] No test hard-codes an absolute jar path (existing rule, still enforced)

---

### T-066 — Make `ReadCommandsTest` hermetic to the machine's workspaces · `TODO`
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

## Open questions

Add here when blocked. Format: `Q-nnn`, the question, why it blocks, and what you did instead.

*(none open)*

**Resolved**
- ~~**Q-001 — GitHub repository name?**~~ Answered 2026-09-13: **`jdx`**. Repo created at
  <https://github.com/MohammadMD1383/jdx>. See D-018.
