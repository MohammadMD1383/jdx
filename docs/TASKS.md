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
| **M0** | Skeleton: build, launcher, `doctor`, `version`, **test spine** | DONE |
| **M1** | Read path: model, refs, `show`/`outline`/`members --inherited` | DONE |
| **M2** | Index: SQLite, `search`, workspaces, auto-discovery | DONE |
| **M3** | Bodies: sources, JavaParser, Vineflower, `body`/`source`/`doc` | DONE |
| **M4** | Graph: `usages`/`hierarchy`/`callers`/`calls`/`samples` | DONE (T-029…T-034) |
| **M5** | Kotlin: `@Metadata` + PSI source parsing | TODO |
| **M6** | Serving: daemon, MCP, HTTP, `batch` | TODO |
| **M7** | Polish: token budgets, AppCDS, mutation gates, docs, install | TODO (T-060 done early) |

**DONE: T-001…T-035** (M0–M2 in full; M3 via the T-020 umbrella's slices —
T-071 + T-021…T-028 + T-072/T-073; M4 via T-029…T-034; M5 opened with T-035) **plus T-053…T-073
plus T-076** (first T-036 slice) **plus T-077** (second T-036 slice) **plus T-078** (third T-036 slice)
**plus T-037** (`--view jvm`, last T-036 slice) **plus T-074** (T-020 `srcmap`
remainder).
DONE entries below are
compressed to a summary + pointers; full notes live in git history and the
session log. **TODO: T-075** (`usages` graph enrichment), **T-079** (annotation-element
member matching), **WIP: T-036** (member mapping, T-037 DONE session 60, T-038/T-039 PSI/bodies next),
**plus M5–M7** (coarse; T-060 already DONE).

---

# M0 — Skeleton

Goal: a build that produces a runnable `jdx` binary that can report on its own environment.
Everything after this assumes it exists.

### T-001 — Gradle multi-project skeleton · `DONE` (session 2)

**Depends:** — · **Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `*/build.gradle.kts`

Create the Gradle wrapper and the module structure from CLAUDE.md §4: `core`, `index`,
`sources`, `decompile`, `cli`, `mcp`, `server`, `app`.

*Closed in session 2. Full notes in git history + session log.*

### T-002 — Dependency-free `core` module foundations · `DONE` (session 3)

**Depends:** T-001 · **Files:** `core/src/main/kotlin/dev/jdx/core/model/*`

The domain model. **`core` must not depend on ASM, SQLite, JavaParser, or any IO.** It is the
vocabulary every other module speaks, and it must stay unit-testable with no fixtures.

*Closed in session 3. Full notes in git history + session log.*

### T-003 — Symbol reference parser and printer · `DONE` (session 5)

**Depends:** T-002 · **Files:** `core/.../ref/SymbolRefParser.kt`, `SymbolRefPrinter.kt`

Implement PROPOSAL.md §6 exactly. Generous input, canonical output.

*Closed in session 5. Decisions: D-025. Full notes in git history + session log.*

### T-004 — `app` module: fat jar + `jdx` launcher script · `DONE` (session 6)

**Depends:** T-001 · **Files:** `app/build.gradle.kts`, `app/src/main/scripts/jdx`, `install.sh`

- Shadow/fat jar of `cli` and its dependencies.
- A POSIX `sh` launcher that: resolves a JDK (`JAVA_HOME` → `java` on `PATH` →
  `/usr/lib/jvm/default`), errors clearly if the JDK is too old, passes through all args,
  and applies one-shot JVM flags (`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto`).
- `install.sh` symlinks into `~/.local/bin`. **Must not** require root, and must refuse to
  overwrite an existing unrelated `jdx` without `--force`.

*Closed in session 6. Decisions: D-026. Full notes in git history + session log.*

### T-005 — `jdx version` and `jdx doctor` · `DONE` (session 7)

**Depends:** T-004 · **Files:** `cli/.../commands/VersionCommand.kt`, `DoctorCommand.kt`

`doctor` is the project's self-diagnosis and the first thing a confused contributor or agent
should run. Report, each with OK/WARN/FAIL:
JDK version + path + whether `jrt-fs` is reachable; `javap` presence and version; cache dir
path, existence, writability, size; config dir; index DB presence, schema version, artifact
count; whether the Kotlin source module is installed (D-008); whether a daemon is running;
detected project/workspace for the CWD.

*Closed in session 7. Decisions: D-027. Full notes in git history + session log.*

### T-006 — Fixture corpus · `DONE` (session 8)

**Depends:** T-001 · **Files:** `testfixtures/`, `core/src/test/kotlin/.../Fixtures.kt`

A `testfixtures` source set of deliberately nasty Java and Kotlin classes, compiled by Gradle
at test time into **both** a binary jar and a sources jar. Nine of the ten test families in
`docs/TESTING.md` lean on this, so it is built early and built well. See TESTING.md §11 for
the required contents.

*Closed in session 8. Decisions: D-017. Full notes in git history + session log.*

### T-053 — Test tier infrastructure · `DONE` (session 9)

**Depends:** T-001 · **Files:** convention plugin / `build.gradle.kts`
*(Added in session 1 after D-020. Numbered T-053 per the board rule: new tasks take the next
free number even when they belong to an earlier milestone.)*

Wire the four tiers from `docs/TESTING.md` §2 so contributors can't accidentally put a slow
test in the fast loop.

*Closed in session 9. Full notes in git history + session log.*

### T-054 — Golden-file test infrastructure · `DONE` (session 17)

**Depends:** T-053 · **Files:** `testfixtures/src/testFixtures/.../golden/*`, `testfixtures/src/test/.../golden/*`

Helper comparing output to files under `src/test/resources/golden/`, rewritten by
`-Pgolden.update=true`.

*Closed in session 17. Lessons: L-029. Full notes in git history + session log.*

### T-055 — Property-based test infrastructure and shared generators · `DONE` (session 21)

**Depends:** T-053, T-002 · **Files:** `core/src/test/kotlin/.../gen/*`

kotest-property wired in, with shared `Arb` generators for `TypeName`, `JvmDescriptor`,
`GenericSignature`, `ClassInfo` graphs (including **cyclic** ones), and member sets. Writing
these once pays for itself across every later property.

*Closed in session 21. Lessons: L-036, L-037, L-038. Full notes in git history + session log.*

### T-056 — `javap` differential harness · `DONE` (session 23)

**Depends:** T-006, T-008 · **Files:** `index/src/test/kotlin/.../differential/*`

**The highest-value test in the project** (TESTING.md §5.1). Parse `javap -p -s` output into
a member set and diff it against `jdx members --declared --access all --include-synthetic
--json` for every fixture class and a sampled slice of the real corpus.

*Closed in session 23. Lessons: L-042, L-043. Full notes in git history + session log.*

### T-057 — Fault-injection suite · `DONE` (session 26)

**Depends:** T-007 · **Files:** `index/src/test/kotlin/.../fault/*`

Generate malformed inputs programmatically rather than collecting them by hand. Full list in
TESTING.md §7.

*Closed in session 26. Decisions: D-015, D-017. Full notes in git history + session log.*

### T-058 — Metamorphic test suite · `DONE` (session 27)

**Depends:** T-009 · **Files:** `core/src/test/kotlin/.../metamorphic/*`, `index/src/test/kotlin/.../metamorphic/*`

Relations that must hold between answers (TESTING.md §6). These catch the deep resolver and
index bugs that example-based tests never reach. Start with the resolver relations; extend as
`hierarchy`/`usages`/`callers` land in M4.

*Closed in session 27. Decisions: D-007. Full notes in git history + session log.*

### T-061 — Shard the growing docs; add the lessons log (D-023, D-024) · `DONE` (session 4)

**Depends:** — · **Files:** `docs/PROGRESS.md`, `docs/progress/*`, `docs/DECISIONS.md`,
`docs/decisions/*`, `docs/LESSONS.md`, `docs/lessons/*`, `CLAUDE.md`, `CONTRIBUTING.md`

*(Owner-directed, added in session 4.)* Append-only logs are split into shard files
behind small entry files (index + rules), so agents read only what they need. Session
shards hold 10 sessions; lessons and decisions shards hold 25 entries each.

*Closed in session 4. Decisions: D-001, D-024. Lessons: L-001, L-009. Full notes in git history + session log.*

# M1 — Read path

Goal: `jdx show` / `outline` / `members --inherited` answer correctly from real jars, with no
index and no sources. This milestone alone already beats `javap` for the agent's main loop.

### T-007 — Artifact loading and sources pairing · `DONE` (session 10)

**Depends:** T-002 · **Files:** `index/.../artifact/*`

Open jars, class dirs, and `jrt:/`. Implement the five sources-pairing rules from
PROPOSAL.md §5.2. Content-hash artifacts. Harden against zip-slip and zip bombs (D-017):
normalise entry names, reject traversal, cap decompressed size and entry count.

*Closed in session 10. Decisions: D-017. Full notes in git history + session log.*

### T-008 — ASM class reader → `ClassInfo`/`MemberInfo` · `DONE` (session 11)

**Depends:** T-007, T-002 · **Files:** `index/.../asm/*`

`ClassReader` with `SKIP_FRAMES` (**not** `SKIP_DEBUG` — parameter names live there).
Extract access, kind, generic signature, supertypes, interfaces, annotations, nesting,
deprecation, source-file name, members with descriptors/signatures/throws/annotations/
default values, and parameter names from `MethodParameters` then `LocalVariableTable`.

*Closed in session 11. Decisions: D-017. Full notes in git history + session log.*

### T-009 — Member resolution with inheritance and generic substitution · `DONE` (session 12)

**Depends:** T-008 · **Files:** `core/.../resolve/MemberResolver.kt`

Implement PROPOSAL.md §9.3 step by step. This is **the highest-value algorithm in the
project** — it is what saves an agent from walking hierarchies by hand. Treat it accordingly:
heavily tested, heavily commented, no cleverness.

*Closed in session 12. Lessons: L-024. Full notes in git history + session log.*

### T-010 — Text and JSON renderers · `DONE` (session 13)

**Depends:** T-009 · **Files:** `core/.../render/*`

Two renderers over one result model, per PROPOSAL.md §8. Text follows the layout in D-007.
JSON follows the `{"jdx":1, ok, command, query, result, truncated, warnings, provenance}`
envelope.

*Closed in session 13. Decisions: D-028. Full notes in git history + session log.*

### T-011 — `jdx show`, `jdx outline`, `jdx members` · `DONE` (session 14)

**Depends:** T-010, T-003 · **Files:** `cli/.../commands/*`

Wire the three read commands through Clikt. **No logic in the command classes** (D-004) —
they parse flags, call `JdxService`, hand the result to a renderer, map to an exit code.

*Closed in session 14. Decisions: D-004, D-015, D-016, D-017. Lessons: L-026, L-027. Full notes in git history + session log.*

### T-062 — Member sort orders (`--sort name|declaring`) · `DONE` (session 31)

**Depends:** T-011 · **Files:** `core/.../render/Listing.kt`, `index/.../service/JdxService.kt`,
`cli/.../commands/ReadCommands.kt`, `ReadCommandSupport.kt`

`members`/`outline` row ordering beyond the default: `--sort name` (flat
name-first order across kinds/groups) and `--sort declaring` (declaring-type
order semantics where they differ from linearisation). Decide the exact layouts,
extend `MemberListingOptions`, and pin both with goldens.

*Closed in session 31. Decisions: D-004, D-007, D-033. Full notes in git history + session log.*

### T-012 — JDK stdlib root via `jrt-fs` + `src.zip` · `DONE` (session 15)

**Depends:** T-007 · **Files:** `index/.../artifact/JdkLayout.kt`, `JrtArtifact.kt`, `ArtifactLoader.kt`, `cli/.../service/DoctorService.kt`

Expose the running JDK's modules as a root, paired with `$JAVA_HOME/lib/src.zip` as its
sources. Appended to every workspace unless `--no-jdk` (D-006).

*Closed in session 15. Full notes in git history + session log.*

# M2 — Index

### T-013 — `IndexStore` interface + SQLite implementation · `DONE` (session 18)

**Depends:** T-008 · Schema in PROPOSAL.md §10.3. WAL mode, schema versioning with a
migration path, artifact-scoped rows (D-013). **Keep SQLite behind the interface** — no SQL
outside the implementation package.

*Closed in session 18. Lessons: L-024. Full notes in git history + session log.*

### T-014 — Parallel indexer · `DONE` (session 19)

**Depends:** T-013 · Virtual-thread fan-out across artifacts, batched transactions,
content-hash short-circuit, progress reporting for long runs. Target ≥3,000 classes/s.

*Closed in session 19. Full notes in git history + session log.*

### T-064 — Close the indexer 3,000/s end-to-end gap · `DONE` (session 33)

**Depends:** T-014 · **Files:** `index/.../store/sqlite/SqliteIndexStore.kt`

*Session-19 measurement: full-JDK (33,100 classes) end-to-end 2,502/s cold —
read pass 5–6k/s, write pass ~4.3k/s after `BulkWriter`. The remainder is one
JNI round-trip per row (~760k for the JDK, half of them `last_insert_rowid`
queries). True JDBC batching needs client-side id assignment, which races across
processes under WAL — do not attempt without solving that. Natural home is the
T-050 bench context with `minecraft-client.jar` as the fixture.*

*Closed in session 33. Lessons: L-059. Full notes in git history + session log.*

### T-065 — Handle JFR-style `$$` class names · `DONE` (session 34)

**Depends:** T-008 · **Files:** `core/.../model/TypeName.kt`, `index/.../asm/*`

*Session-19 find: the running JDK ships `java/lang/Exception$JB$$Assertion`
(and `$Event`, `$FullGC`, `$ShrinkingGC`) — empty name segments the model
rejects, so they index as `CORRUPT_CLASS` warnings. That is honest degradation,
but they are the only 4 of 33,104 JDK classes we cannot name. Decide: accept
empty segments in the model, or keep rejecting with a dedicated warning code.*

*Closed in session 34. Decisions: D-025. Lessons: L-060. Full notes in git history + session log.*

### T-015 — Workspaces (`jdx ws …`) · `DONE` (session 20)

**Depends:** T-013 · TOML at `~/.config/jdx/workspaces/<name>.toml`, ordered roots,
classpath-order shadowing, `DUPLICATE_FQN` warnings, `jdx ws use`, `JDX_WORKSPACE`.

*Closed in session 20. Decisions: D-027, D-029. Full notes in git history + session log.*

### T-016 — Project auto-discovery · `DONE` (session 22)

**Depends:** T-015 · Walk up for `settings.gradle(.kts)`/`build.gradle(.kts)`/`pom.xml`/
`.idea`; derive roots per PROPOSAL.md §13 step 4. **Must not run Gradle or Maven** (N3).
Cache derived workspace, invalidate on build-file change.

*Closed in session 22. Decisions: D-030. Full notes in git history + session log.*

### T-017 — `jdx search`, `jdx resolve`, `jdx ls`, `jdx tree` · `DONE` (session 24)

**Depends:** T-014 · Glob, regex, and IntelliJ-style camel-hump matching; `--fuzzy`
Levenshtein fallback; the "did you mean" path from PROPOSAL.md §16.

*Closed in session 24. Decisions: D-031. Lessons: L-044, L-045. Full notes in git history + session log.*

### T-059 — Corpus soak harness · `DONE` (session 29)

**Depends:** T-014, T-053 · **Files:** `index/src/test/kotlin/.../soak/*`

Tier 3. Run every implemented command over the real local jar corpus (~2,183 jars) and assert
**invariants, not values** — it cannot know the right answer for 2,183 jars, but it knows
`jdx` must never crash, never emit invalid JSON, and never be non-deterministic.
See `docs/TESTING.md` §8.

*Closed in session 29. Decisions: D-006. Lessons: L-054. Full notes in git history + session log.*

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

*Closed in session 25. Decisions: D-007. Full notes in git history + session log.*

### T-019 — Maven coordinate resolution and opt-in fetching · `DONE` (session 28)

**Depends:** T-015 · Resolve from `~/.gradle/caches` and `~/.m2` first; fetch from Maven
Central into `~/.cache/jdx/m2/` **only** with `--fetch`; verify checksums; fetch the
`-sources.jar` too.

*Closed in session 28. Decisions: D-032. Lessons: L-052. Full notes in git history + session log.*

### T-069 — Configurable Maven repositories (`--repo`) · `DONE` (session 37)

**Depends:** T-019 · **Files:** `cli/.../commands/*`, `index/.../maven/*`
*(Added in session 28: D-032 §6 deferred it.)*

`MavenResolver.Repositories.repoBaseUrl` is code-configurable but the CLI only
speaks Maven Central. Add `--repo <url>` (repeatable?) to the read commands
and `ws create`, flowing into resolution and fetch URLs.

*Closed in session 37. Decisions: D-032, D-034. Full notes in git history + session log.*

# M3 — Bodies (DONE — umbrella closed by its slices below; `srcmap` remainder tracked as T-074)
### T-020 Sources-jar/dir access → T-071 (srcmap remainder → T-074) · JavaParser body extraction → T-021 · `jdx body` → T-022 · `jdx source` → T-023 · `jdx signature` → T-024 · `jdx doc` → T-025 · decompilers → T-026/T-027/T-073 · `SOURCES_VERSION_MISMATCH` → T-028 · `--with-doc` → T-072

### T-028 — `SOURCES_VERSION_MISMATCH` detection · `DONE` (session 47)

**Depends:** T-021 (`listJavaMembers` seam), T-022/T-023/T-025 (body/source/doc patterns) · **Files:** `sources/.../SourcesMismatch.kt`, `JavaBodies.kt`, `index/.../service/JdxService.kt`
*(Last M3 one-liner: turn the `WarningCode.SOURCES_VERSION_MISMATCH` enum (D-009, PROPOSAL.md §11.1) from a dead code into an emitted warning. Structure stays bytecode-authoritative: successful `body`/`source`/`doc` answers from paired Java sources carry the warning when the sources declare a different member set; missing-member/type failures keep exit 1 with the code named.)*

Emit a structured `SOURCES_VERSION_MISMATCH` warning on successful sources-backed `body`/`source`/`doc` when bytecode and sources disagree; keep exit codes unchanged.

*Closed in session 47. Decisions: D-040. Lessons: L-078, L-079. Follow-up split to T-074 (`srcmap` remainder). Full notes in git history + session log.*

### T-027 — `javap` engine for `body`/`source` · `DONE` (session 46)

**Depends:** T-022/T-023 (body/source patterns), T-026 (`DecompilerEngine` seam + forced-engine plumbing), T-011/T-015/T-016 (roots) · **Files:** `decompile/.../JavapDecompiler.kt`, `JavapOutput.kt`, `DecompilerEngine.kt`, `index/.../service/JdxService.kt`, `cli/.../commands/BodyCommand.kt`, `SourceCommand.kt`
*(Second and last decompiler slice of M3: the `--engine javap` opcode path
PROPOSAL.md §11.2 promises ("Show Bytecode" action). Structure stays
bytecode-authoritative (D-009): overload ambiguity is decided from bytecode
before `javap` runs. No auto-fallback: a Vineflower failure stays exit 1
naming the now-working hatch (filed as T-073); Kotlin `.kt`-only roots still
degrade to T-039; source-missing-member still names T-028.)*

Shell out to the system `javap` (resolved `JAVA_HOME` → `java.home` →
`PATH`, mirroring `doctor`'s check) with `javap -c -p -s` over the winning
class's staged bytes, and serve the disassembly through the `body`/`source`
renderers with `DECOMPILED_JAVAP` provenance. `--engine javap` forces the
disassembly path even when sources are paired.

*Closed in session 46. Decisions: D-007, D-039. Lessons: L-076, L-077. Full notes in git history + session log.*

### T-073 — Vineflower→javap auto-fallback on engine failure · `DONE`

**Depends:** T-027 · **Files:** `index/.../service/JdxService.kt`
*(Split out of T-027 in session 46: the default ladder (sources → Vineflower)
degrades automatically, but a Vineflower failure still exits 1 naming the
`--engine javap` hatch instead of retrying through `javap` — TESTING.md §7
("decompiler timeout and crash must fall back to `javap`") wants the
automatic step.)*

When the default-ladder Vineflower reconstruction fails (timeout, crash,
unparseable output), retry the same query through the T-027 `javap` engine
before exiting 1 — so the ladder reads sources → vineflower → javap →
signatures → not found, per PROPOSAL.md §16. Forced `--engine vineflower`
stays strict (its failure still exits 1 naming the hatch).

*Closed in session 49 (worktree). Full notes in git history + session log.*

### T-026 — `DecompilerEngine` + Vineflower fallback for `body`/`source` · `DONE` (session 45)

**Depends:** T-021 (MemorySourceRoot + JavaParser slicing seam), T-022/T-023 (body/source patterns), T-011/T-015/T-016 (roots) · **Files:** `decompile/.../DecompilerEngine.kt`, `VineflowerDecompiler.kt`, `DecompileCache.kt`, `core/.../render/Body.kt`, `Source.kt`, `index/.../service/JdxService.kt` (+ `index/build.gradle.kts`), `cli/.../commands/BodyCommand.kt`, `SourceCommand.kt`
*(First decompiler slice of M3: Vineflower only. `--engine javap` stays parked on
T-027; Kotlin `.kt`-only roots still degrade to T-039; source-missing-member
still names T-028; `doc` gets no decompiled path — decompiled text carries no
javadoc. Structure stays bytecode-authoritative (D-009): overload ambiguity is
decided from bytecode before any decompilation runs.)*

Wire PROPOSAL.md §11.2: a `DecompilerEngine` interface in `decompile` with a
Vineflower implementation (single-class, workspace jars as library context,
on-disk cache, wall-clock timeout, lazy isolated loading), and fall back to it
from `JdxService.body`/`source` when no paired sources exist. `--engine
vineflower` forces the decompiled path even when sources are paired.

*Closed in session 45. Decisions: D-007, D-038. Lessons: L-075, L-076. Full notes in git history + session log.*

### T-025 — `jdx doc` over the T-021 seam · `DONE` (session 44)

**Depends:** T-021, T-022 (body patterns), T-011 (read-command patterns), T-015/T-016 (roots) · **Files:** `sources/.../JavaDocs.kt`, `core/.../render/Doc.kt`, `index/.../service/JdxService.kt` (`doc`), `cli/.../commands/DocCommand.kt`
*(Fourth CLI slice of M3: javadoc rendering from paired Java sources only, wired to
`jdx doc`. No decompilation (T-026/T-027), no Kotlin KDoc (T-039), no
`SOURCES_VERSION_MISMATCH` formalism (T-028 — best-effort message only), no
`--with-doc` enrichment of `body`/`members`/`outline` (filed as T-072).
Structure stays bytecode-authoritative (D-009): the type resolves from
bytecode before sources are read, overload ambiguity is decided from bytecode.)*

Wire the T-021 `loadJavaUnit` seam to the CLI: `JdxService.doc(rawRef,
roots, opts)` resolves the type or member with the T-011 machinery (exact/
short-name matching, `g:a:v` scope, `DUPLICATE_FQN`), then renders the
winning root's paired-sources javadoc (sibling `-sources.jar`,
Gradle/`~/.m2` cache layouts, embedded sources, `src.zip` for the JDK) as
plain text with provenance. Undocumented members fall back to the nearest
documenting supertype (IntelliJ quick-doc semantics), labelled as such.

*Closed in session 44. Decisions: D-007, D-009, D-017, D-037. Lessons: L-072, L-073, L-074. Full notes in git history + session log.*

### T-072 — Wire `--with-doc` into `body`/`members`/`outline` over the T-025 seam · `DONE` (session 48)

**Depends:** T-025 · **Files:** `cli/.../commands/BodyCommand.kt`, `ReadCommands.kt`, `ReadCommandSupport.kt`, `core/.../render/*`, `index/.../service/JdxService.kt`
*(Split out of T-025 in session 44: per-row/per-body javadoc enrichment is a
different scale from one-shot `jdx doc` — `members`/`outline` need the first
sentence per row across a listing, `body` a doc block beside the slice.)*

Reuse the T-025 extraction + rendering: `members`/`outline --with-doc`
(first javadoc sentence per member) and `body --with-doc` (member doc).
Repoint the parked exit-3 messages (currently naming T-025 in
`BodyCommand.validateBodyFlags` and `ReadCommandSupport.validateMemberFlags`,
pinned by `BodyCommandTest` + `ReadCommandsTest`) at this task when started.

*Closed in session 48. Decisions: D-037, D-041. Lessons: L-080. Full notes in git history + session log.*

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

*Closed in session 41. Decisions: D-007, D-017, D-035. Lessons: L-065, L-066, L-067. Full notes in git history + session log.*

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

*Closed in session 42. Decisions: D-007, D-009, D-017, D-036. Lessons: L-068, L-069, L-070. Full notes in git history + session log.*

### T-024 — `jdx signature` over bytecode · `DONE` (session 43)

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

*Closed in session 43. Decisions: D-007, D-009, D-017. Lessons: L-071. Full notes in git history + session log.*

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

*Closed in session 40. Decisions: D-016. Lessons: L-063, L-064. Full notes in git history + session log.*

# M4 — Graph
### T-029 — Reference-edge extraction (bytecode → edges → store) · `DONE` (session 50)

**Depends:** T-013/T-014 (store + indexer), T-008 (ASM reader) · **Files:** `core/.../model/Reference.kt`, `index/.../refs/ReferenceExtractor.kt`, `index/.../store/IndexStore.kt`, `index/.../store/sqlite/SqliteIndexStore.kt`, `index/.../index/ArtifactIndexer.kt`

*(First M4 slice: extract reference edges from bytecode and persist them. No CLI surface — `usages`/`hierarchy`/`callers`/`calls`/`samples` (T-030…T-034) query this data. The v1 `ref` table already exists in the schema (T-013) with no writers; this task fills it without a migration.)*

*Closed in session 50. Decisions: D-042. Lessons: L-081, L-082. Full notes in git history + session log.*

### T-030 — `jdx usages` (live bytecode usages) · `DONE` (session 51)

**Depends:** T-029 (ReferenceEdge/ReferenceExtractor model), T-011 (read-command patterns), T-015/T-016 (roots) · **Files:** `core/.../render/Usages.kt`, `index/.../service/JdxService.kt` (`usages`), `cli/.../commands/UsagesCommand.kt`

*(First M4 CLI slice: find-usages over live bytecode roots via the T-029
`ReferenceExtractor` (no persistent-index read yet — the `findReferencesTo`
seam stays the store-side contract; the indexed acceleration lands with the
daemon/`jdx index` work. Mirrors D-031's "live roots first" precedent).
Project source-dir usages stay in T-031; hierarchy/implementors in T-032;
callers/calls in T-033; samples in T-034.)*

*Closed in session 51. Decisions: D-007, D-017, D-043. Lessons: L-083. Full notes in git history + session log.*

### T-031 project source-dir usages · `DONE` (session 52)

**Depends:** T-030 (usages over live bytecode), T-015 (workspaces), T-016 (discovery) · **Files:** `index/.../workspace/WorkspaceDefinition.kt`, `WorkspaceToml.kt`, `WorkspaceResolver.kt`, `index/.../service/JdxService.kt` (`usages`), `index/.../usages/SourceUsages.kt`, `cli/.../commands/UsagesCommand.kt`, `WsCommands.kt`, `ReadCommandSupport.kt`

*(Second M4 CLI slice: D-010 promises usages over "all workspace jars **plus** the project's own source dirs" — T-030 scans jars only. This task adds `--src <dir>` source-dir roots (explicit flag + stored workspace `srcs`) scanned textually for whole-word mentions with file:line call sites. Hierarchy/implementors stay in T-032; callers/calls in T-033; samples in T-034.)*

*Closed in session 52. Decisions: D-007. Full notes in git history + session log.*

### T-032 — `jdx hierarchy` / `implementors` (live-roots type hierarchy) · `DONE` (session 53)

**Depends:** T-011 (read-command patterns), T-015/T-016 (roots) · **Files:** `core/.../render/Hierarchy.kt`, `index/.../service/JdxService.kt` (`hierarchy`), `cli/.../commands/HierarchyCommand.kt`

*(Second M4 CLI slice: supertypes upward + subtypes/implementors downward across
live bytecode roots via ASM `ClassInfo` supertype edges (no persistent-index
read yet — mirroring D-043's live-roots-first precedent). Callers/calls stay
in T-033; samples in T-034; `usages --kind impl|override` repoints here.)*

*Closed in session 53. Decisions: D-007, D-017, D-045. Full notes in git history + session log.*

### T-033 `jdx callers` / `calls --depth` · `DONE` (session 54)

**Depends:** T-029 (ReferenceEdge/ReferenceExtractor model), T-030 (usages live-scan patterns),
T-011 (read-command resolution incl. `g:a:v` scope + `DUPLICATE_FQN`), T-015/T-016 (roots) ·
**Files:** `core/.../render/Calls.kt`, `index/.../service/JdxService.kt` (`callers`/`calls`,
`CallOptions`, `executeCallGraph`), `cli/.../commands/CallCommands.kt`, `cli/.../JdxCli.kt`

*(Third M4 CLI slice: the call-hierarchy pair over live bytecode roots via the T-029
`ReferenceExtractor` (no persistent-index read yet — mirroring D-043's live-roots-first
precedent). `usages` answers "what touches X" flat; these answer "what calls M" / "what
does M call" as a depth-bounded tree. Samples stay in T-034.)*

- Member refs only: methods + `<init>`; type refs and field refs exit 3 (fields belong
  to `usages`); `callers` of `<clinit>` exits 3 (never invoked), `calls` from `<clinit>`
  is allowed (static init calls out).
- Overload-blind unless the ref carries a parameter list — the usages rule (D-042 §5):
  a bare `Lib#greet` matches every overload's edges; deeper tree levels always expand
  overload-blind. Tree nodes are descriptor-specific `(class, member, descriptor)` so
  sibling overloads render as distinct rows with canonical `Owner#m(params)` refs.
- `callers`: incoming `METHOD_CALL` edges; `calls`: outgoing `METHOD_CALL` edges from the
  resolved overload(s). Exact name+descriptor matching only — no virtual-dispatch /
  override-aware resolution in v1 (documented limitation).
- `--depth N` (default 1, `< 1` exits 3): transitive BFS walk, cycle-safe with `…(cycle)`
  markers on the re-entrant row; diamonds repeat (tree semantics, IntelliJ-like).
- `--in`/`--exclude` artifact-label filters (D-031 matching, mirror usages/hierarchy);
  `calls --external-only` prunes callees in the query target's own artifact (Appendix B
  dependency view).
- `--limit N` (default 50) caps total tree nodes shown (pre-order), flat footer
  `shown of total … (--limit M to see more)`.
- Zero nodes exit 1 with a `no callers` / `no calls` detail (mirror usages); unknown
  target exits 1 with did-you-mean; ambiguous short names exit 2; unreadable classes
  warn once each (`CORRUPT_CLASS`) and are skipped (D-017).
- Tests: core `CallsTest` (test-first) + property (determinism, sorted law, cycle law);
  index ASM case jar (chain + diamond + cycle + self-recursion + overloads) with
  `CallersServiceTest`/`CallsServiceTest` incl. the TESTING.md §6 `callers⟺calls`
  depth-1 metamorphic over every case method; goldens; CLI tier-1 + tier-2;
  `CorpusSoakTest` outcome branches.

### T-034 — `jdx samples` with exemplariness ranking · `DONE` (session 55)

**Depends:** T-029 (ReferenceEdge/ReferenceExtractor model), T-030 (usages live-scan patterns),
T-022 (JavaBodies source slicing for snippets), T-011 (read-command resolution incl. `g:a:v`
scope + `DUPLICATE_FQN`), T-015/T-016 (roots) ·
**Files:** `core/.../render/Samples.kt`, `index/.../service/JdxService.kt` (`samples`,
`SampleOptions`, `executeSamples`, `ServiceOutcome.SampleList`), `cli/.../commands/SamplesCommand.kt`,
`cli/.../JdxCli.kt`

*(Last M4 CLI slice: source-rendered usage examples over live bytecode roots via the T-029
`ReferenceExtractor` (no persistent-index read yet — mirroring D-043's live-roots-first
precedent). `usages` answers "what touches X" flat; `callers` answers "who calls M" as a tree;
`samples` answers "how is X really used" with evidence: ranked call sites with enclosing-method
source snippets when paired sources exist.)*

- Type refs and method refs only: incoming `METHOD_CALL` edges (type query matches any member
  of the type; member query is overload-blind unless the ref carries a parameter list — the
  usages rule, D-042 §5). Field refs exit 3 naming `usages`; `<clinit>` samples exit 3 (never
  invoked); package/module refs exit 3.
- Exemplariness ranking (deterministic, PROPOSAL.md §7.3): non-test before test (class/member
  containing `test`, case-insensitive), non-generated before generated (`$` nesting,
  `lambda$`/`access$`/metafactory members), fuller overloads first (more target params), then
  `fromRef` alphabetical. `--prefer-sources` ranks callers from sources-paired
  artifacts first.
- Snippets: enclosing caller method sliced via the T-021 `findJavaBodies` seam over the
  caller's own paired sources (best effort — any miss yields a snippet-less row, never a
  failure); capped to 15 lines with a `… (truncated)` marker. Rows without sources still print
  the canonical caller ref + artifact, so samples degrades to ranked `callers` depth-1.
- `--limit N` (default 3, per proposal) caps ranked rows shown, flat footer
  `shown of total … (--limit M to see more)`; `--in`/`--exclude` artifact-label filters
  (D-031 matching, mirror usages/callers). Zero samples exit 1 with a `no samples` detail;
  unknown target exits 1 with did-you-mean; ambiguous short names exit 2; unreadable classes
  warn once each (`CORRUPT_CLASS`) and are skipped (D-017).
- Repoints the parked T-034 messages: `usages --context` and `--kind new|throw|annotation`
  rejections now name `jdx samples` / the live rule instead of the task number.
- Tests: core `SamplesTest` (test-first) + property (determinism, ranking law,
  truncation-prefix law, text⊆JSON); index ASM case jar (plain + test-named +
  generated + overloads + ctor, sibling `-sources.jar` for the snippet/degrade
  pair) with `SamplesServiceTest`; goldens; CLI tier-1 + tier-2;
  `CorpusSoakTest` outcome branches.

*Closed in session 55. Decisions: D-047. Lessons: L-086. Follow-up split to
T-075 (`usages` graph-enrichment remainder). Full notes in git history +
session log.*

### T-075 — `usages` graph enrichment: `--kind new|throw|annotation` + `--context` · `TODO`

**Depends:** T-030 (usages live-scan), T-034 (`samples` source rendering) ·
**Files:** `index/.../service/JdxService.kt` (`usages`), `cli/.../commands/UsagesCommand.kt`
*(Split out of T-034 in session 55: the parked `usages` flags still exit 3 —
`new|throw|annotation` naming this task, `--context` naming `jdx samples`.)*

Teach `usages` the three deferred edge kinds over the T-029 vocabulary
(constructor `new` sites, `throw` sites, annotation uses) and decide whether
`--context` renders inline snippets (reusing the T-034 slice) or stays a
`samples` redirect. Unblocked but low priority: M5 (T-035) stays the next
task per the lowest-numbered-TODO rule.

# M5 — Kotlin
### T-035 — `@Metadata` decoding (live-roots + `show` + stored `is_kotlin`) · `DONE` (session 56)

**Depends:** T-008 (ASM reader), T-013 (store schema already has `is_kotlin` + `ktmeta`) ·
**Files:** `core/.../model/ClassInfo.kt` (`isKotlin`), `core/.../render/ClassCard.kt`,
`index/.../kotlin/KotlinMetadata.kt` (new decoder), `index/.../asm/AsmClassReader.kt`,
`index/.../store/sqlite/SqliteIndexStore.kt`, `index/.../store/IndexStore.kt` (KDoc)

*(First M5 slice: decode Kotlin `@Metadata` from class bytes with
`kotlin-metadata-jvm` (already a dependency, unused) and surface it. No member
mapping (T-036), no `--view jvm` (T-037), no PSI (T-038), no Kotlin bodies
(T-039), no `ktmeta` blob writes — `is_kotlin` persistence only.)*

- New pure decoder `index/.../kotlin/KotlinMetadata.kt`: ASM
  `visible+invisibleAnnotations` → find `Lkotlin/Metadata;` → build
  `KotlinClassHeader` → `KotlinClassMetadata.readLenient` → `KotlinMetadata`
  (`metadataKind`: CLASS/FILE_FACADE/MULTI_FILE_*/SYNTHETIC/UNKNOWN, plus
  `classKind` when CLASS). Never throws: absent → `null`, corrupt → `null`.
- `AsmClassReader.mapClass`: `isKotlin = metadata != null` (file facades count);
  kind refinement only `OBJECT`/`COMPANION_OBJECT` → `TypeKind.OBJECT`/`COMPANION`
  (the kinds `ClassInfo` already reserves); every other Kotlin kind keeps its JVM
  kind so Java output is byte-identical.
- `core`: `ClassInfo.isKotlin: Boolean = false` (default keeps all existing
  constructors compiling); `ClassCard` text gains a `kotlin` line (mirroring
  `deprecated`) and JSON gains `"kotlin":true` only when true (D-007 parity,
  zero churn on Java goldens).
- `SqliteIndexStore`: bind `is_kotlin` from `ClassInfo` (today hardcoded `0`);
  `readClass` returns it. No migration: columns already exist at schema v1.
- Tests: core `ClassCard` kotlin line + JSON (test-first); index tier-1
  `KotlinMetadataTest` (fixture bytes: class/object/companion/file-facade/Java-negative)
  + never-throws/determinism properties (the generating family); index tier-2
  reader test over the fixture jar; `show` goldens for the Kotlin fixtures
  updated after reading the diff.
- Live proof: `show` on `KotlinMembers` (kotlin class), `KotlinRegistry`
  (object), `$Companion` (companion), a Java class (no kotlin marker).

*Closed in session 56. Decisions: D-048. Lessons: L-087. Full notes in git history +
session log.*

### T-036 Kotlin member mapping (properties, default args, suspend) · `WIP` (session 57)

**Depends:** T-035 (decoder + `isKotlin` flag) ·
**Files:** `index/.../kotlin/KotlinMetadata.kt`, `index/.../kotlin/KotlinMembers.kt`,
`core/.../render/*`, `index/.../service/JdxService.kt`

*(M5 second slice: map collected JVM members back onto Kotlin declarations
(PROPOSAL.md §9.3 step 7, §12.1). T-035 proved the language; this task fixes
the misleading projection: properties instead of `getX`/`setX` pairs,
`suspend` without the hidden `Continuation`, default-arg `$default` stubs
folded in, `@JvmName` mappings honoured. Split into one-sitting slices —
carrier first, then renderings, then `--view jvm`:)*

- **T-076 (carrier, this session):** `KotlinMetadata` carries the decoded
  `KmClass` for `CLASS` kind (`null` for facades/non-Kotlin/corrupt) —
  no rendering change, no `ktmeta` blob writes.
- **T-077 (next):** suspend + `@JvmName` + mangled-`internal` signature repair
  over the carrier.
- **T-078 (next):** property folding (`getX`/`setX` → `val`/`var`) + default-arg
  annotation over the carrier.
- **T-037 (parked):** `--view jvm` forcing the JVM projection.

### T-076 — Kotlin `KmClass` carrier (first T-036 slice) · `DONE` (session 57)

**Depends:** T-035 (decoder) · **Files:** `index/.../kotlin/KotlinMetadata.kt`

Carry the decoded `KmClass` on `KotlinMetadata` for `CLASS` metadata
(`kmClass: KmClass?`, `null` for facades/synthetic/unknown/non-Kotlin/corrupt)
so T-077/T-078 have declarations to map against. No rendering, CLI, store or
golden change: `isKotlin` and kinds are byte-identical.

- `KotlinMetadataReader.read` returns `metadata.kmClass` on the `Class`
  branch; every other branch carries `null`. Never throws (existing
  never-throws/determinism properties cover the new field via `data class`
  equality).
- Tests: tier-2 `KotlinClassesTest` pins fixture truth — `KotlinMembers`
  functions name `fetch`/`withDefault`/`originalName` (JVM: `renamedForJvm`),
  properties name `Companion`/`VERSION`-adjacent state; `KotlinData`
  properties name `name`/`count`/`greeting`/`nickname`; file facades and
  Java negatives carry `null`.
- Deferred explicitly: any signature/member rendering (T-077/T-078),
  `ktmeta` blob persistence, `--view jvm` (T-037).

*Closed in session 57. Decisions: D-049. Lessons: L-088. Full notes in git history +
session log.*

### T-077 — suspend + `@JvmName` + mangled-`internal` signature repair (second T-036 slice) · `DONE` (session 58)

**Depends:** T-076 (carrier) · **Files:** `core/.../model/ClassInfo.kt`
(`kotlinMethodViews`), `core/.../model/KotlinView.kt` (new),
`core/.../render/Signatures.kt`, `core/.../render/Listing.kt`,
`core/.../resolve/MemberResolver.kt`, `index/.../kotlin/KotlinMembers.kt` (new),
`index/.../asm/AsmClassReader.kt`, `index/.../service/JdxService.kt`

Repair the three JVM-projection lies that need no property model, over the
T-076 carrier (PROPOSAL.md §12.1, §9.3 step 7). One `KmFunction` ↔ one JVM
method, keyed by the metadata `jvmSignature` (name + descriptor):

- `@JvmName`: display the Kotlin name (`renamedForJvm` → `originalName`).
- Mangled `internal`: strip the `$module` suffix (`internalHelper$testfixtures`
  → `internalHelper`).
- `suspend`: drop the trailing `Continuation` parameter, add the `suspend`
  keyword, render the `KmType` return (suspend erases it to `Object`).
- Matching accepts both spellings everywhere (JVM exact first, Kotlin alias
  second, incl. stripped-Continuation arity); canonical refs use Kotlin names;
  did-you-mean suggests Kotlin names.
- Scope: `CLASS`-kind methods only, live roots only (D-043 precedent, no
  `ktmeta` blob, no store migration — views ride `ClassInfo` with defaults).
  Properties/`$default` stay on T-078; file facades stay JVM (no carrier);
  `--view jvm` stays parked (T-037); full nullability stays deferred
  (suspend returns excepted).

*Closed in session 58. Decisions: D-050. Lessons: L-089. Full notes in git history +
session log.*

### T-078 — property folding + default-arg annotation (third T-036 slice) · `DONE` (session 59)

**Depends:** T-076 (carrier), T-077 (method-view pattern) · **Files:** `core/.../model/ClassInfo.kt`
(`kotlinProperties`, `kotlinHiddenAccessors`), `core/.../model/KotlinView.kt`
(`KotlinPropertyView`, `defaultArgIndices` on `KotlinMethodView`), `core/.../render/Signatures.kt`,
`core/.../render/Listing.kt` (`MemberKind.PROPERTY`, `MemberCounts.properties`),
`core/.../resolve/MemberResolver.kt`, `index/.../kotlin/KotlinMembers.kt`,
`index/.../asm/AsmClassReader.kt`, `index/.../service/JdxService.kt`

Fold the two remaining JVM-projection lies over the T-076 carrier (PROPOSAL.md §12.1, §9.3 step 7):

- Properties: `KmProperty` getter/setter signatures → hide the JVM accessors, show one
  `property` row (`val`/`var` + metadata type, Java-style modifiers). Backing private fields
  with a matching `fieldSignature` hide alongside. Matching accepts the property name
  everywhere (JVM exact first, property alias second); canonical refs use property names
  (`Owner#name`); did-you-mean suggests them.
- Default args: `KmValueParameter.declaresDefaultValue` → annotate the real method's
  default params with `= ...` in `members`/`outline`/`signature` (`withDefault(int, String = ...)`).
  `$default` stubs are already synthetic-hidden; `@JvmOverloads` overloads stay (real Java API).
- Scope: `CLASS`-kind only, live roots only (D-043 precedent, no `ktmeta` blob, no migration —
  views ride `ClassInfo` with defaults). File facades stay JVM; `--view jvm` stays parked (T-037);
  `body`/`doc` stay JVM-spelled (property-aware bodies await T-039 PSI).

*Closed in session 59. Decisions: D-051. Lessons: L-090. Full notes in git history +
session log.*

### T-037 — `--view jvm` forcing the JVM projection (last T-036 slice) · `DONE` (session 60)

**Depends:** T-078 (Kotlin view to escape from) · **Files:** `core/.../resolve/MemberResolver.kt`
(`MemberResolutionOptions.jvmView`), `index/.../service/JdxService.kt` (`MemberView`,
`MemberFilters.view`, `SignatureOptions.view`, JVM-only matching/refs/suggestions),
`cli/.../commands/ReadCommands.kt`, `SignatureCommand.kt` (`--view kotlin|jvm`),
`index/.../differential/ServiceDifferential.kt` (re-include Kotlin via JVM view)

Force the raw JVM projection for agents that genuinely need the JVM truth
(PROPOSAL.md §12.1: calling Kotlin from Java, reading a stack trace):

- `members`/`outline`/`signature` gain `--view kotlin|jvm` (default `kotlin`,
  invalid exits 3 via the choice). JVM view: JVM method/field names (incl.
  mangled `internal`, `@JvmName` JVM spellings), full JVM params (incl. the
  hidden `Continuation`), unfolded getters/setters + backing fields, `$default`
  stubs per synthetic rules, no `= ...` defaults, no `property` rows, JVM
  canonical refs, JVM-only did-you-mean. Matching is JVM-exact only (true
  `javap` parity); Kotlin spellings exit 1 with JVM suggestions.
- `show`/`body`/`source`/`doc`/`usages`/`hierarchy`/`callers`/`calls`/`samples`:
  unchanged (body/doc/usages/callers already JVM-spelled per D-051).
- Scope: `CLASS`-kind live roots only (D-043 precedent); file facades already
  JVM; no store change. Differential re-includes Kotlin classes via JVM view.
- Tests: core `MemberResolverTest` jvmView cases (test-first) + a generating
  family law; index tier-2 JVM-view service tests; CLI tier-1 flag + tier-2
  end-to-end; JVM goldens for the Kotlin fixtures; differential Kotlin
  re-inclusion.

*Closed in session 60. Decisions: D-052. Lessons: L-091. T-036 stays WIP
(T-038/T-039 next). Full notes in git history + session log.*

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

*Closed in session 30. Full notes in git history + session log.*

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

*Closed in session 32. Decisions: D-017. Lessons: L-029. Full notes in git history + session log.*

### T-066 — Make `ReadCommandsTest` hermetic to the machine's workspaces · `DONE` (session 35)

**Depends:** T-015 · **Files:** `cli/src/test/kotlin/.../commands/ReadCommandsTest.kt`
*(Added in session 21: found while verifying T-055 — pre-existing T-015 gap, not caused
by T-055.)*

`ReadCommandsTest` constructs `ShowCommand`/`MembersCommand`/`OutlineCommand` with the
default `FileWorkspaceStore.system()`, so an `active-workspace` file on the contributor's
machine (e.g. `fx`, left by an earlier e2e) silently re-roots every "no workspace"
assertion — 2 red tests whose diff is nowhere near the failure (L-038). Proven by
shelving `~/.config/jdx`: green without it, red with it.

*Closed in session 35. Full notes in git history + session log.*

### T-067 — Repair the configuration-cache storing failure · `DONE` (session 25)

**Depends:** — · **Files:** `gradle.properties`, root `build.gradle.kts`
*(Added in session 24: found while verifying T-017 — pre-existing, not caused
by T-017. Proven by stashing all T-017 work and rerunning on the clean tree:
`org.gradle.configuration-cache=true` (commit `1ebfe56`) fails every build at
"2 problems were found storing the configuration cache", L-045.)*

Any `./gradlew` invocation currently ends `BUILD FAILED` unless passed
`--no-configuration-cache` — the tests themselves run, then cache-storing
fails the build. Either fix the storing problems or revert the flag.

*Closed in session 25. Full notes in git history + session log.*

### T-068 — Dedupe identical resolved roots before querying · `DONE` (session 36)

**Depends:** — · **Files:** `index/.../service/JdxService.kt`
*(Added in session 24: found during T-017's real-binary e2e — not a T-017 bug,
correct per the §13 merge + D-031 per-provider rules, but noisy.)*

Explicit `--jars testfixtures/build/libs/testfixtures-0.1.0-SNAPSHOT.jar` merged
in front of the ambient `fx` workspace (which holds the same jar via a relative
glob) opens one file as two roots: `search` lists every hit twice and `tree`
prints the artifact twice (second suffixed `(2)`). Same *file*, not same *name*.

*Closed in session 36. Lessons: L-061. Full notes in git history + session log.*

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

*Closed in session 38. Full notes in git history + session log.*

### T-071 — `sources`: sources-jar/dir access (`SourceRoot`) · `DONE` (session 39)

**Depends:** — · **Files:** `sources/src/main/kotlin/dev/jdx/sources/SourceRoot.kt`
*(First expanded slice of the M3 one-liner T-020. Parsing/AST work stays in T-021;
decompilation stays in T-026/T-027; no CLI surface yet — this task only opens
sources and maps `binaryName → source file`, which every later M3 task reads through.)*

Open a `-sources.jar`, a source directory, or `src.zip` uniformly and answer
"which source file holds this class" — the seam `body`/`source`/`doc` will query
before any JavaParser/Kotlin-PSI parsing happens.

*Closed in session 39. Full notes in git history + session log.*

### T-074 — `srcmap`: same-file top-level siblings (T-020 remainder) · `DONE` (session 61)

**Depends:** T-071 (`SourceRoot`), T-021 (`listJavaMembers` seam) · **Files:** `sources/.../SourceSiblings.kt` (new), `sources/.../JavaBodies.kt` (`loadJavaUnit`), `index/.../service/JdxService.kt` (`sourceOutcome`)
*(Split out of T-028 in session 47: same-file top-level siblings (`Matrix`/`Tag` in `Annos.java`)
resolve to no source file — `binaryName → source file` maps per top-level name only. Full detail
in git history (pre-compression T-028 implementation notes) + session 47 log.)*

Map every top-level type declared in the same `.java` file to that file, so `body`/`source`/`doc`
find siblings without a full scan. Unblocked but low priority: M5 (T-035) stays the next
task per the lowest-numbered-TODO rule (T-075 is the `usages` remainder).

*Closed in session 61. Lessons: L-092. Follow-up split to T-079 (annotation-element
member matching). Full notes in git history + session log.*

### T-079 — Match annotation-element members in the JavaParser seam · `TODO`

**Depends:** T-021 (`matchByName`/`narrowBySignature` seam) · **Files:** `sources/.../JavaBodies.kt` (`matchByName`), `sources/.../JavaDocs.kt` (same matchers)
*(Split out of T-074 in session 61: the sibling scan resolves `Tag` to `Annos.java`,
but `body 'dev.jdx.fixtures.Tag#value()'` still reports no source counterpart —
`matchByName` matches methods/fields/enum entries only, while JavaParser parses
annotation elements (`String value();`) as `AnnotationMemberDeclaration`, not
`MethodDeclaration`. Pre-existing T-021 gap, not a T-074 regression: single-file
annotations miss the same way. `listJavaMembers` already lists them via
`declaredMembersOf`, so the T-028 comparison sees them — only `body`/`doc`
member matching drops them.)*

Teach `matchByName` (and thus `narrowBySignature`, `findJavaBodies`,
`findMemberDocs`) the `AnnotationMemberDeclaration` branch, mirroring the
`declaredMembersOf` handling: name match on the element name, zero-arity.
Unblocked but low priority alongside T-074/T-075: M5 (T-038/T-039) stays next.

## Open questions

Add here when blocked. Format: `Q-nnn`, the question, why it blocks, and what you did instead.

*(none open)*

**Resolved**
- ~~**Q-001 — GitHub repository name?**~~ Answered 2026-09-13: **`jdx`**. Repo created at
  <https://github.com/MohammadMD1383/jdx>. See D-018.
