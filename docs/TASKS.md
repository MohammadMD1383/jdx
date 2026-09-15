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

**T-001, T-002, T-003, T-004, T-005 and T-061 are `DONE`.** M0's remaining tasks are
the **test spine: T-006** (fixture corpus) and **T-053** (test tier infrastructure),
both unblocked (depend only on T-001).

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

### T-053 — Test tier infrastructure · `TODO`
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
- [ ] `./gradlew check` passes on a machine with **no** local jar corpus
- [ ] A test tagged `soak` cannot run in tier 1 or 2 (prove with a deliberate test)
- [ ] Tier-1 duration is printed at the end of the run, with the slowest tests named
- [ ] All four commands in TESTING.md §13 exist and do what that section says

---

### T-054 — Golden-file test infrastructure · `TODO`
**Depends:** T-053 · **Files:** `core/src/test/kotlin/.../golden/*`

Helper comparing output to files under `src/test/resources/golden/`, rewritten by
`-Pgolden.update=true`.

**Acceptance**
- [ ] Failure output is a readable **unified diff**, not two blobs
- [ ] Update mode prints a summary of every file it rewrote, so a reviewer sees the blast
      radius before committing (TESTING.md §14)
- [ ] Golden files are plain text, committed, and readable in a PR diff
- [ ] An orphaned golden file (no test references it) fails the build

---

### T-055 — Property-based test infrastructure and shared generators · `TODO`
**Depends:** T-053, T-002 · **Files:** `core/src/test/kotlin/.../gen/*`

kotest-property wired in, with shared `Arb` generators for `TypeName`, `JvmDescriptor`,
`GenericSignature`, `ClassInfo` graphs (including **cyclic** ones), and member sets. Writing
these once pays for itself across every later property.

**Acceptance**
- [ ] ≥ 1,000 cases per property in tier 1 without breaking the 30 s budget
- [ ] The failing **seed is printed** and there is a documented one-line way to pin it as a
      regression test
- [ ] Generators produce genuinely nasty values: nested generics, wildcards, type variables,
      arrays of arrays, `$` in identifiers, unicode identifiers, empty packages
- [ ] The properties listed in TESTING.md §4 exist (or have tasks) — none silently dropped

---

### T-056 — `javap` differential harness · `TODO`
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

### T-007 — Artifact loading and sources pairing · `TODO`
**Depends:** T-002 · **Files:** `index/.../artifact/*`

Open jars, class dirs, and `jrt:/`. Implement the five sources-pairing rules from
PROPOSAL.md §5.2. Content-hash artifacts. Harden against zip-slip and zip bombs (D-017):
normalise entry names, reject traversal, cap decompressed size and entry count.

**Acceptance**
- [ ] Reads a jar, a class directory, and the running JDK's `jrt:/` uniformly through one
      interface
- [ ] Finds `gson-2.14.0-sources.jar` from `gson-2.14.0.jar` via the Gradle cache layout
- [ ] Multi-release jars: picks the right variant for the running JDK and emits
      `MULTI_RELEASE_VARIANT`
- [ ] A crafted zip-slip entry is rejected with a test proving it
- [ ] Hash of an unchanged jar is stable across runs and processes

---

### T-008 — ASM class reader → `ClassInfo`/`MemberInfo` · `TODO`
**Depends:** T-007, T-002 · **Files:** `index/.../asm/*`

`ClassReader` with `SKIP_FRAMES` (**not** `SKIP_DEBUG` — parameter names live there).
Extract access, kind, generic signature, supertypes, interfaces, annotations, nesting,
deprecation, source-file name, members with descriptors/signatures/throws/annotations/
default values, and parameter names from `MethodParameters` then `LocalVariableTable`.

**Acceptance**
- [ ] **Differential test vs `javap -p -s`**: for every fixture class, the member set and
      descriptors match exactly. This is the correctness oracle — make it a real test, not a
      manual check.
- [ ] Class-file major versions up to the running JDK parse; newer ones emit
      `UNSUPPORTED_CLASS_VERSION` instead of throwing
- [ ] A corrupt/truncated class file yields `CORRUPT_CLASS` and does not abort a whole jar
- [ ] No class from an inspected jar is ever loaded into the JVM (D-017) — assert by running
      with a `SecurityManager`-free check that the fixture's static initialiser side-effect
      file was never created

---

### T-009 — Member resolution with inheritance and generic substitution · `TODO`
**Depends:** T-008 · **Files:** `core/.../resolve/MemberResolver.kt`

Implement PROPOSAL.md §9.3 step by step. This is **the highest-value algorithm in the
project** — it is what saves an agent from walking hierarchies by hand. Treat it accordingly:
heavily tested, heavily commented, no cleverness.

**Acceptance**
- [ ] Linearisation is cycle-safe and deterministic
- [ ] `class StringList extends ArrayList<String>` reports `boolean add(String)`, not `add(E)`
- [ ] Overridden members collapse to the nearest declaration, with the overridden type
      recorded as metadata
- [ ] JLS visibility respected from the querying perspective (private supertype members
      excluded; package-private only when same package)
- [ ] Bridge/synthetic hidden by default, shown with `--include-synthetic`
- [ ] Spot-check against IntelliJ: `java.util.HashMap` inherited completion list matches

---

### T-010 — Text and JSON renderers · `TODO`
**Depends:** T-009 · **Files:** `core/.../render/*`

Two renderers over one result model, per PROPOSAL.md §8. Text follows the layout in D-007.
JSON follows the `{"jdx":1, ok, command, query, result, truncated, warnings, provenance}`
envelope.

**Acceptance**
- [ ] Anything present in text output is also present in JSON (no text-only information)
- [ ] Deterministic: identical bytes for identical input, sorted, no absolute paths, no
      timestamps, no hashes in default output
- [ ] ANSI only when `stdout` is a TTY; piping yields plain text
- [ ] Truncation never cuts mid-entity and always reports `shown`/`total`/`hint`
- [ ] Golden tests for both renderers on every fixture

---

### T-011 — `jdx show`, `jdx outline`, `jdx members` · `TODO`
**Depends:** T-010, T-003 · **Files:** `cli/.../commands/*`

Wire the three read commands through Clikt. **No logic in the command classes** (D-004) —
they parse flags, call `JdxService`, hand the result to a renderer, map to an exit code.

**Acceptance**
- [ ] All flags from PROPOSAL.md §7.1 for these commands are implemented or explicitly
      rejected with a "not yet implemented" message naming the task that will add them
- [ ] `--inherited` is the default for `members`; `java.lang.Object` members collapse to one
      line by default
- [ ] Exit codes follow D-015; ambiguity follows D-016 with candidate listing
- [ ] `jdx members java.util.HashMap` works with **no** flags, via the JDK jrt root
- [ ] Golden tests for each command, text and JSON

---

### T-012 — JDK stdlib root via `jrt-fs` + `src.zip` · `TODO`
**Depends:** T-007 · **Files:** `index/.../artifact/JrtRoot.kt`

Expose the running JDK's modules as a root, paired with `$JAVA_HOME/lib/src.zip` as its
sources. Appended to every workspace unless `--no-jdk` (D-006).

**Acceptance**
- [ ] `jdx show java.util.HashMap` works with zero configuration
- [ ] JDK source is found when `src.zip` is present, absent gracefully when it is not
      (some distributions omit it) — emit a WARN row in `doctor`, not an error
- [ ] Module name is reported as the artifact (e.g. `java.base`)

---

# M2 — Index

### T-013 — `IndexStore` interface + SQLite implementation · `TODO`
**Depends:** T-008 · Schema in PROPOSAL.md §10.3. WAL mode, schema versioning with a
migration path, artifact-scoped rows (D-013). **Keep SQLite behind the interface** — no SQL
outside the implementation package.

### T-014 — Parallel indexer · `TODO`
**Depends:** T-013 · Virtual-thread fan-out across artifacts, batched transactions,
content-hash short-circuit, progress reporting for long runs. Target ≥3,000 classes/s.

### T-015 — Workspaces (`jdx ws …`) · `TODO`
**Depends:** T-013 · TOML at `~/.config/jdx/workspaces/<name>.toml`, ordered roots,
classpath-order shadowing, `DUPLICATE_FQN` warnings, `jdx ws use`, `JDX_WORKSPACE`.

### T-016 — Project auto-discovery · `TODO`
**Depends:** T-015 · Walk up for `settings.gradle(.kts)`/`build.gradle(.kts)`/`pom.xml`/
`.idea`; derive roots per PROPOSAL.md §13 step 4. **Must not run Gradle or Maven** (N3).
Cache derived workspace, invalidate on build-file change.

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

## Open questions

Add here when blocked. Format: `Q-nnn`, the question, why it blocks, and what you did instead.

*(none open)*

**Resolved**
- ~~**Q-001 — GitHub repository name?**~~ Answered 2026-09-13: **`jdx`**. Repo created at
  <https://github.com/MohammadMD1383/jdx>. See D-018.
