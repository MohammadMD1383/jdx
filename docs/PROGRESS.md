# Progress log

**Append-only.** Newest entry directly under CURRENT STATE, older entries below it.
Never edit or delete a past entry — if it turned out to be wrong, say so in a *new* entry.

---

# CURRENT STATE

> **Read this block first. It is the handoff.**
> Whoever writes the last session entry is responsible for making this block true.

| | |
|---|---|
| **Last updated** | 2026-09-13 (session 3) |
| **Repository** | <https://github.com/MohammadMD1383/jdx> (public, Apache-2.0) |
| **Phase** | Design complete; **M0 implementation in progress** |
| **Active milestone** | M0 — Skeleton |
| **Next task** | **T-003 — symbol reference parser and printer** (`docs/TASKS.md`) |
| **Task count** | 58 tasks defined (T-001…T-060, M0–M2 in full detail, M3–M7 as one-liners) |
| **Build status** | **Green.** `./gradlew build` passes. |
| **Test status** | **75 tests in `core`, all green**, ~10 s for the full tier-1 run. |
| **Blocked on** | Nothing. (Q-001 resolved: repo name is `jdx`.) |
### What exists right now

Documentation, the Gradle skeleton, and **the first implemented behaviour: the `core`
domain model** (`core/src/main/kotlin/dev/jdx/core/model/`):

- `TypeName` (class/array/primitive, binary/FQN/simple names) + `typeNameFromBinaryName`,
  `arrayTypeName`
- `JvmDescriptor` (field/method descriptors, parse & print, null-on-malformed)
- `GenericSignature` (full JVMS §4.7.9.1 grammar: class/method/field signatures, type
  variables, nested generics, all three wildcard kinds, throws clauses, void return)
- `Access`/`AccessFlag`/`Visibility`, `TypeKind`, `ClassInfo`, `MemberInfo`
  (`FieldInfo`/`MethodInfo`), `AnnotationInfo`
- `SymbolRef` hierarchy (`TypeSymbolRef`, `MemberSymbolRef`, `PackageSymbolRef`,
  `ModuleSymbolRef`, `MavenCoordinate`) — structure only; parsing is T-003
- `Provenance`/`Origin`, `Warning`/`WarningCode` (closed enum set)

All 75 tests are round-trip / structural tests written **test-first** (D-020).

```
CLAUDE.md            project instructions — the entry point, read first
README.md            user-facing intro
CONTRIBUTING.md      conventions, code style, definition of done
.gitignore
docs/PROPOSAL.md     the full design spec (~1120 lines) — the "why" and the "what"
docs/DECISIONS.md    D-001…D-021, locked vs open — do not re-litigate `locked` entries
docs/TASKS.md        T-001…T-060, the backlog — pick your next task here
docs/TESTING.md      the testing strategy — read before writing tests
docs/PROGRESS.md     this file
LICENSE              Apache-2.0

settings.gradle.kts  8 modules: core index sources decompile cli mcp server app
build.gradle.kts     shared config (toolchain, JUnit 5, test-tier tag exclusion)
gradle/libs.versions.toml   EVERY dependency version — no inline versions anywhere
gradlew, gradle/wrapper/    Gradle 9.7.1, already cached locally so no download
<module>/build.gradle.kts   per-module deps
<module>/src/main/kotlin/dev/jdx/<pkg>/ModuleInfo.kt
                     ^ read these. Each documents its module's responsibility and the
                       boundary rules a newcomer would otherwise break.
```

Verify it yourself:
```bash
./gradlew build        # green
./gradlew projects     # lists all 8 modules
```

### The working cadence (D-022)
**One small task → commit → push → log → next task.** Claim the task by committing its
`TODO`→`WIP` change *before* you start. If a task won't fit in one sitting, split it in
`docs/TASKS.md` first. An unpushed, unlogged working tree is invisible to every other
contributor.

### If you are an agent picking this up cold, do exactly this
1. Read `CLAUDE.md` (5 min). It is short and it is the contract.
2. Read `docs/DECISIONS.md` (10 min). It prevents you from redesigning settled things.
3. Skim `docs/PROPOSAL.md` §§5–9 (the conceptual model, refs, commands, architecture).
   Read the rest of it lazily, when a task sends you there.
4. Open `docs/TASKS.md`, take **T-001**, follow the rules at the top of that file.
5. Before you stop, append a session entry below using the template at the bottom of this
   file, and update the CURRENT STATE block above.

### Things a newcomer will otherwise get wrong
- `JAVA_HOME` is **unset** on the owner's machine and there is **no `gradle` or `mvn` on
  PATH**. Use the Gradle wrapper; make the launcher script resolve a JDK itself.
- `core` must stay dependency-free. It is tempting to `import org.objectweb.asm` there. Don't.
- Front-end modules (`cli`, `mcp`, `server`) must contain **no logic**. Four front-ends were
  chosen deliberately (D-004); the only way they stay consistent is if they are all thin.
- `--json` is not a second-class citizen. A command whose JSON output is missing information
  the text output has is a bug, not a nicety (D-007).
- The tool must never load an inspected class into the JVM (D-017). Use ASM, not reflection.
- Testing is not "add a few unit tests at the end". `core` is **test-first**, and every
  behaviour needs at least one *generative* test family (D-020, `docs/TESTING.md` §2). The
  `javap` differential harness (T-056) and the corpus soak (T-059) are where most real bugs
  will be caught — build them early, not last.
- `./gradlew test` must stay under 30 s. If you put a jar-reading test in tier 1, you have
  started the slow slide that ends with nobody running tests.

---

# Session log

<!-- newest first -->

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

---

# Template — copy this for every session

Keep entries factual and specific. "Refactored some stuff" helps nobody. Name files, name
tasks, name the commands you ran. Write for someone with none of your context.

```markdown
## Session N — YYYY-MM-DD — <one-line summary>
**Agent/Author:** <model or person> · **Commits:** <range or "none">

### Goal
<What you set out to do, and which task IDs it covers.>

### What I did
<Concrete changes. Files touched. Commands that matter. Findings that surprised you.>

### Decisions made
<New D-nnn entries, or "none". Anything non-obvious you chose while coding belongs in
docs/DECISIONS.md, not only here.>

### Tasks moved
<T-nnn: TODO → WIP → DONE. Keep docs/TASKS.md in sync — this list is the audit trail.>

### What works now (and how to verify it yourself)
<Exact commands a successor can run to see the state for themselves. This is the single most
useful part of the entry — a successor trusts what they can reproduce.>

### What is broken / half-done
<Be honest and specific. A known-broken thing that is documented costs an hour;
an undocumented one costs a day. Include the file and the reason you stopped.>

### Open questions / blockers
<New Q-nnn entries, or "none".>

### Next action
<The single next task ID, and anything the next person needs to know to start it cold.>
```

**Before you stop, verify:**
- [ ] `docs/TASKS.md` statuses match reality
- [ ] The **CURRENT STATE** block at the top of this file is true
- [ ] New decisions are in `docs/DECISIONS.md`, not buried in this log
- [ ] Your entry's "how to verify it yourself" commands actually work when pasted
