# Session log — shard 1: sessions 1–10

Append-only session log, **10 sessions per shard** (D-023). `docs/PROGRESS.md` holds the
`CURRENT STATE` handoff, the shard index, and the entry template — this file only holds
entries. Newest entry first; never edit or delete a past entry — if one turned out to be
wrong, say so in a *new* entry.

Sessions 1–3 were moved here **verbatim** from `docs/PROGRESS.md` in session 4 (git
history preserves the pre-shard file).

---

## Session 4 — 2026-09-13 — docs sharded, lessons log created (D-023, D-024, T-061)
**Agent:** GLM (via opencode) · **Commits:** single commit, this entry included

### Goal
Owner instruction: shard ever-growing documents (PROGRESS.md named as the example,
"progress1-10, progress11-20, etc…"), and record every contributor's experience so later
agents don't repeat mistakes — both driven by agent context efficiency ("use references,
read only when needed").

### What I did
1. Recorded the instructions as **D-023** (sharding) and **D-024** (lessons log), both
   `locked`, quoted in the decision entries.
2. Restructured the three append-only logs into entry-file + shards (D-023):
   - `docs/PROGRESS.md` → `CURRENT STATE` + shard index + template; sessions 1–3 moved
     **verbatim** to `docs/progress/sessions-001-010.md` (bash line extraction,
     `diff`-verified byte-identical).
   - `docs/DECISIONS.md` → contributor rules + status legend + index; D-001…D-022 moved
     verbatim to `docs/decisions/D-001-025.md`, which now also holds D-023/D-024.
   - New `docs/LESSONS.md` (rules + index) with entries in `docs/lessons/L-001-025.md`,
     seeded with nine lessons (L-001…L-009) distilled from sessions 1–3.
3. Updated every cross-reference: `CLAUDE.md` (reading list, §4 layout, §7 agreements),
   `CONTRIBUTING.md` (docs table, For-AI-agents list), `docs/TASKS.md` (board step 5,
   new T-061), and the PROGRESS.md template (new "Lessons distilled" section).

### Decisions made
D-023, D-024 — both owner instructions from this session, both `locked`. Shard capacities
(10 sessions, 25 decisions, 25 lessons) are `proposed-by-implementer` defaults noted
inside D-023.

### Tasks moved
- T-061: created → DONE (owner-directed, this session).

### Lessons distilled
L-005…L-009 were seeded from earlier sessions; this session added none beyond the
restructure itself (the moving-files-verbatim-with-diff-verification technique is now
documented in this entry, which is where it belongs — it was used once).

### What works now (and how to verify it yourself)
```bash
/usr/bin/find docs -name '*.md' | sort   # new layout: progress/, decisions/, lessons/
rg -c '^## Session' docs/progress/sessions-001-010.md   # 4 sessions, nothing lost
rg -c '^## D-' docs/decisions/D-001-025.md              # 24 decisions, nothing lost
./gradlew build                          # still green (docs-only change)
```

### What is broken / half-done
Nothing. Note: git history preserves the pre-shard `docs/PROGRESS.md` and
`docs/DECISIONS.md` in full if the old shape is ever needed.

### Open questions / blockers
None.

### Next action
**T-003 — symbol reference parser and printer** (unchanged from session 3's handoff).

## Session 3 — 2026-09-13 — T-002 core domain model, test-first
**Agent:** GLM (via opencode) · **Commits:** `e024333` (claim) … this session

### Goal
**T-002 — dependency-free `core` module foundations**, the first behavioural task,
developed test-first (D-020).

### What I did
1. Claimed T-002 (`chore: claim T-002`) and pushed, per the D-022 cadence.
2. **RED:** wrote 5 test files (75 tests) defining the API: `TypeNameTest`,
   `JvmDescriptorTest`, `GenericSignatureTest`, `AccessTest`, `ModelTest`. Verified the
   suite fails to compile for exactly the right reason (types don't exist).
3. **GREEN:** implemented 9 files under `core/src/main/kotlin/dev/jdx/core/model/`:
   `TypeName.kt`, `JvmDescriptor.kt`, `GenericSignature.kt`, `Access.kt`,
   `MemberInfo.kt`, `ClassInfo.kt`, `SymbolRef.kt`, `Provenance.kt`, `Warning.kt`.
   Every public type has a KDoc line (what it represents, who produces it). All 75 tests
   pass; `./gradlew build` green for the whole project.

### Decisions made
None new at decision-log level. Two design notes worth recording here:
- **`V` is not a field type.** My first test round-tripped `V` as a field descriptor;
  JVMS §4.3.2 permits void only as a method return type. The *test* was wrong and was
  fixed; the parser deliberately rejects `V` in field position.
- **Signature grammar ambiguity:** `LFoo;` is both a valid field signature and a valid
  parameterless class signature. `GenericSignature.parse` prefers the field reading and
  falls back to the class reading only when input remains unconsumed. Documented on the
  `parse` KDoc.

### Tasks moved
- **T-002: `TODO` → `WIP` → `DONE`.** All four acceptance criteria verified below.

### What works now (and how to verify it yourself)
```bash
./gradlew :core:test                              # 75 tests, green, ~10 s
./gradlew build                                   # whole project green
./gradlew :core:dependencies --configuration compileClasspath
                                                  # kotlin-stdlib only — core stays dependency-free
```

### What is broken / half-done
Nothing broken. Half-done by design: `SymbolRef` is structure-only — the parser/printer
(`SymbolRefParser`/`SymbolRefPrinter`) is T-003 and was deliberately not leaked into this
task. Property-based generators (`Arb<TypeName>` etc., T-055) will extend the round-trip
tests to generated cases later; today they cover a hand-picked nasty set (nested generics,
all wildcard kinds, recursive bounds, `$` nesting, arrays of arrays).

### Open questions / blockers
None.

### Next action
**T-003 — symbol reference parser and printer.** Builds directly on `TypeName` and
`MavenCoordinate`; implements PROPOSAL.md §6. Note T-004, T-006 and T-053 are also
unblocked if a contributor prefers them.

## Session 2 — 2026-09-13 — repo published, T-001 build skeleton
**Agent:** Claude Opus 5 (1M context) · **Commits:** `af32f73`…`81ead50`

### Goal
Publish the repository and complete **T-001** (Gradle multi-project skeleton).

### What I did
1. **Published the repo.** Owner answered Q-001: the name is **`jdx`**.
   <https://github.com/MohammadMD1383/jdx>, public, Apache-2.0. D-018 closed.
2. **Adopted the owner's work cadence** (D-022): *one small task → commit → push → log →
   next task*. Claim a task by committing its `TODO`→`WIP` change **before** starting.
3. **T-001 — Gradle multi-project skeleton.** Eight modules, Kotlin 2.4.20, Java 21
   toolchain, wrapper pinned to Gradle 9.7.1.

### Decisions made
None new beyond D-018 (repo name) and D-022 (cadence), both recorded in `docs/DECISIONS.md`.

### Tasks moved
- **T-001: `TODO` → `WIP` → `DONE`.** Every acceptance criterion verified (evidence below).

### What works now (and how to verify it yourself)
```bash
./gradlew build                 # green — the whole skeleton compiles
./gradlew projects              # app, cli, core, decompile, index, mcp, server, sources
./gradlew :core:dependencies --configuration compileClasspath
                                # kotlin-stdlib only — core's no-dependency rule holds
```
Verified by **cloning the public repo into a clean directory and building there** — so
"works on my machine" is ruled out. Every version pinned in the catalog was resolved for
every module (2–60 artifacts each), which proves none of them is a typo or a nonexistent
release.

### Things worth knowing that cost me time
- **`libs` is not in scope inside a `subprojects { }` block.** Gradle generates the version
  catalog accessor per build script; inside `subprojects` the receiver is a subproject that
  has no such extension, and you get a confusing *"Extension with name 'libs' does not
  exist"*. Fix used: capture the catalog values at root scope into `val`s and reference those
  inside the block. Module build scripts use `libs.*` directly and are unaffected. If you
  later move this to a `buildSrc` convention plugin, the problem disappears.
- **`grep` on this machine is `ugrep`** and misparses some flag combinations (`-vF '-'`).
  Prefer Python for text processing in scripts meant to be portable.
- **`ls -la` hangs** in this environment (shell alias). Use `/usr/bin/find` or `/usr/bin/ls`.
- **No `gradle` on PATH**, so the wrapper was generated by invoking the cached distribution
  directly at
  `~/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle wrapper`.
  Worth remembering if the wrapper ever needs regenerating.

### What is broken / half-done
Nothing is broken. Nothing is *implemented* either — every module contains exactly one
`ModuleInfo.kt`, which is documentation, not code. That is the intended end state of T-001.

Note `gradle/libs.versions.toml` pins several libraries (ASM, Vineflower, SQLite,
JavaParser, MCP SDK) that no code uses yet. That is deliberate: declaring them now proved
every pinned version actually resolves, so a later task doesn't discover a bad version at a
bad moment.

### Open questions / blockers
None.

### Next action
**T-002 — dependency-free `core` foundations.** It is the first **test-first** task
(D-020): write the failing test, then the model. Acceptance criteria in `docs/TASKS.md`.
Start with `TypeName` and `JvmDescriptor` — `SymbolRef` (T-003) builds directly on them.

## Session 1 — 2026-09-13 — design, decisions, and project scaffolding docs
**Agent:** Claude Opus 5 (1M context) · **Duration:** one sitting · **Commits:** see `git log`

### Goal
Turn the owner's verbal brief ("a CLI that acts like an IDE, but for agents, over jars and
sources jars") into a settled design, a decision record, and a backlog detailed enough that a
different contributor can start coding without asking anything.

### What I did
1. **Surveyed the machine** so the design is grounded rather than generic:
   - Arch Linux, **JDK 26** at `/usr/lib/jvm/default`; `javap`/`jar`/`jdeps` present;
     `JAVA_HOME` **unset**; **no `mvn`/`gradle` on PATH**, but Gradle 9.6.1 and 9.7.1
     distributions are already cached in `~/.gradle/wrapper/dists`, so the wrapper will work.
   - Maven Central and GitHub reachable; `git` and `gh` installed.
   - ~2,183 jars in `~/.gradle/caches`, including `-sources.jar` artifacts (gson, msal4j,
     lwjgl, mojang-logging) and a large obfuscated `minecraft-client.jar` under
     `~/.gradle/caches/fabric-loom/`. ASM and Vineflower already cached.
   - Chose **gson** as the everyday correctness fixture and **minecraft-client.jar** as the
     scale benchmark.
2. **Asked the owner 12 questions in 3 rounds** rather than assuming. Every answer is
   recorded in `docs/DECISIONS.md` as D-001…D-012, with the alternatives that were rejected
   and why — so a future contributor can see the shape of the decision, not just its result.
3. **Wrote `docs/PROPOSAL.md`** (~1,100 lines): problem statement, an explicit
   IDE-capability → `jdx`-command parity table, the agent-specific capabilities that have no
   IDE equivalent (§3 — context economy, machine-legible failure, never-guess, provenance,
   `samples`, `batch`), conceptual model, symbol-ref grammar, full command surface, output
   design, module architecture, index schema, truth model, Kotlin plan, performance targets,
   testing strategy, licensing, 8 milestones, deferred work, risks, and a worked end-to-end
   agent session.
4. **Wrote `CLAUDE.md`** as the durable entry point: principles, locked decisions table,
   layout, exit-code contract, symbol-ref cheat sheet, working agreements, machine notes.
5. **Wrote `docs/DECISIONS.md`** (D-001…D-019) separating `locked` owner decisions from
   `proposed-by-implementer` defaults, so contributors know what they may change freely.
6. **Wrote `docs/TASKS.md`** (T-001…T-060) with a "how to pick your next task" protocol,
   dependency links, and per-task acceptance criteria for M0–M2 in full detail.
7. **Wrote `CONTRIBUTING.md` and `README.md`** after the owner reframed the project mid-session
   as community-developed and explicitly required that any future agent be able to resume cold
   (quoted in D-012, formalised as D-019). This turned documentation from a nicety into a
   release gate, and is why `docs/TASKS.md`, `CONTRIBUTING.md` and the `CURRENT STATE` block
   above exist in the shape they do.
8. **Wrote `docs/TESTING.md`** after the owner asked for heavy testing and proposed TDD
   (D-020). My recommendation, which the strategy implements: **adopt TDD but do not stop
   there.** TDD verifies behaviour *you* define — perfect for the ~40 % of `jdx` that is our
   own rules (ref grammar, member resolution, generic substitution, truncation, rendering),
   and mandatory there. The other ~60 % reads files emitted by other people's compilers,
   where correct output is "whatever `javac` actually produced" and cannot be invented in a
   test. For that half the strategy leans on families that **generate their own cases**:
   differential testing against `javap` (a free exhaustive oracle), compile-back testing for
   decompilation, property-based tests, metamorphic relations, fault injection, a soak run
   over the ~2,183 real local jars, and Pitest mutation scoring as the meta-test that the
   tests actually assert something. Ten families, four runtime tiers, gates in D-021.

### Key decisions made this session
See `docs/DECISIONS.md` (D-001…D-021) for all of them with rationale and rejected
alternatives. Headlines: Kotlin/Gradle; Vineflower bundled with `javap` as an alternate
engine; persistent SQLite index keyed by artifact content hash; all four interfaces (CLI,
MCP, daemon with 5-minute idle shutdown, HTTP); name `jdx`; text output by default with
`--json`; full Kotlin PSI source parsing with mandatory lazy/isolated-classloader
mitigations; bytecode-is-skeleton/sources-are-flesh; usages across jars *and* project
sources; documentation-for-successors as a release gate (D-019); TDD plus nine further test
families with a mutation-score gate on `core` (D-020, D-021).

### Where I pushed back on the owner
The owner proposed TDD. I adopted it but argued it is insufficient alone for this project
(reasoning above and in D-020), and expanded the plan to ten test families. The owner
delegated this explicitly ("if you know a better way go with that"), so it is recorded as a
locked decision with the reasoning preserved — a future contributor who thinks "why all this
machinery, why not just unit tests?" should read D-020 before simplifying it away.

### What I deliberately did **not** do
- **No code.** The owner asked for a proposal first. Starting M0 before the design was
  approved would have produced churn.
- **No `gh repo create` / no push.** D-012 authorises a public repo, but the name is still
  open (Q-001) and pushing is an outward, irreversible act that needs an explicit go-ahead in
  the conversation where it happens.
- **No jar API diff** (`jdx diff`) — the owner explicitly deferred it to phase 2.
- **No fine-grained tasks for M3–M7.** Writing 40 detailed tasks for work six milestones out
  would be invented precision. They are listed as one-liners and should be expanded when
  their milestone begins.

### Open questions / risks handed forward
- **Q-001 — GitHub repository name?** Blocks repo creation only; does not block coding.
- The `kotlin-compiler-embeddable` decision (D-008) is the biggest single risk in the plan:
  ~55 MB and ~1 s init. The five mitigations in D-008 are *acceptance criteria* for T-038,
  not suggestions. If they cannot be met, stop and ask the owner rather than shipping a slow,
  fat CLI.
- SQLite (D-013) is `proposed-by-implementer`. If it proves wrong at Minecraft scale, it is
  behind the `IndexStore` interface and may be replaced — measure first (T-050).

### Next action for whoever is next
**T-001 — Gradle multi-project skeleton.** Everything else depends on it. Acceptance criteria
are in `docs/TASKS.md`.

