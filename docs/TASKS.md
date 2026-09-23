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
   `docs/PROGRESS.md`'s CURRENT STATE, update `open-items.md` (always on — move finished
   items out, record new ones), commit.
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
| **M5** | Kotlin: `@Metadata` + PSI source parsing | DONE (T-035…T-039) |
| **M6** | Serving: daemon, MCP, HTTP, `batch` | DONE (T-040…T-046 + T-082) |
| **M7** | Polish: token budgets, AppCDS, mutation gates, docs, install | TODO (T-060 done early) |

**DONE: T-001…T-035** (M0–M2 in full; M3 via the T-020 umbrella's slices —
T-071 + T-021…T-028 + T-072/T-073; M4 via T-029…T-034; M5 opened with T-035) **plus T-053…T-073
plus T-076** (first T-036 slice) **plus T-077** (second T-036 slice) **plus T-078** (third T-036 slice)
**plus T-037** (`--view jvm`, last T-036 slice) **plus T-074** (T-020 `srcmap`
remainder) **plus T-079** (annotation-element matching) **plus T-075** (`usages`
graph enrichment) **plus T-038** (PSI loader seam) **plus T-039** (Kotlin
bodies/KDoc, last T-036 slice) **plus T-036** (member-mapping umbrella)
**plus T-040** (M6 RPC wire contract) **plus T-041** (daemon + unix socket)
**plus T-082** (daemon `JdxService` dispatch) **plus T-042** (transparent CLI
daemon client + `--no-daemon`) **plus T-043** (MCP stdio server) **plus
T-044** (HTTP/JSON server) **plus T-045** (`jdx batch`) **plus T-046**
(adapter parity test, closes M6).
DONE entries below are
compressed to a summary + pointers; full notes live in git history and the
session log. **Next: M7** (coarse; T-060 already DONE).

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

### T-075 — `usages` graph enrichment: `--kind new|throw|annotation` + `--context` · `DONE` (session 63)

**Depends:** T-030 (usages live-scan), T-034 (`samples` source rendering) ·
**Files:** `index/.../service/JdxService.kt` (`usages`), `cli/.../commands/UsagesCommand.kt`
*(Split out of T-034 in session 55: the parked `usages` flags still exit 3 —
`new|throw|annotation` naming this task, `--context` naming `jdx samples`.)*

Teach `usages` the three deferred edge kinds over the T-029 vocabulary
(constructor `new` sites, `throw` sites, annotation uses) and decide whether
`--context` renders inline snippets (reusing the T-034 slice) or stays a
`samples` redirect. Unblocked but low priority: M5 (T-038/T-039) stays the next
task per the lowest-numbered-TODO rule.

*Closed in session 63. Decisions: D-053. Full notes in git history +
session log.*

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

### T-036 Kotlin member mapping (properties, default args, suspend) · `DONE` (session 65)

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

*Closed in session 65 via the last slice T-039 (T-037 DONE session 60,
T-038 DONE session 64). Decisions: D-048…D-052, D-054, D-055. Full notes in
git history + session log.*

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

### T-038 — Kotlin PSI loader seam (first T-036 remainder slice) · `DONE` (session 64)

**Depends:** T-035 (decoder) · **Files:** `sources/.../KotlinToolchain.kt` (new),
`sources/.../KotlinParser.kt` (new), `cli/.../service/DoctorService.kt` (`kotlin` row)

*(M5 fourth slice: the D-008 loading infrastructure. No parsing yet — `body`/
`source`/`doc` keep their T-039 degradations. Proves the side-load shape before
T-039 builds PSI queries on it.)*

- New `KotlinToolchain` in `sources`: versioned sidecar path
  (`~/.cache/jdx/kotlin/kotlin-compiler-embeddable-<version>.jar`, version pinned
  next to `libs.versions.toml#kotlinCompiler`), `probe(userHome)` returning
  `Installed`/`Missing` as a value — never throws, deterministic.
- New `KotlinSourceParser` seam + `openKotlinParser(userHome)`: present sidecar →
  isolated `URLClassLoader` (platform parent, never the app loader) held open,
  reflective presence-check of one compiler class; absent/corrupt → unavailable
  value with an install hint, never a throw. **Nothing outside `sources` imports
  compiler classes** (D-008 §1); no new `implementation` dependency (the compiler
  stays side-loaded, never on the compile classpath, never in the fat jar).
- `doctor kotlin` reports the real status: OK with version+path when installed,
  WARN with the install hint when absent (replacing the hardcoded WARN).
- Tests: tier-2 `KotlinToolchainTest` (sidecar-path/version pins, disk probe —
  missing/present/directory-at-path/empty-file, open/close laws incl. a
  JDK-compiled presence-stub jar for the Available branch and a classpath-
  absence pin for the D-008 premise) + doctor present/absent rows; the
  generating family is a hostile-path never-throws property (200 cases).

*Closed in session 64. Decisions: D-054. Lessons: L-093. T-036 stays WIP
(T-039 next). Full notes in git history + session log.*

### T-039 — Kotlin bodies/KDoc over the T-038 seam (last T-036 remainder slice) · `DONE` (session 65)

**Depends:** T-038 (loader seam) · **Files:** `sources/.../KotlinBodies.kt` (new),
`sources/.../JavaBodies.kt`, `sources/.../JavaDocs.kt` (`ParserUnavailable` cases),
`sources/.../KotlinParser.kt` (reflective PSI env), `sources/.../KotlinToolchain.kt`
(sidecar dir + install hint), `index/.../service/JdxService.kt` (T-039 degradations)

*(M5 last slice: serve `.kt` member bodies + KDoc through the T-038 loader with
real PSI ranges (PROPOSAL.md §12.2). Stays bytecode-authoritative (D-009):
overload ambiguity decided from bytecode first; property-aware (getter/setter →
property name); never throws; degrades to decompile/javap where PSI has no node.)*

*Closed in session 65. Decisions: D-055. Lessons: L-094, L-095 (plus L-039
re-hit: nested block comments). Follow-ups filed as T-080 (sidecar fetch)
and T-081 (`@Metadata`-aware mismatch pairing). Full notes in git history +
session log.*

### T-080 — Fetch the Kotlin sidecar set on first use · `WIP`

**Depends:** T-038 (sidecar path), T-039 (runtime-jar set) · **Files:**
`sources/.../KotlinToolchain.kt`, `cli/.../commands/*` (opt-in flag)

*(Split out of T-039 in session 65: the D-008 mitigation "shipped in a
separate jar fetched/verified on first use" is half-wired — T-039 reads
whatever set is present and degrades otherwise, but nothing downloads it.
The set is the compiler plus its runtime jars (stdlib, script-runtime,
reflect, daemon-embeddable, coroutines) at pinned versions with checksum
verification into `~/.cache/jdx/kotlin/`. Network stays opt-in per
invocation (T-019 precedent): decide the flag surface (`--fetch` parity on
the read commands vs a `jdx kotlin install` command vs daemon warm-up)
when starting. M6 (T-040+) stays next per the lowest-numbered-TODO rule.)*

### T-081 — `@Metadata`-aware `SOURCES_VERSION_MISMATCH` pairing for Kotlin · `TODO`

**Depends:** T-039 (Kotlin listings) · **Files:**
`sources/.../SourcesMismatch.kt`, `index/.../service/JdxService.kt`
(`mismatchWarning`)

*(Split out of T-039 in session 65: `mismatchWarning` skips Kotlin pairs
(`null`) because compiler-generated members (data `componentN`/`copy`,
value-class `-impl`s, `@JvmOverloads` overloads, file-facade statics) would
false-positive a JVM-name pairing. Pair over Kotlin declaration names
instead (display names + property names from the `ClassInfo` views, excusing
defaulted overloads and data/value synthetics) so stale Kotlin sources still
warn. Unblocked but low priority: M6 (T-040+) stays next.)*

# M6 — Serving

Goal: every `JdxService` answer reachable without a cold JVM — daemon, MCP,
HTTP and `batch` as thin adapters over one wire contract, with byte-identical
payloads. Expanded into detail blocks session 66 (board rule: coarse until
started).

### T-040 — `JdxService` RPC protocol (v1 wire contract) · `DONE` (session 66)

**Depends:** T-010 (JSON envelope) · **Files:** `core/.../rpc/RpcProtocol.kt`

*(First M6 slice: the contract only — no socket, no server, no client. T-041
owns the daemon transport, T-042 the CLI client, T-043 MCP, T-044 HTTP, T-045
`batch`, T-046 the parity proof. Keeps the one-sitting rule: a pure-`core`,
dependency-free codec plus docs.)*

- New `core/.../rpc/RpcProtocol.kt` (explicit API, no IO, no new dependencies —
  `core` stays dependency-free so JSON is hand-rolled via `JsonEscape`, D-028):
  `RPC_VERSION = 1` (mirrors `ENVELOPE_VERSION`); `RpcCommand` enum with stable
  wire names for every `JdxService` query (`show`, `members`, `outline`,
  `body`, `source`, `signature`, `doc`, `search`, `resolve`, `ls`, `tree`,
  `usages`, `hierarchy`, `implementors`, `callers`, `calls`, `samples`) plus
  `version`/`doctor`/`health`; `RpcRequest(command, query, params)` with
  deterministic `encode()` (params sorted by key, fixed key order) and
  `decode(text): RpcRequest?` that never throws (malformed/unknown → `null`).
- Framing: newline-delimited JSON (one request per line, `\n`-terminated;
  encoded bytes never contain a raw newline). Response bytes **are** the
  existing `toJson(command)` envelope — no second shape, so T-046 parity is
  structural (PROPOSAL.md §8.2/§14).
- Tests (test-first): tier-1 `RpcProtocolTest` (known vectors, sorted-params
  determinism, unknown-command/garbage/empty → `null`, escaping) + property
  (round-trip, determinism, never-throws on hostile strings — the generating
  family). No tier-3 touch (pure strings, no jars).

*Closed in session 66. `core/.../rpc/RpcProtocol.kt` + `RpcProtocolTest` (30
vectors) + `RpcProtocolPropertyTest` (7 properties). Decisions: D-056. Lessons:
L-096, L-097. Spec summary: PROPOSAL.md §14.5. The acceptance list above is left
in full on purpose — T-041…T-046 implement against it.*

### T-041 — daemon + unix socket + 5-min idle shutdown · `DONE` (session 67)

**Depends:** T-040 (wire contract) · **Files:** `server/.../DaemonPaths.kt`,
`DaemonIdle.kt`, `DaemonServer.kt`, `cli/.../commands/DaemonCommand.kt`,
`cli/.../JdxCli.kt`, `cli/build.gradle.kts` (+ `:server` dep),
`server/build.gradle.kts` (kotest-property for the generating family)

Background JVM holding a hot process, listening on a version-stamped unix-domain
socket (`$XDG_RUNTIME_DIR/jdx/<workspace-hash>.sock`, PROPOSAL.md §14.3).
CLI auto-spawns on first use; `ScheduledExecutorService` idle shutdown after
5 min (`--idle <duration>`, `0` disables); `daemon start|stop|status|restart`
(status: uptime, workspace, memory, indexed artifacts, query count). Tests:
tier-2 lifecycle (start/status/stop, idle-shutdown with a short `--idle`,
version-stamp mismatch refuses) + property (framing round-trip over the
socket). Live-index acceleration stays out (live roots first, D-043
precedent) unless the slice fits one sitting — split again if not.

*Closed in session 67. Transport only: `health`/`version` answered internally,
read queries refused with an exit-6 envelope naming T-082 (filed below), so
the slice fits one sitting. `DaemonPaths` (hashed, version-stamped socket +
pid/log siblings; no `XDG_RUNTIME_DIR` → exit 3), `DaemonServer` (strict 1:1
NDJSON line mapping, per-request idle reset, `health` handshake with
protocol-level version refusal), `DaemonProbe` (never-throws round-trip +
health), `parseIdleDuration` (`0`/`Ns`/`Nm`/`Nh`/bare seconds). CLI
`daemon start|stop|status|restart|run` thin over `:server` (new cli→server
dep, D-004 intact); `start` spawns `java.home`'s java detached and polls
health; `run` is the foreground spawn target. Tests: tier-1 `DaemonPathsTest`
(8) + `DaemonIdleTest` (9, incl. hostile never-throws properties) +
cli `DaemonCommandTest` (7); tier-2 `DaemonServerTest` (13: lifecycle,
query counting, honest refusal, malformed/version-mismatch refusal,
double-start refusal, idle shutdown + idle reset, file sweeping, socket
framing round-trip + hostile-lines properties). Decisions: D-057. Lessons:
L-098. Live proof in the session log. Full `JdxService` dispatch → T-082;
transparent auto-spawn on first query → T-042.*

### T-082 — daemon `JdxService` query dispatch · `DONE` (session 68)

**Depends:** T-040 (wire contract), T-041 (transport) · **Files:**
`server/.../DaemonDispatch.kt` (new), `index/.../service/JdxService.kt` (roots wiring)
*(Split out of T-041 in session 67: the T-041 transport answers `health`/`version`
internally and refuses read queries with an exit-6 envelope naming this task,
so the transport slice fits one sitting. T-042's transparent client needs this
before any warm answer exists.)*

Build the daemon's `DaemonHandler` from a `JdxService` over the daemon's
workspace roots: all 17 read `RpcCommand`s dispatch to the same service
methods the one-shot CLI calls, and responses are `ServiceOutcome.toJson(
command)` bytes verbatim — no second shape, so T-046 parity stays structural
(D-056 §1). Malformed/unknown stays the T-041 envelope (transport owns it).
Indexed acceleration stays out unless it fits one sitting (live roots first,
D-043 precedent) — split again if not. Tests: tier-2 dispatch over the
fixture jar through the live socket (every `RpcCommand` returns the same
bytes as the in-process service call) + the existing framing properties.

*Closed in session 68. Dispatch + `daemonRoots` landed in
`index/.../service/RpcDispatch.kt` (new, same package as `JdxService` — the
6k-line `JdxService.kt` stays untouched) rather than inside `JdxService.kt`;
`server/.../DaemonDispatch.kt` only resolves roots and serialises (D-004).
`doctor` refused exit-6 (`DoctorService` lives in `:cli`); `indexedArtifacts`
stays 0 (live roots, no index). Tests: `RpcDispatchTest` (28) +
`DaemonDispatchTest` (5). Decisions: D-058. Lessons: L-099. Live proof in
the session log (warm `members --json` byte-identical to one-shot). T-042
unblocked.*

### T-042 — transparent CLI daemon client + `--no-daemon` · `DONE` (session 69)

**Depends:** T-041 (daemon transport), T-082 (daemon query dispatch — both DONE,
warm answers exist) · **Files:** `cli/.../DaemonClient.kt`,
`cli/.../commands/*` (flag plumbing)

One-shot CLI forwards the T-040 request to a running daemon when the socket
answers, else runs in-process; `--no-daemon` forces in-process (PROPOSAL.md
§14.1). Failure to reach the daemon degrades to in-process, never an error.
Tests: tier-2 (daemon up → served warm; daemon down → in-process identical
bytes; `--no-daemon` bypasses) + parity property (client vs in-process
byte-identical).

*Closed in session 69. `cli/.../DaemonClient.kt` (new: `shouldAttempt` guards,
`serveWarmIfReady`, `workspaceNameForDaemon`, `parseExitCode`, 17 flag-verbatim
request builders) + `--no-daemon` on the root and every read command (D-027
dual position). Warm serves `--json` only in v1 (no text wire, D-059);
explicit roots, unnamed workspaces and usage errors stay cold. Tests: tier-1
`DaemonClientTest` (18: precedence, guards, exit codes, builders, 3 hostile
never-throws/determinism properties) + tier-2 `DaemonWarmTest` (8: all-17
warm byte parity with exit-code carriage, down/bypass/text/explicit-roots
degradation, command-level + root-flag plumbing, 200-request hostile parity
property). Decisions: D-059. Lessons: L-100. Live proof in the session log
(warm `members`/`search`/`usages --json` byte-identical to `--no-daemon`,
exit-1 carriage, `queries served` climbing). T-043 unblocked.*

### T-043 — MCP stdio server with generated schemas · `DONE` (session 70)

**Depends:** T-040 (wire contract) · **Files:** `mcp/.../*`

JSON-RPC over stdio exposing each T-040 command as a typed MCP tool
(`jdx_show`, `jdx_members`, … per PROPOSAL.md §14.2). Schemas generated from
the same command metadata the CLI uses — never hand-written twice. Holds the
index in memory (per-session daemon). Tests: tier-2 tool-listing pin +
per-tool smoke over the fixture jar + parity (MCP result bytes == CLI
`--json` bytes, feeds T-046).

*Closed in session 70. `mcp/.../McpTools.kt` (the `ALL_MCP_TOOLS` table: one
`jdx_<wire>` tool per `RpcCommand`, schema + wire request generated from the
same rows), `McpSession.kt` (per-session daemon: per-call roots via
`daemonRoots`, `version`/`health` internal in the daemon's envelope shapes,
`doctor` refused exit 6, `isError` = `ok:false`), `McpServer.kt` (SDK
registration + stdio to EOF), `cli/.../McpCommand.kt` (`jdx mcp [-w name]`,
new `:cli`→`:mcp` dep). Tests: tier-1 `McpToolsTest` (10: table pin, schema
determinism, hostile never-throws property) + tier-2 `McpSessionTest` (26:
all-17-tool parity vs hand-written requests, transport commands, workspace
override, SDK tool-listing pin). Decisions: D-060. Lessons: L-101 (stdout is
the protocol), L-102 (exit on EOF, not `awaitCancellation`). Live proof in
the session log (raw JSON-RPC handshake, 20 tools, `members` byte-identical
to CLI `--json` modulo the `println` newline, exit 0 on EOF). T-044
unblocked.*

### T-044 — HTTP/JSON server on `com.sun.net.httpserver` · `DONE` (session 71)

**Depends:** T-040 (wire contract) · **Files:** `server/.../HttpServer.kt`,
`cli/.../commands/ServeCommand.kt`

`jdx serve [-w name] [--port 7070] [--bind 127.0.0.1]` (PROPOSAL.md §14.4, JDK builtin
only): `GET /v1/<command>?…`, `POST /v1/batch`, `GET /v1/health`; localhost
by default; serves the exact `--json` envelope. Separate port/socket from the
daemon. Tests: tier-2 (health, one query GET, batch POST, bind-default pin) +
parity (HTTP bytes == CLI `--json` bytes, feeds T-046).

*Closed in session 71. `server/.../HttpServer.kt` (`JdxHttpServer` over
`com.sun.net.httpserver`: per-request `daemonRoots`, verbatim
`toJson(wire)` bodies, exit→status via `statusForExit`, NDJSON batch,
`?workspace=` per-call override, total handlers) + `cli/.../ServeCommand.kt`
(`jdx serve`, registered in `JdxCli`, injectable runner). Tests: server
tier-2 `HttpServerTest` (12: health/version pins, GET byte parity,
not-found 404 with identical envelope, batch lines + malformed-line
survival + empty-batch usage, missing-workspace exit 4 with override,
doctor refusal, unknown/method envelopes, defaults + status + query-string
pins) + cli tier-1 `ServeCommandTest` (4: defaults, flag plumbing, port
exit 3, bind-failure exit 6). Decisions: D-061. Lessons: none. Live proof
in the session log (health, members parity vs `--json --no-daemon`,
batch, doctor/unknown statuses). T-045 unblocked.*

### T-045 — `jdx batch` · `DONE` (session 72)

**Depends:** T-040 (wire contract) · **Files:** `cli/.../commands/BatchCommand.kt`

Read T-040 requests from stdin (newline-delimited, same framing), one envelope
per line on stdout; `--json` forced (output already is envelopes); per-query
failures ride the envelope's `ok:false`, never abort the stream; exit code is
the max query exit code. Tests: tier-1 framing + tier-2 goldens (mixed
ok/not-found/ambiguous stream).

*Closed in session 72. `cli/.../commands/BatchCommand.kt` (roots resolve once
via `ReadCommandSupport`; `version`/`health` internal in the daemon envelope
shape, `doctor` answered in-process via `DoctorService`, all 17 read queries
via `JdxService.dispatch` verbatim; empty batch exits 3, malformed lines exit
6, blank lines skipped) + `JdxCli` registration. Tests: tier-1
`BatchCommandTest` (8: mixed-stream max-exit, health shape/count, empty batch,
blank skipping, roots-failure mapping, `--json` no-op, 200-case hostile
never-throws/determinism property, doctor envelope) + tier-2
`BatchCommandsServiceTest` (mixed stream golden
`cli/src/test/resources/golden/batch/mixed.txt` + byte parity of show/body
lines vs one-shot `--json` — T-046 in miniature). Lessons: L-103 (fake-construction
throws behind total adapters). Live proof in the session log (warm `members`
parity via `cmp`, exit-6 carriage). T-046 unblocked.*

### T-046 — adapter parity test (CLI/HTTP/MCP byte-identical payloads) · `DONE` (session 73)

**Depends:** T-042, T-043, T-044 · **Files:** `cli/src/test/.../parity/AdapterParityTest.kt`

TESTING.md §9 contract: the same query answered via CLI `--json`, HTTP and
MCP returns byte-identical `result` payloads (envelope `command`/`query`
equal by construction; timestamps/paths already banned by CLAUDE.md §2).
Tier-2, over the fixture jar + a JDK sample. Closes M6.

*Closed in session 73. New `cli/.../parity/AdapterParityTest.kt` (`@Tag("tier2")`,
5 tests): all 17 read `RpcCommand`s (built with the CLI's own
`DaemonClient` flag→param builders) plus a JDK `show java.util.HashMap`
sample and two failure branches (exit 1 unknown, exit 3 usage) assert
`dispatch.toJson` == MCP `callTool` text == HTTP `GET /v1/<wire>` body,
one line each; two real one-shot `--json` spot checks (`show`, `members`
with kind+limit) anchor the `dispatch`-as-CLI reference; a 100-case
kotest-property over hostile (command, query, params) keeps the proof
generating (the standing bar). Lessons: L-104. Full notes in git history +
session log.*

# M7 — Polish
### T-060 — Mutation testing and coverage gates · `DONE` (session 30)

**Depends:** T-055, T-056 · Pitest wired as tier 4. Gates per `docs/TESTING.md` §10:
`core` ≥ 95 % line and **≥ 80 % mutation score** (build-failing); `index`/`sources`/
`decompile` ≥ 85 % line with mutation measured and reported; `cli`/`mcp`/`server`
**deliberately ungated** — they are thin by rule (D-004) and gating them would only
incentivise padding. Surviving mutants in `core` are a gap: kill them with a test, or delete
the unreachable code.

*Closed in session 30. Full notes in git history + session log.*

# M7 — Polish

**Expanded session 74 (board rule: coarse until started).** M6 closed session
73; T-060 already DONE. Order within M7 is by value-per-sitting, not number:
T-049 first (small, unblocks T-051), then T-047, T-052, T-048, T-050, T-051
last (sweeps everything landed). T-080/T-081 stay low priority.

### T-049 — `jdx help --agent` · `DONE` (session 74)

**Depends:** — · **Files:** `cli/.../render/HelpSheet.kt` (new),
`cli/.../commands/HelpCommand.kt` (new), `cli/.../JdxCli.kt` (register)

*(First M7 slice: PROPOSAL.md §3.8 + §14.5 + Appendix B. There is no `help`
subcommand today — `jdx help` is an unknown command. Add one: `jdx help`
prints the human cheat sheet, `jdx help --agent` the compact paste-ready
block for CLAUDE.md/system prompts, both generated from one `HELP_ROWS`
table so they cannot drift. `--json` emits the same rows in the standard
envelope. Exit 0 always on valid flags; bad flags exit 3 via Clikt.)*

- One metadata table (command, one-liner, example); human and `--agent`
  renderers read the same rows. Content: all read commands + `version`/
  `doctor`/`ws`/`cache`/`daemon`/`serve`/`mcp`/`batch`, symbol-ref syntax
  pointer, exit-code pointer, `--json` note. Deterministic, no timestamps,
  no paths (CLAUDE.md §2).
- Tests: tier-1 `HelpCommandTest` (default vs `--agent` content, `--json`
  envelope pin, determinism) + a generating family (hostile-flag
  never-throws / determinism property). No tier-3 touch (pure strings).

*Closed in session 74. `cli/.../render/HelpSheet.kt` (HELP_ROWS + both
renderers + `HelpResult` JSON) + `cli/.../commands/HelpCommand.kt`
(`jdx help [--agent] [--json]`, registered in `JdxCli`) +
`HelpCommandTest` (7 tests, incl. a 200-case determinism property). Live
proof in the session log. Full notes in git history + session log.*

### T-047 — token budgets on `members`/`outline`: `--brief` + `--max-lines` · `DONE` (session 75)

**Depends:** T-010 (renderers + envelope), T-011 (read-command patterns), T-042/D-059
(warm serves `--json` only, so text-only flags need no wire change) · **Files:**
`core/.../render/TokenBudget.kt` (new), `core/.../render/Listing.kt`
(`renderBriefText`), `index/.../service/JdxService.kt`
(`ServiceOutcome.renderBriefText` default), `cli/.../commands/ReadCommands.kt`
(flags), `cli/.../commands/ReadCommandSupport.kt` (`finish` budget params +
validation)

*(First M7 token-budget slice, one sitting: `members --inherited` over JDK
types is the biggest token burner, and `--limit` caps entities but not text
lines. Text-only presentation, so the T-040 wire, daemon/HTTP/MCP adapters
and T-046 parity stay untouched: `--json` bytes are byte-identical with or
without the flags.)*

- Core (test-first): new `TokenBudget.capLines(text, maxLines): String` —
  whole-line cap; exact fit uncut; over keeps the first `maxLines` lines plus
  a `… K more lines (--max-lines M to see more)` footer; `0` shows only the
  footer. `MemberListing.renderBriefText()` — the `members of X` header, bare
  member rows (no group headers, no ` — doc` suffixes, no provenance block),
  object summary + truncation footer + warnings kept.
  `ServiceOutcome.renderBriefText` defaults to `renderText` (only `MemberList`
  overrides).
- CLI: `members` + `outline` gain `--brief` (signatures only, no group
  headers/provenance; warnings + footers kept; text only, `--json`
  unaffected) and `--max-lines N` (cap text lines; `--json` unaffected).
  `--max-lines < 0` exits 3; `--brief --with-doc` exits 3 (mutually exclusive,
  `--static`/`--instance` precedent).
- Tests: core `TokenBudgetTest` + hostile never-throws/determinism property +
  brief example tests + brief⊆full property (the generating family); cli
  tier-1 flag/validation/rendering tests; tier-2 e2e over the fixture jar +
  a JDK sample; brief goldens.
- Docs: PROPOSAL.md Appendix B `members`/`outline` rows; this detail block;
  PROGRESS.md session entry; open-items.md.

*Closed in session 75. `core/.../render/TokenBudget.kt` (`capLines`: whole-line
cap, universal newlines, POSIX trailing-newline rule, `… K more lines
(--max-lines M to see more)` footer) + `MemberListing.renderBriefText()`
(header + bare rows, object summary + limit footer + warnings kept, group
headers + doc suffixes + provenance dropped) + `MemberRow.briefLine()` +
`ServiceOutcome.renderBriefText` default (only `MemberList` overrides);
`members`/`outline` gain `--brief` + `--max-lines` (text-only, `--json`
byte-identical, so no wire/adapter/parity touch). `--brief --with-doc` and
`--max-lines < 0` exit 3. Tests: core `TokenBudgetTest` (6) +
`TokenBudgetPropertyTest` (whole-line-prefix + hostile never-throws/
determinism properties) + `MemberListingBriefTest` (4 examples + brief⊆full
property); cli tier-1 `ReadCommandsTest` +6; tier-2
`ReadCommandsServiceTest` +3 + `TokenBudgetGoldenTest`
(`golden/members-budget/`, 6 files, text only — JSON immunity pinned in
tier-1/2 instead). Decisions: none (text-only scoping follows D-059).
Lessons: L-105 (POSIX trailing-newline rule for line-counting oracles).
Live proof in the session log. Next: T-052.*

### T-052 — warnings-as-errors, lint, final API review · `DONE` (session 76)

**Depends:** — · **Files:** `build.gradle.kts` (shared `subprojects` block),
`index/.../kotlin/KotlinMembers.kt`, `server/.../HttpServer.kt`,
`{index,sources,decompile,cli,mcp,server}/build.gradle.kts`

*(M7 gate slice, one sitting: turn today's clean tree into a build-enforced
guarantee. Pre-claim probes: full `compileKotlin`/`compileTestKotlin`
`--rerun-tasks` sweep shows exactly 2 main-source warnings; `explicitApi()`
trial-compiles clean on `index`; all four lint rules below are green today
across every source set — verified, not assumed. Test-source warnings
(~11) + the Java `strictfp` fixture stay out — filed as T-083.)*

- Fix the 2 main-source warnings: `index/.../KotlinMembers.kt:212`
  redundant `else` (exhaustive `KmVariance` when — unreachable branch, drop
  it) and `server/.../HttpServer.kt:90` redundant `as? InetSocketAddress`
  (`HttpServer.address` is already non-null `InetSocketAddress`). Both
  behavior-preserving; covered by existing tests.
- `allWarningsAsErrors` on every module's main `compileKotlin` only
  (task-scoped in the shared block, so test compilations stay under T-083).
  Full build green proves the gate; a planted-warning negative proof proves
  it bites.
- `explicitApi()` on `index`/`sources`/`decompile`/`mcp`/`server`
  (`core` already has it; all five compile clean). `cli` is reverted:
  99 violations across Clikt wiring with no Kotlin consumers — `public`
  noise on every command class for zero API safety (its contract is the
  CLI surface, pinned by help/parity goldens). `app` (assembly) and
  `testfixtures` (deliberately-nasty fixtures) excluded likewise.
- New dependency-free root `lint` task wired into every module's `check`:
  no trailing whitespace, no tabs, no bare `TODO`/`FIXME` without `T-nnn`,
  no `println`/`System.exit`/`printStackTrace` in library mains
  (`core`/`index`/`sources`/`decompile`). 100-column stays a soft limit
  (774 main-source lines over it) — explicitly not gated. A planted-violation
  negative proof proves it bites.
- Verify: full `./gradlew check` green (tiers 1–2) + both negative proofs
  (removed after) + session entry + open-items.

*Closed in session 76. Both main warnings fixed (the `renderType` classifier
`else` dropped as exhaustive; the `renderProjection` variance `else` narrowed
to an explicit `null` branch — first attempt hit the wrong `when`, the build
said so); `HttpServer.localAddress` cast dropped. `allWarningsAsErrors` gates
main compilations (planted redundant-`else` fails with `-Werror`); `lint`
green and wired into every `check` (planted trailing-WS + bare-TODO fail with
pointers; the rule first fired on its own source — L-106). `explicitApi()` on
index/sources/decompile/mcp/server clean; `cli` reverted (99 violations, no
Kotlin consumers). Test-source + Java remainder filed as T-083. No tier-3 run:
the two fixes remove provably-unreachable branches, so no corpus behaviour
can differ (same reasoning as T-047's tier-1–2 verification).*

### T-083 — warnings-as-errors for test sources + Java · `TODO`

**Depends:** T-052 · **Files:** `build.gradle.kts`,
`core/src/test/...`, `testfixtures/src/main/java/...`

*(Split out of T-052 in session 76: the test-source sweep shows ~11 warnings —
`ExperimentalKotest` opt-in at `core/.../gen/PropertySupport.kt:22` (gate via
`-opt-in` flag, not per-call-site annotations), 5× `shouldNotBeNull { msg }`
"unused expression" in `GenericSignatureTest`/`GenericSignaturePropertyTest`
(columns point at the message string — investigate whether the message lambda
is silently dropped before "fixing"), 4× unnecessary `!!` in the render
property tests, 1× deprecated `Arb.stringPattern` in `ReferencePropertyTest` —
plus the Java `[strictfp]` warning on the deliberately-`strictfp`
`VarargsAndModifiers` fixture (scope Java `-Werror` to exclude
`testfixtures`, or suppress at the declaration). Gate test compilations and
decide Java when starting.)*

### T-048 — AppCDS archive generation · `DONE` (session 77)

**Depends:** T-004 (fat jar + launcher own the flags) · **Files:**
`app/build.gradle.kts` (class-list + archive tasks, wired into `installDist`),
`app/src/main/scripts/jdx` (use the archive when present),
`app/src/test/kotlin/dev/jdx/app/LauncherScriptTest.kt` (present/absent pins + property)

*(M7 cold-start slice, one sitting: the launcher already passes
`-Xshare:auto` (T-004) but ships no archive, so CDS never engages.
Generate `app/build/libs/jdx.jsa` from the fat jar with the build JVM's
`java` (`-XX:DumpLoadedClassList` over `version` + `help` runs, merged +
sorted, then `-Xshare:dump`), ship it via `installDist`, and have the
launcher pass `-XX:SharedArchiveFile=<archive>` only when the file exists
next to the fat jar — missing/stale archives degrade to today's plain
run via `-Xshare:auto`, never a hard failure. No `doctor` row, no
`install.sh` change (the archive travels with the build dir the launcher
already resolves): file follow-ups when starting them. T-050 stays next.)*

- Build: `generateCdsClassList` (fat jar inputs, class-list output) +
  `createCdsArchive` (archive output, `installDist` depends on it); both
  incremental (inputs/outputs declared) and config-cache safe (plain-File
  captures only, T-067 pattern).
- Launcher: fixed `"$jdx_home/libs/jdx.jsa"` path (stable name, not
  versioned); flag order `… -Xshare:auto -XX:SharedArchiveFile=<path> -jar …`
  so a stale archive is ignored, not fatal.
- Tests: tier-2 `LauncherScriptTest` present/absent pins (exec-args
  contain/omit the flag) + a generating property (archive × generated
  version lines: exit codes unchanged, flag iff present); e2e pin that
  `installDist` leaves a non-empty `jdx.jsa` next to the fat jar.
- Verify: `./gradlew :app:installDist` produces the archive;
  `app/build/jdx --version` still exits 0 with/without it;
  `./gradlew :app:check -Ptier1.budget=10000` green.

*Closed in session 77. `generateCdsClassList` + `createCdsArchive` in
`app/build.gradle.kts` (build-JVM `java`, incremental, config-cache safe)
ship `app/build/libs/jdx.jsa` via `installDist`; the launcher passes
`-XX:SharedArchiveFile` only when present (stale archives degrade via
`-Xshare:auto`, probed). Measured `--version` 187 ms → 90 ms cold
(~140 ms → ~228 ms through the real launcher). Tests: 2 tier-2
present/absent pins + archive×version-gate property + `installDist`
archive pin (`LauncherScriptTest` 23/23). Lessons: L-107 (never merge
class lists). Next: T-050.*

### T-050 `jdx bench` against `minecraft-client.jar` · `DONE` (session 78)

**Depends:** T-011 (read-command patterns + `resolveRoots`), T-010 (JSON
envelope) · **Files:** `cli/.../bench/BenchRunner.kt` (new),
`cli/.../render/BenchSheet.kt` (new), `cli/.../commands/BenchCommand.kt`
(new), `cli/.../JdxCli.kt` (register), `cli/.../render/HelpSheet.kt`
(row), `index/.../service/JdxService.kt` (`expandJarSpec` internal→public,
no behaviour change), `cli/build.gradle.kts` (`benchTest` fixtures wiring),
`build.gradle.kts` (`bench` message), `docs/PROPOSAL.md`
(Appendix B row), `README.md` (table row)

*(M7 perf slice, one sitting: PROPOSAL.md §15 promises "a `jdx bench`
command, tagged out of normal test runs, measuring against the local
`minecraft-client.jar` so regressions are caught". The per-module
`benchTest` tasks + root `bench` task are wired but empty — this task
fills them with a smoke plus the CLI command.)*

- `jdx bench [--jars …] [--iterations N] [--json] [-w name] [--no-jdk]
  [--src/--coord/--repo/--fetch]`: resolves roots exactly like the read
  commands (`ReadCommandSupport.resolveRoots`); when no explicit roots and
  no workspace is selected, probes `~/.gradle/caches/fabric-loom/` for the
  newest `minecraft-client.jar` and uses it — missing probe with no roots
  exits 4 naming `--jars`. `--iterations < 1` exits 3; empty jars exit 1
  via `ErrorResult.notFound` (`generic` rejects 1/2, D-015). `--jars`
  (not `--jar`) for read-command consistency.
- Fixed workload in deterministic order over the resolved roots, sampling
  the first/middle/last sorted class names from the jars at runtime (no
  hard-coded symbols — `minecraft-client.jar` is obfuscated): `load`
  (re-open + ASM-read every class, the "first index" proxy, target
  ≤ 8000 ms) then `show`/`members`/`search`/`hierarchy` on the samples
  (cold-query targets ≤ 250 ms each, PROPOSAL.md §15). Each case runs
  `iterations` times (default 3), reported as the median. Targets are
  advisory: rows print `ok`/`OVER` but the command exits 0 on success
  (machine variance — budgets never gate, per the `verifyTier1Budget`
  precedent). `usages` stays out: the live scan has no indexed path yet
  (D-043), so a §15 `≤ 150 ms` row would only document the known gap.
- Text table + `--json` in the standard envelope (`command: "bench"`).
  Timings are inherently non-deterministic (the disciplined exception to
  CLAUDE.md §2, like `doctor` sizes) — everything else (order, labels,
  targets) is byte-stable.
- Tests: tier-1 `BenchRunnerTest` (median/target math + rendering
  determinism + hostile never-throws property — the generating family) +
  `BenchCommandTest` (flag validation, probe-miss exit 4, JSON envelope);
  tier-2 `BenchServiceTest` (real run over the fixture jar: rows present,
  `ms >= 0`, JSON parses); `@Tag("bench") BenchSmokeTest`
  (iterations=1 over the fixture jar completes) so `./gradlew bench`
  runs something real; root `bench` message updated.
- Verify: `./gradlew check` green + `./gradlew bench` green + live
  `jdx bench --iterations 1` over `minecraft-client.jar` + `bench
  --json` valid JSON.

*Closed in session 78. Live: 10,410 classes, `load` 1273 ms ok, cold
query cases OVER as designed (each one-shot re-opens roots — the warm
daemon path is the ≤ 20 ms story). `expandJarSpec` visibility is the only
`index` touch and changes no behaviour, so no tier-3 run (T-047/T-052
precedent). Lessons: L-108. Next: T-051.*

### T-051 README + install docs · `DONE` (session 79)

*(Last M7 task: the README still describes M0–M3/M4-in-progress and the
command table as "(target)" — stale since M6 closed. One sitting,
docs-only: no code, no behaviour, no tests beyond `lint` (md-untouched)
plus a doc-accuracy pass where every command/flag shown is verified
against the built binary.)*

- `README.md`: status line → M0–M6 DONE, M7 state; command surface
  `(target)` → actual incl. `implementors`, `callers`/`calls`,
  `samples`, `ws`/`cache`/`daemon`/`serve`/`mcp`/`batch`/`bench`/
  `help --agent`, `--view kotlin|jvm`, `--brief`/`--max-lines`,
  `--engine vineflower|javap`, `--with-doc`, `--src`, `--coord`/
  `--fetch`/`--repo`, `--no-daemon`; new sections: Requirements
  (JDK 21+, wrapper-only build), Build + install (`:app:installDist`
  → `app/build/jdx`, `./install.sh [--force]` per-user symlink,
  AppCDS `jdx.jsa` presence-check), Quickstart (workspace-less JDK
  query, `ws create`, `--jars`), front-ends (daemon 5-min idle,
  MCP stdio, HTTP `serve`, `batch` NDJSON), exit codes + symbol-ref
  pointer, Kotlin sidecar note (absent → degrade, D-008),
  `bench`/AppCDS advisory-targets note.
*Closed in session 79. Docs-only `README.md` rewrite (no code, no
behaviour): status M0–M6 DONE, command surface actual (incl.
`implementors`, `callers`/`calls`, `serve`/`mcp`/`batch`/`bench`/
`help --agent`, `--view`, `--brief`/`--max-lines`, `--engine`,
`--with-doc`, `--src`, `--coord`/`--fetch`/`--repo`, `--no-daemon`;
dropped the nonexistent `jdx index` row), plus new Requirements /
Build+install / Quickstart / Front-ends / Exit-codes+refs / Kotlin-note
sections — every example and default verified against the built binary
and `--help`. `lint` clean, `check -Ptier1.budget=10000` green (docs-only,
no tier-3 per precedent). M7 left with T-080/T-081/T-083 (low priority).*

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

### T-079 — Match annotation-element members in the JavaParser seam · `DONE` (session 62)

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

*Closed in session 62. Lessons: L-092 (filed in session 61). Full notes in git history +
session log.*

## Open questions

Add here when blocked. Format: `Q-nnn`, the question, why it blocks, and what you did instead.

*(none open)*

**Resolved**
- ~~**Q-001 — GitHub repository name?**~~ Answered 2026-09-13: **`jdx`**. Repo created at
  <https://github.com/MohammadMD1383/jdx>. See D-018.
