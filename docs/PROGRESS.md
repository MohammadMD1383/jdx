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
| **Last updated** | 2026-09-16 (session 20) |
| **Repository** | <https://github.com/MohammadMD1383/jdx> (public, Apache-2.0) |
| **Phase** | Design complete; **M0 done, M1 in progress** |
| **Active milestone** | M2 — Index |
| **Next task** | **T-016 (project auto-discovery)** — lowest unblocked TODO; or T-055 (property infra), T-062 (`--sort` orders), T-063 (share fixture-jar helpers) |
| **Task count** | 63 tasks defined (T-001…T-065; M0–M2 in full detail, M3–M7 as one-liners) |
| **Build status** | **Green.** `./gradlew build` passes. Runnable `jdx`: `./gradlew :app:installDist` → `app/build/jdx` (fat jar + POSIX launcher, JDK-21 gate, ~180 ms cold start); `install.sh` symlinks it into `~/.local/bin`. Commands so far: `--version`, `version [--json]`, `doctor [--json]`, **`show`, `members`, `outline` (all flags, text+JSON, exits 0–4, `-w` workspaces)**, **`ws create|list|info|remove|use|add` (text+JSON, exits 0/1/3/4/6)**. JDK root: zero-config `show`/`members` on `java.*` via `jrt:/`, module-as-artifact (`java.base`), `src.zip` paired from `java.home`/`$JAVA_HOME` (T-012). Workspaces (T-015): TOML in `~/.config/jdx/workspaces/`, §13 resolution (`-w` > `JDX_WORKSPACE` > `ws use`, explicit `--jars` merge first), first-provider-wins shadowing with `DUPLICATE_FQN`. |
| **Test status** | **569 tests, all green** (`./gradlew check` tiers 1+2; soak tier 3 green). T-015 added 73: validation/codec/resolver-order examples + 4 thousand-case properties (TOML fixed-point, merge law) + `WsCommandsTest` (16 in-process, text⊆JSON) + read-flag + doctor tests + 4 tier-2 (`WorkspaceServiceTest`: order-flip reverses the `DUPLICATE_FQN` winner). Shared `GoldenFiles`/`UnifiedDiff` helper (`testfixtures` testFixtures) backs all 280 `index`/`cli` goldens; update mode prints every rewritten path. |
| **Docs** | Append-only logs sharded (D-023): sessions → `docs/progress/`, decisions → `docs/decisions/` (shard 2 open — D-029 workspace shape/semantics), lessons → `docs/lessons/` (`L-026-050.md` open — L-034 (`:` illegal in backtick test names), L-035 (capture stdout around the exit-throw). Every hard-won lesson goes to `docs/LESSONS.md` (D-024). |
| **Blocked on** | Nothing. **Push needs owner go-ahead (D-012)** — session 20 commits unpushed. |

### What exists right now

Documentation, the Gradle skeleton, and **four layers of implemented behaviour**:

1. **The `core` domain model** (`core/src/main/kotlin/dev/jdx/core/model/`):   - `TypeName` (class/array/primitive, binary/FQN/simple names) + `typeNameFromBinaryName`,
     `arrayTypeName`
   - `JvmDescriptor` (field/method descriptors, parse & print, null-on-malformed)
   - `GenericSignature` (full JVMS §4.7.9.1 grammar: class/method/field signatures, type
     variables, nested generics, all three wildcard kinds, throws clauses, void return)
   - `Access`/`AccessFlag`/`Visibility`, `TypeKind`, `ClassInfo`, `MemberInfo`
     (`FieldInfo`/`MethodInfo`), `AnnotationInfo`
   - `SymbolRef` hierarchy (`TypeSymbolRef`, `MemberSymbolRef`, `PackageSymbolRef`,
     `ModuleSymbolRef`, `MavenCoordinate`) — `MemberSymbolRef.parameterTypes` is
     `List<TypeName>?`: `null` = no parameter list, `[]` = zero parameters (D-025)
   - `Provenance`/`Origin`, `Warning`/`WarningCode` (closed enum set)

2. **The symbol reference parser and printer** (`core/src/main/kotlin/dev/jdx/core/ref/`,
   T-003): `SymbolRefParser.parse` implements the full PROPOSAL.md §6 grammar —
   generous input (`#`/`::`/`.` separators, simple/FQ params, JVM descriptors,
   varargs, coordinates, package globs, `<init>`/`<clinit>`), canonical output via
   `SymbolRefPrinter.print`, positioned structured failures (`Failure(message,
   position)`, never throws). Disambiguation rules are **D-025** — read it before
   touching the parser. Round-trip is a pinned property (`parse(print(ref)) == ref`).

3. **A runnable `jdx` distribution** (`cli` + `app`, T-004/T-005):
   - `cli`: `JdxCli` root command (Clikt **`clikt-core`** — plain flavor; the mordant
     flavor eagerly loads JNA, L-011) with `--version`; `BuildInfo` reads the
     Gradle-generated `build.properties` so the version is never hard-coded.
   - **Commands (T-005):** `version [--json]` and `doctor [--json]`. `DoctorService`
     (`cli/.../service/`) runs ten injectable, exception-proof checks (jdk, jrt, javap,
     jdk-sources, cache, config, index, kotlin, daemon, workspace) with OK/WARN/FAIL
     severity per D-027; `DoctorReport` feeds a text and a JSON renderer
     (`cli/.../render/JsonEnvelope.kt`, the T-010 seed: `{"jdx":1,ok,command,result}`).
     Exit 0/6 per D-015; `--json` works before or after the subcommand.
   - `app`: `fatJar` task (hand-rolled, byte-deterministic, `Main-Class
     dev.jdx.cli.JdxCliKt`) + `installDist` → `app/build/jdx` — a POSIX launcher that
     resolves a JDK (`JAVA_HOME` → PATH → `JDX_JVM_DEFAULT_DIR`, D-026), gates on
     Java 21+, applies the one-shot JVM flags, and fails with one human line (exit 6).
    - `install.sh` at the repo root: no-root symlink install into `~/.local/bin` with a
      `--force` clobber guard.

4. **The fixture corpus** (`testfixtures/`, T-006): 12 deliberately nasty Java/Kotlin
   sources compiled to a byte-deterministic binary jar + `-sources.jar`
   (`:testfixtures:jar :testfixtures:sourcesJar`); every source-declared type carries
   `@ExpectedMembers` with its `javap -p` truth (bridges, `this$0`, `$default` stubs
   included) so adding a fixture adds coverage; `Fixtures` helper
   (`core/src/test/.../fixtures/`) resolves the jars without hard-coded paths and never
   loads a fixture class; `StaticInitMarker` + absent-marker test prove D-017; one class
   compiles `-g:none`. How to extend: `docs/TESTING.md` §11.1.

 5. **The artifact layer** (`index/src/main/kotlin/dev/jdx/index/artifact/`, T-007):
   a uniform `ArtifactRoot` (`classEntryPaths`/`openClass`/`stableId`/`warnings`) over
   jars, class dirs and `jrt:/` (`JarArtifact`/`DirArtifact`/`JrtArtifact`, all opened
   via the `ArtifactLoader` dispatch), plus `ZipSafety` (traversal rejection +
   entry/total/per-read caps), `ArtifactHash` (SHA-256/128 content ids), `MultiRelease`
   (manifest-gated variant selection + `MULTI_RELEASE_VARIANT`) and `SourcesPairing`
   (sealed `External`/`Embedded`/`Absent`, five §5.2 rules). Proven by 28 tier-1 tests
   (incl. a 1,000-case normalisation property) and 21 tier-2 tests over real and
   crafted jars.

6. **The ASM class reader** (`index/src/main/kotlin/dev/jdx/index/asm/`, T-008):
   `AsmClassReader.read` maps class-file bytes to `ClassInfo`/`MemberInfo` with
   `SKIP_FRAMES` (never `SKIP_DEBUG`): kind, access, superclass (normalised `null`
   for interfaces/`Object`), interfaces, generic signatures (malformed → `null`),
   annotations, `InnerClasses` outer class, `Deprecated`, source file, members with
   descriptors/throws/deprecation/`AnnotationDefault`/`ConstantValue`, and param
   names from `MethodParameters` then `LocalVariableTable` then `null`. Errors are a
   sealed `ClassReadResult` (`Ok`/`UnsupportedVersion`/`Corrupt`) — never throws, so
   one bad entry never aborts a jar. Core gained three defaulted slots for it
   (`ClassInfo.deprecated`, `MethodInfo.annotationDefault`,
   `FieldInfo.constantValue`). Proven by 14 tier-1 tests over in-memory ASM-built
   classes plus a `javap -p -s` differential over every fixture class, a JRT smoke
   (major-70 JDK-26 bytes parse), corrupt-neighbour isolation and the D-017 marker
   proof (7 tier-2 tests).

7. **The member resolver** (`core/src/main/kotlin/dev/jdx/core/resolve/`, T-009,
   session 12): `MemberResolver.resolve` implements PROPOSAL.md §9.3 — BFS
   linearisation (superclass then interfaces, first-visit-wins, `Object` last),
   transitive generic substitution (raw edges erase to `Object`, method type
   params shadow class ones), override collapse by name+erased-descriptor
   (fields by name), JLS visibility from the target's package,
   synthetic/bridge filtering, ctors never inherited, `<clinit>` never listed,
   missing supertypes skipped and reported sorted. Enabling fix:
   `GenericSignature.parseClass` (lone-superclass signatures are class
   signatures by construction — L-024). Proven by 16 tier-1 examples, 6
   thousand-case properties, and 4 tier-2 JRT tests over real
   `java.util.HashMap`.

8. **The text and JSON renderers** (`core/src/main/kotlin/dev/jdx/core/render/`,
   T-010, session 13): `MemberListing` (the one result model both renderers
   read) built from `ResolvedMembers` — grouped by declaring type in
   linearisation order, kind-then-name sort, `Object` collapsed to one pinned
   summary line, `declaredOnly` outline mode, `:return` ref disambiguation only
   for bridge siblings, `UNRESOLVED_SUPERTYPE` warnings; `SignatureLines`
   (FQN `$`-joined one-liners); `Truncation` (whole rows, `shown`/`total`/
   `--limit` hint, default cap 50); hand-rolled JSON envelope v1 (core stays
   dependency-free); `ErrorResult` (exit 1/2, both renderers); `Ansi` color
   gate. Layout choices in D-028. Proven by 52 tier-1 tests (examples + 5
   thousand-case properties) + 3 cli JSON-validity tests (real-parser check) +
   70 golden files over all 35 fixture classes (hermetic: fixed artifact label,
   `Object` stub, no JRT; rewrite with `-Pgolden.update=true`, seed of T-054).

All 232 `core` tier-1 tests are round-trip / structural / property tests written **test-first**
(D-020); generators live in `core/src/test/kotlin/.../gen/` (seed of T-055). The `cli`
tests (T-004/T-005) cover the launcher with stub-JDK fault injection, an exhaustive
576-combination doctor environment matrix (never-throws, exit-code law, text↔JSON parity,
determinism), generated version-gate and tool-version-parser properties, an install.sh
suite, and real-JVM end-to-end tests.

 9. **The persistent index seam** (`index/.../store/`, T-013, session 18):
    `IndexStore` (hash-keyed artifacts, per-artifact `replaceClasses`, `ClassHit`
    cross-artifact lookup) with the only-SQL-here `sqlite/SqliteIndexStore`
    (WAL, `user_version` migrations that refuse newer files, full §10.3 schema).
    Proven by 12 tier-2 example tests (every fixture class round-trips exactly),
    2 properties (500-case fixed point, store-twice determinism) and 2 storage-
    contract tests. No indexer yet — T-014 calls `upsert → replaceClasses`.

10. **The parallel indexer** (`index/.../index/ArtifactIndexer`, T-014,
    session 19): `indexOne` (jar/dir) + `indexJdk` (`jrt:/`) + `indexMany`
    (one virtual thread per artifact, completion-order progress listener) over
    the `indexRoot` seam — hash → short-circuit (`SKIPPED`, untouched) → ASM
    every entry (bad entries warn, never abort) → `upsert` + one-transaction
    `replaceClasses`; per-artifact failures become `FAILED` report entries.
    `BulkWriter` prepares the 7 write statements once per artifact (JDK write
    pass 14.7 s → 7.7 s). Measured on JDK 26 (33,104 entries): read pass
    5–6k/s, end-to-end 2,502/s cold (gap filed as T-064); 4 JFR `$$` classes
    warn as designed (T-065). Proven by 10 tier-2 tests (incl. a 10 %-step
    truncation sweep and a two-store determinism metamorphic) + 1 soak test
    (full JDK, excluded from `check`).

11. **Named workspaces** (`index/.../workspace/`, `cli/.../commands/WsCommands.kt`,
    T-015, session 20): `WorkspaceDefinition` + hand-rolled 3-key `WorkspaceToml`
    codec + `WorkspaceStore` (file: `~/.config/jdx/workspaces/<name>.toml`,
    `active-workspace` default; in-memory fake) + pure `WorkspaceResolver`
    (§13: `-w` > `JDX_WORKSPACE` > `ws use`, explicit `--jars` merge first,
    `--no-jdk` always wins). `jdx ws create|list|info|remove|use|add`
    (text+JSON, exits 0/1/3/4/6); `-w/--workspace` on `show`/`members`/`outline`
    in both flag positions; `doctor` workspace row reports the selection +
    project root + stored count. Shadowing/`DUPLICATE_FQN` reuse the existing
    first-provider-wins order (decision record: D-029). Proven by 4 tier-1
    suites (incl. 4 thousand-case properties) + 16 in-process `ws` tests +
    read-flag/doctor tests + 4 tier-2 service tests (order-flip reverses the
    `DUPLICATE_FQN` winner).

Docs of note: `docs/LESSONS.md` (+ `docs/lessons/` shards) — mistakes already paid for,
now L-001…L-025 (`L-001-025.md` full — next lesson creates `L-026-050.md`). Skim its index before fighting a toolchain or spec.

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
| `docs/progress/sessions-001-010.md` | 1–10 | full |
| `docs/progress/sessions-011-020.md` | 11– | open (2 free) |

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
