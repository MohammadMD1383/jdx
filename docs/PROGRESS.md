# Progress log

**Structure (D-023):** this file is the *entry file* — it holds only the `CURRENT STATE`
handoff, the shard index, and the session template. Session entries live in
`docs/progress/sessions-NNN-NNN.md` shards (10 sessions each, newest first). Never edit
or delete a past entry — if one turned out to be wrong, say so in a *new* entry.

---

# CURRENT STATE

> **Read this block first. It is the handoff.**
> Whoever writes the last session entry is responsible for making this block true.

| | |
|---|---|
| **Last updated** | 2026-09-13 (session 4) |
| **Repository** | <https://github.com/MohammadMD1383/jdx> (public, Apache-2.0) |
| **Phase** | Design complete; **M0 implementation in progress** |
| **Active milestone** | M0 — Skeleton |
| **Next task** | **T-003 — symbol reference parser and printer** (`docs/TASKS.md`) |
| **Task count** | 59 tasks defined (T-001…T-061; M0–M2 in full detail, M3–M7 as one-liners) |
| **Build status** | **Green.** `./gradlew build` passes. |
| **Test status** | **75 tests in `core`, all green**, ~10 s for the full tier-1 run. |
| **Docs** | Append-only logs sharded (D-023): sessions → `docs/progress/`, decisions → `docs/decisions/`, lessons → `docs/lessons/`. Every hard-won lesson goes to `docs/LESSONS.md` (D-024). |
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

Docs of note: `docs/LESSONS.md` (+ `docs/lessons/` shards) — mistakes already paid for,
seeded with L-001…L-009. Skim its index before fighting a toolchain or spec.

```
CLAUDE.md            project instructions — the entry point, read first
README.md            user-facing intro
CONTRIBUTING.md      conventions, code style, definition of done
.gitignore
docs/PROPOSAL.md     the full design spec (~1120 lines) — the "why" and the "what"
docs/DECISIONS.md    decision index; entries in docs/decisions/ shards (D-023)
docs/LESSONS.md      lessons index + rules (D-024); entries in docs/lessons/ shards
docs/TASKS.md        T-001…T-061, the backlog — pick your next task here
docs/TESTING.md      the testing strategy — read before writing tests
docs/PROGRESS.md     this file: CURRENT STATE + shard index + template
docs/progress/       session-log shards (10 sessions each, newest first)
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
2. Read the `docs/DECISIONS.md` **index** (5 min), then open the shard entries for the
   decisions your task touches. It prevents you from redesigning settled things.
3. Skim `docs/LESSONS.md` — mistakes already made and paid for.
4. Skim `docs/PROPOSAL.md` §§5–9 (the conceptual model, refs, commands, architecture).
   Read the rest of it lazily, when a task sends you there.
5. Open `docs/TASKS.md`, take the lowest-numbered unblocked `TODO`, and follow the rules
   at the top of that file.
6. Before you stop: append a session entry to the newest `docs/progress/` shard (template
   at the bottom of this file), update the CURRENT STATE block above, and distill any new
   lessons into `docs/LESSONS.md` (D-024).

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

# Session log — sharded (D-023)

Entries live in `docs/progress/`, newest first. Append to the shard with free capacity;
when it holds 10 sessions, create the next (`sessions-011-020.md`) and update this index.

| Shard | Sessions | Status |
|---|---|---|
| `docs/progress/sessions-001-010.md` | 1–4 | open (6 free) |

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

### Lessons distilled
<New L-nnn entries added to docs/LESSONS.md, or "none".>

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
- [ ] New decisions are in `docs/DECISIONS.md`'s newest shard, not buried in this log
- [ ] New lessons are in `docs/LESSONS.md`'s newest shard, not buried in this log
- [ ] Your entry's "how to verify it yourself" commands actually work when pasted
