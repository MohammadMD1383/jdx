# Session log — shard 2: sessions 11–20

Append-only session log, **10 sessions per shard** (D-023). `docs/PROGRESS.md` holds the
`CURRENT STATE` handoff, the shard index, and the entry template — this file only holds
entries. Newest entry first; never edit or delete a past entry — if one turned out to be
wrong, say so in a *new* entry.

---

## Session 16 — 2026-09-16 — Merge + push T-011/T-012 to `origin/main`
**Agent:** Muse Spark (via opencode) · **Branch:** `t011-show-outline-members` → `main` ·
**Commits:** none (merge only)

### Goal
Owner-directed: merge the T-011/T-012 branch and push (D-012 go-ahead given in
conversation).

### What I did
1. Verified the branch held exactly the 4 T-011/T-012 commits over `main`
   (`c82dde1`, `18c79db`, `cdc7490`, `0079ea3`) and that `origin/main` was still
   at `e887df0` (`git fetch`; no drift).
2. Fast-forward-merged into `main` (no merge commit — linear history, same as
   session 13's T-009 merge) and pushed: `e887df0..0079ea3 main -> main`.
3. Deleted the merged branch `t011-show-outline-members`.
4. Cleared the "unpushed work" line in CURRENT STATE.

### Decisions made
None.

### Tasks moved
None (T-011/T-012 already DONE; this session only moved the commits).

### Lessons distilled
None.

### What works now (and how to verify it yourself)
```bash
git log origin/main --oneline -3   # 0079ea3 on top, in sync with local main
git status -sb                     # ## main...origin/main, clean
```

### What is broken / half-done
Nothing.

### Open questions / blockers
None.

### Next action
**T-013** (`IndexStore` interface + SQLite implementation) — lowest-numbered
unblocked TODO.

---

## Session 15 — 2026-09-16 — JDK stdlib root + `src.zip` pairing (T-012)
**Agent:** Muse Spark (via opencode) · **Branch:** none (on top of T-011 HEAD) ·
**Commits:** claim `cdc7490` + one feat commit (this entry included)

### Goal
T-012 — the lowest-numbered unblocked TODO (12 < 54). Close the JDK-root milestone
item: `jrt-fs` + `src.zip` pairing, zero-config reads, module-as-artifact.

### What I did
1. Audited T-012's three acceptance boxes against the tree: zero-config
   `show`/`members` on `java.util.HashMap` and the `java.base` module label already
   worked and were pinned by T-011 tests; `doctor` already WARNed on a missing
   `src.zip`. The real gap was `src.zip` *discovery*: `JrtArtifact` and
   `DoctorService` each hardcoded `javaHome/lib/src.zip`, ignored `$JAVA_HOME`,
   and could disagree with each other.
2. **New `index/.../artifact/JdkLayout.kt`:** `findSrcZip(javaHome, envJavaHome)`
   (tries `java.home` then `$JAVA_HOME`, first file wins, never throws) plus
   `envJavaHome(getenv)` (`$JAVA_HOME` reader, injectable, never throws).
3. **Wired it in:** `JrtArtifact(javaHome, envJavaHome)` computes `jdkSources`
   through the finder; `ArtifactLoader.openJdk` defaults the env home from the
   live `$JAVA_HOME`; `DoctorEnvironment` gained `javaHomeEnv` (defaulted, so
   existing callers compile) and `system()` fills it; `jdkSourcesCheck` uses the
   same finder and its absent-WARN names every location searched.
4. **Tests:** 8 tier-1 `JdkLayoutTest` (fabricated homes: found/preferred/
   fallback/dir-named-src.zip/missing-homes/env parsing + one 500-case property:
   null-or-real-file, java-home-first, never throws, ~0.2 s) + 3 tier-2
   `ArtifactLoaderTest` pairing tests + 3 `DoctorServiceTest` tests (env-fallback
   OK, java-home precedence, absent WARN) + 1 tier-2 test pinning
   `show --json` `provenance[0].artifact == "java.base"`. One red along the way,
   working as designed: my new test read `provenance` under `result` — the
   envelope carries it top-level (fixed the test, not the code).
5. Twice deleted a neighbouring test's `@Test` header with a bad `edit` anchor
   (repaired immediately, caught by reading the file — now L-028).

### Decisions made
None at D-level. Judgement calls in code KDoc: only `java.home` and `$JAVA_HOME`
are candidates (no parent-dir guessing — a sibling JDK's `src.zip` would be a
false positive); ties go to `java.home`, matching the launcher's spirit (D-026).

### Tasks moved
- T-012: WIP → DONE (all three acceptance boxes ticked, verified below).

### Lessons distilled
**L-028** (an `edit` insertion anchor must appear in *both* `oldString` and
`newString`; `git diff` the hunk before running anything).

### What works now (and how to verify it yourself)
```bash
./gradlew test                        # tier 1 green (incl. JdkLayoutTest property)
./gradlew check                       # tiers 1+2 green
./gradlew soak                        # tier 3 green
./gradlew :app:installDist && env -u JAVA_HOME app/build/jdx show java.util.HashMap
env -u JAVA_HOME app/build/jdx doctor | sed -n '5p'   # jdk-sources WARN (this machine ships no src.zip)
env -u JAVA_HOME app/build/jdx members java.util.HashMap --limit 3  # source: java.base (jrt)
```

### What is broken / half-done
Nothing known in T-012 scope. `show`/`members` provenance still says
`source: java.base (jrt)` without a sources-available flag — bodies and source
slices that would use `jdkSources` land in M3 (T-020…T-025).

### Open questions / blockers
None. **Push needs owner go-ahead (D-012):** T-011 + T-012 work unpushed.

### Next action
**T-013** (`IndexStore` interface + SQLite implementation) — lowest-numbered
unblocked TODO by the board rule (13 < 54). T-054/T-055 (golden/property spine)
and T-062 (`--sort` orders) remain available as alternatives.

---

## Session 14 — 2026-09-16 — `jdx show`, `jdx outline`, `jdx members` (T-011)
**Agent:** Muse Spark (via opencode) · **Branch:** none (continued on top of the T-011
`WIP` working tree: claim `c82dde1` plus uncommitted `ClassCard`/`JdxService`/test work
from the previous session) · **Commits:** one feat commit (this entry included)

### Goal
Finish T-011, found `WIP` with the service layer (`JdxService`, `ClassCard`,
`ErrorResult.Generic`, pure filter/name-matching tests) written but the Clikt commands,
goldens and wiring missing. Lowest-numbered unblocked TODO.

### What I did
1. Verified the inherited tree compiles and `./gradlew test` is green before touching
   anything; confirmed the T-011 acceptance list against the tree (commands missing).
2. **Commands (thin, D-004):** `cli/.../commands/ShowCommand.kt` (`show <type>`),
   `ReadCommands.kt` (`MembersCommand` with every §7.1 flag, `OutlineCommand` as
   declared-only), `ReadCommandSupport.kt` (roots mapping, `--access`/`--kind`
   mapping, flag-combination validation, outcome printing + termination).
   `--with-doc` exits 3 naming T-025; `--sort name|declaring` exits 3 naming the new
   T-062; `--inherited` accepted explicitly (default on); `--declared` wins when both
   are passed (said in `--help`). Registered all three in `JdxCli`.
3. **Exit-code fix:** first cut threw Clikt `ProgramResult` — the real binary still
   exited 0 on every error. Root cause: clikt-core's default `exitProcess` hook is a
   no-op (`{ }`; the real one lives in the excluded mordant flavor). Commands now
   call `kotlin.system.exitProcess` like `DoctorCommand`, with the terminator
   `(Int) -> Nothing` injected so tests throw instead of dying (L-026).
4. **Real bug the tests caught (L-027):** `members --from java.lang.Object` returned
   *only* the `+ N from java.lang.Object` collapse line — the renderer collapsed
   exactly what was asked to expand. `JdxService` now passes
   `collapseObjectMembers = (from != Object)`; tier-2 test pins it.
5. **Tests:** 20 tier-1 command tests (injected query + exit: flags, both `--json`
   positions, text⊆JSON, exits 1/2/3); +1 tier-1 property (`narrowing filters never
   adds rows`, 500 cases); 15 tier-2 behavioural tests (HashMap zero-config,
   outline == `members --declared` byte-for-byte, `--from` expansion, `--limit 0`,
   exits 1/4/5, determinism, D-017 marker absence, corpus size pin); 3 tier-2 golden
   tests — 210 files (35 fixture classes × show/members/outline × text/JSON,
   hermetic: `--no-jdk`, fixed artifact label). Read every sampled golden before
   accepting (covariant collapse, `compareTo(E)` + Comparable warning, enum/Kotlin
   outlines, `argN` fallback, annotation defaults in JSON).
6. **Docs:** T-011 → DONE with implementation notes; new T-062 (`--sort` orders);
   Appendix B `show`/`outline` rows; `L-026-050.md` shard created (L-026, L-027);
   CURRENT STATE updated. `cli/build.gradle.kts` wires `jdx.fixturesDir` +
   `jdx.golden.update` (T-054 seed pattern).

### Decisions made
None at D-level. Judgement calls recorded in code/docs: `--declared` wins over
`--inherited` when both are passed; `--access public` means exactly public (the
public+protected default applies only when the flag is absent); annotation cards
print `extends java.lang.Object`, faithful to the T-008 model (cosmetic, noted).

### Tasks moved
- T-011: WIP → DONE (all five acceptance boxes ticked, verified below).
- T-062: added (TODO) — `--sort name|declaring` for members/outline.

### Lessons distilled
**L-026** (clikt-core's default `exitProcess` is a no-op — `ProgramResult` alone
exits 0; call `kotlin.system.exitProcess` explicitly, inject the terminator) and
**L-027** (`--from X` must defeat display rules hiding X; tests caught it, not
reading). New shard `docs/lessons/L-026-050.md` (23 free).

### What works now (and how to verify it yourself)
```bash
./gradlew test                       # tier 1 green
./gradlew check                      # tiers 1+2 green (439 tests, 0 failures)
./gradlew soak                       # tier 3 green
./gradlew :app:installDist && env -u JAVA_HOME app/build/jdx members java.util.HashMap --limit 8
env -u JAVA_HOME app/build/jdx show java.util.HashMap
env -u JAVA_HOME app/build/jdx members Map; echo $?            # 2, candidates
env -u JAVA_HOME app/build/jdx members java.util.NoSuchThing; echo $?  # 1
env -u JAVA_HOME app/build/jdx members java.util.HashMap --static --instance; echo $?  # 3
env -u JAVA_HOME app/build/jdx members java.util.HashMap --no-jdk; echo $?  # 4
env -u JAVA_HOME app/build/jdx members java.util.HashMap --from java.lang.Object --limit 5
```

### What is broken / half-done
Nothing known in T-011 scope. Known deferred paths, each labelled at runtime:
`--with-doc` → T-025, `--sort name|declaring` → T-062, Maven coords → T-019,
named workspaces → T-015. `version`/`doctor` still use the M0 envelope until
migrated (compatible, D-028).

### Open questions / blockers
None. **Push needs owner go-ahead (D-012):** T-011 work (this session + the
inherited `ClassCard`/`JdxService` files) is unpushed.

### Next action
**T-012 (JDK stdlib root + `src.zip`)** — lowest unblocked TODO; the `JrtArtifact`
already exists, so this is mostly pairing + `--jdk` surfacing. T-054/T-055 remain
available as infra alternatives.

---

## Session 13 — 2026-09-16 — Text and JSON renderers over one result model (T-010)

## Session 13 — 2026-09-16 — Text and JSON renderers over one result model (T-010)
**Agent:** Muse Spark (via opencode) · **Branch:** `t010-renderers` ·
**Commits:** claim `81359c9` + one feat commit (this entry included)

### Goal
T-010: the shared text+JSON renderers T-011's `show`/`outline`/`members` will
consume. Lowest-numbered unblocked TODO (10 < 54). Plus prerequisite hygiene the
previous session left undone: session 12 never merged `t009-member-resolver`
into `main` (local `main` was 2 commits behind its own branch).

### What I did
1. Merged `t009-member-resolver` into `main` (fast-forward, clean tree;
   verified `main` was the ancestor first), deleted the branch, cut
   `t010-renderers`, claimed T-010 (`TODO`→`WIP` commit before coding).
2. **Test-first, RED:** 5 core test files against non-existent `core/.../render/`
   (`Unresolved reference`, the right reason): `TruncationTest`,
   `SignatureLineTest`, `MemberListingTest` (exact-text layout pins),
   `JsonEscapeTest`, `ErrorResultTest`, `MemberListingPropertyTest`
   (5 properties × 500–1000 generated graphs reusing T-009's `arbClassGraph`).
3. **GREEN:** `core/.../render/` — `JsonEscape` (hand-rolled quoting, D-028),
   `Truncation` (whole-entity cut, `shown`/`total`/`hint`), `SignatureLines`
   (FQN `$`-joined one-liners: generics, varargs, `argN` fallback, throws,
   defaults, const values), `Listing` (`MemberListing` grouped by declaring
   type in linearisation order, kind-then-name sort, pinned Object summary,
   `declaredOnly` outline mode, `:return` refs only for bridge siblings,
   `UNRESOLVED_SUPERTYPE` warnings), `Envelope` (version-1 success envelope),
   `Errors` (exit-1 not-found/did-you-mean, exit-2 ambiguous+candidates, both
   renderers), `Ansi` (color flag, bold headers; TTY detection is adapters').
   Plus `WarningCode.UNRESOLVED_SUPERTYPE` (closed-set test + PROPOSAL §16
   updated per the `Warning` KDoc rule).
4. **Goldens (tier 2):** `index/.../render/RendererGoldenTest` over all 35
   fixture classes × text+JSON = 70 files under
   `index/src/test/resources/golden/members/` (orphan check included; rewrite
   via `-Pgolden.update=true`, wired in `index/build.gradle.kts` as T-054's
   seed). Hermetic by construction (D-028 §7): fixed artifact label, empty
   `Object` stub, no JRT. Read every golden before accepting — real signal
   found on sight: `Generics$Recursive#compareTo(E)` stays unsubstituted with
   a labelled warning where `Comparable` is absent (honest degradation).
5. **Outside JSON check:** `cli/.../render/RendererJsonValidityTest` (tier 1)
   parses renderer output with kotlinx.serialization — hostile strings
   (quotes, backslash, newline, λ) round-trip.
6. One red along the way, working as designed: `ModelTest`'s closed-set pin
   failed on the new warning code; updated the set + PROPOSAL §16 (public-API
   procedure, not a test to weaken).

### Decisions made
**D-028** (renderer placement, FQN-over-simple-names reading of principle 3,
per-group canonicality, Object-collapse/truncation rules, uniform error
envelope, `UNRESOLVED_SUPERTYPE`, golden hermeticity rule). Judgement calls in
code KDoc: `argN` is the labelled fallback (proposal's ladder); empty lists
and `deprecated:false` omitted from JSON; `members` default cap 50.

### Tasks moved
- T-010: WIP → DONE (all five acceptance boxes ticked, verified below).

### Lessons distilled
**L-025** (`git diff` every build-file edit before running the build — an
`edit` meant to append replaced the fixtures block; diff review caught it).
Note: `docs/lessons/L-001-025.md` is now full (25/25) — next lesson-adding
session creates `L-026-050.md` per D-023.

### What works now (and how to verify it yourself)
```bash
./gradlew test            # tier 1: 314 tests, ~4 s, inside the 30 s budget
./gradlew check           # tiers 1+2: green (60 tier-2, incl. the golden test)
./gradlew soak            # tier 3: green (rendering touched, TESTING.md §13)
./gradlew :core:test --tests "dev.jdx.core.render.*"       # 52 renderer tests
./gradlew :index:tier2Test --tests "dev.jdx.index.render.*" # goldens vs fixtures
./gradlew :cli:test --tests "dev.jdx.cli.render.*"         # real-parser JSON check
```
375 tests total (314 tier 1 + 60 tier 2 + 1 soak proof), 0 failures.

### What is broken / half-done
Nothing known in T-010 scope. Known limits, documented for the owning task:
`ClassCard` (show model) and `JdxService` + flags land in T-011; `version`/
`doctor` still use the M0 `cli` envelope until migrated (compatible, D-028);
golden helper is local to the index test until T-054 promotes it.

### Open questions / blockers
None. **Push needs owner go-ahead (D-012):** local `main` is now 5 commits
ahead of `origin/main` (T-009 feat + T-010 work) — nothing pushed this session.

### Next action
**T-011 (`jdx show`, `jdx outline`, `jdx members`)** — consumes `MemberListing`
directly; lowest unblocked TODO. T-054/T-055 remain available as infra
alternatives.

---

## Session 12 — 2026-09-16 — Member resolution with inheritance + generic substitution (T-009)
**Agent:** Muse Spark (via opencode) · **Branch:** `t009-member-resolver` ·
**Commits:** claim `edb3042` + docs-tooling `0ee304a` (main) + one feat commit (this entry included)

### Goal
T-009: the `--inherited` core (PROPOSAL.md §9.3) — linearise, substitute generics,
filter visibility, collapse overrides, drop synthetics. Lowest-numbered unblocked TODO
(T-009 = 9 < T-054). Plus an owner-directed one-liner: state the tool-call batching rule
in `CLAUDE.md` (done as `0ee304a` on `main` before branching).

### What I did
1. Owner doc tweak: `CLAUDE.md` §7 gained "Batch independent tool calls in a single
   turn" (plan reads/writes, issue together; sequence only on real dependencies).
2. Claimed T-009 (`chore: claim T-009` on new branch `t009-member-resolver`).
3. **Test-first, RED:** `MemberResolverTest` (16 examples) + `MemberResolverPropertyTest`
   (6 properties × 1,000 generated graphs) + `ResolveGenerators`/`ResolutionTestFixtures`
   helpers — failed with `Unresolved reference 'MemberResolver'`, the right reason.
4. **GREEN:** `core/.../resolve/MemberResolver.kt` — BFS linearisation (superclass then
   interfaces, first-visit-wins, `Object` moved last), transitive type-variable
   environments (raw edges erase to `Object`, method type params shadow class ones,
   wildcards approximate to bound/`Object`, documented), override collapse by
   name+erased-descriptor (fields by name, hidden types recorded), JLS visibility from
   the target's package, synthetic/bridge filtering, ctors never inherited, `<clinit>`
   never listed, missing supertypes skipped and reported sorted.
5. **Enabling fix the tests caught:** `StringList`-shaped signatures
   (`Lfoo/Bar<String>;`, no interfaces/params) parsed as `FieldSignature`, so
   `AsmClassReader`'s `as? ClassSignature` silently dropped every such edge (real
   bytecode would have resolved `add(Object)`). Added `GenericSignature.parseClass`
   (test-first, 7 new invocations incl. pinning `parse`'s field-first behaviour) and
   pointed the reader at it + 1 tier-1 reader test. Logged as **L-024**.
6. **Tier-2 real-JDK spot-check** (`index/.../resolve/MemberResolverJrtTest`, `@Tag("tier2")`,
   needs only the running JDK, no corpus): `HashMap` Map-API name set (the IntelliJ
   completion check), determinism + empty `missingSupertypes` + `Object`-last, no
   private-supertype leak, and a synthetic `StringMap extends HashMap<String,Integer>`
   proving `put(String,Integer)` substitution against real supertypes.

### Decisions made
None at D-level (all within T-009's brief). Judgement calls in code KDoc: wildcard
edges approximate (no `TypeSignature` form for use-site wildcards); `Array` throws
mappings keep the declared name; resolvers return declaration order and leave
grouping/sorting to T-010 renderers; `missingSupertypes` sorted for determinism.

### Tasks moved
- T-009: WIP → DONE (all six acceptance boxes ticked, verified below).

### Lessons distilled
**L-024** (lone-superclass `Signature` parses as a field — `as?` silent-drop; use
`parseClass` for class-file contexts).

### What works now (and how to verify it yourself)
```bash
./gradlew test            # tier 1: 259 tests, ~7 s, inside the 30 s budget
./gradlew check           # tiers 1+2: green (59 tier-2, incl. 4 JRT resolver tests)
./gradlew soak            # tier 3: green (proof test; corpus harness is T-059)
./gradlew :core:test --tests "dev.jdx.core.resolve.*"      # just this task's properties
./gradlew :index:tier2Test --tests "dev.jdx.index.resolve.*" # just the JRT spot-check
```
319 tests total (259 tier 1 + 59 tier 2 + 1 soak proof), 0 failures.

### What is broken / half-done
Nothing known in T-009 scope. Known limits, documented in code for the task that owns
them: Kotlin JVM→Kotlin-declaration mapping is T-036's (resolver returns JVM members);
grouping/sorting/rendering is T-010's; `overriddenTypes`/`hiddenTypes` are recorded but
no renderer prints them yet (same task).

### Open questions / blockers
None.

### Next action
**T-010 (text and JSON renderers)** — consumes `ResolvedMembers` directly; lowest
unblocked TODO (10 < 54). T-054/T-055 remain available as infra alternatives.

---

## Session 11 — 2026-09-15 — ASM class reader into ClassInfo/MemberInfo (T-008)
**Agent:** Muse Spark (via opencode) · **Branch:** `t008-asm-reader` (from T-007 HEAD) ·
**Commits:** claim `9dc94e4` + one feat commit (this entry included)

### Goal
T-008: read class-file bytes with ASM into `core`'s `ClassInfo`/`MemberInfo` — the bytecode
half of the truth model (D-009). Lowest-numbered unblocked TODO per the board rules
(T-008 = 8 < T-054); builds directly on T-007's `openClass` bytes.

### What I did
1. Claimed T-008 (`chore: claim T-008` on new branch `t008-asm-reader`).
2. **Core model, test-first** (`MemberDefaultsTest`: RED `Unresolved reference
   'deprecated'`, then GREEN): three defaulted, backward-compatible slots the reader
   needs — `ClassInfo.deprecated`, `MethodInfo.annotationDefault` (rendered
   `AnnotationDefault`), `FieldInfo.constantValue` (rendered `ConstantValue`).
3. **New `index/.../asm/` package** (1 main file, KDoc'd):
   - `AsmClassReader` — sealed `ClassReadResult` (`Ok`/`UnsupportedVersion`/`Corrupt`;
     errors are values, never throws, per CONTRIBUTING.md). Major-version pre-check
     against `Runtime.version().feature() + 44`, plus a catch for majors ASM itself
     rejects (ASM lags the running JDK — same graceful path, never a throw).
   - `SKIP_FRAMES` (never `SKIP_DEBUG` — param names live there). Kind from flag bits
     (`ANNOTATION` > `ENUM` > `RECORD` > `INTERFACE` > `CLASS`; Kotlin
     `OBJECT`/`COMPANION` stay T-035's job). Interfaces and `java.lang.Object`
     normalise `superclass` to `null` (per `ClassInfo` KDoc). Outer class from the
     `InnerClasses` entry first, `outerClass` second. Malformed generic signatures
     degrade to `null`, never fail the class. Raw access masks kept (incl.
     `ACC_SUPER` — renderers must not print it; said in code).
   - Param names: `MethodParameters` when sizes match, else an LVT slot walk
     (`this` at 0 for instance methods, long/double take two slots), else `null`s
     (renderer synthesises `argN`, T-010).
   - Deterministic value rendering: `"s"`, `'c'`, `Fqn.class`, `E.CONST`, `{1, 2}`,
     `@Fqn(k=v)`; unknown shapes are corrupt input, never guessed (no `toString()`
     on unknown objects — identity hashcodes would break determinism).
4. **Tests:** 14 tier-1 (`AsmClassReaderTest`, classes built in memory via the new
   `AsmTestClasses` builder — no disk, no subprocesses) + 3 core tier-1
   (`MemberDefaultsTest`) + 7 tier-2 (`AsmClassReaderDifferentialTest`): the
   `javap -p -s` differential over **every** fixture class (member+descriptor sets
   equal exactly, per-member mismatch report), whole-jar read via `ArtifactLoader`,
   corrupt-neighbour isolation (every 7th entry truncated), the D-017 marker proof
   through this reader, a JRT smoke (`Object`/`HashMap` — major-70 JDK-26 bytes
   parse, so ASM 9.10.1 covers the running JDK), and flag spot-checks
   (`synchronized`/`native`/varargs/deprecated/`serialVersionUID = "1"`,
   `Matrix` defaults, `Nesting$Inner` outer, `NoDebug` all-`null` names).
5. One compile error, now a lesson: kotest 6 `shouldBeInstanceOf<T>()` takes an
   assertion lambda, not a failure message (L-023).

### Decisions made
None at D-level (all within T-008's brief). Two judgement calls recorded in code:
explicit `--sources`-style override ordering is T-007's; here, a class-level
`@Deprecated` annotation and the `Deprecated` attribute both set `deprecated`, and
per-annotation mapping failures corrupt the class rather than silently dropping —
annotations name real types, so a malformed one means malformed bytes.

### Tasks moved
- T-008: WIP → DONE (all four acceptance boxes ticked, verified below).

### Lessons distilled
**L-023** (`shouldBeInstanceOf` takes a lambda, not a message; it already smart-casts).

### What works now (and how to verify it yourself)
```bash
./gradlew test            # tier 1: 229 tests, ~8 s, inside the 30 s budget
./gradlew check           # tiers 1+2: green — +7 differential/fault/D-017 tests
./gradlew :index:test :index:tier2Test   # just this task's suites
```
285 tests total (229 tier 1 + 55 tier 2 + 1 soak proof), 0 failures. `check` is still
green on machines with no jar corpus for this task's scope: the only
cache-dependent read is the JRT smoke, which needs no corpus (every machine has its
own JDK); the `javap` differential skips cleanly via `assumeTrue` where `javap` is
absent.

### What is broken / half-done
Nothing known in T-008 scope. Known limits, documented in code for the task that owns
them: `Ok.warnings` is empty (kept for forward growth); field `ConstantValue`s and
annotation defaults are rendered strings — rich value modelling (enums as types,
nested constraints) arrives with M3 javadoc/KDoc work; reference-edge extraction
(`visitMethodInsn` etc.) is T-029's, not read here.

### Open questions / blockers
None.

### Next action
**T-009 (member resolution with inheritance and generic substitution)** — the
highest-value algorithm in the project; it consumes this task's `ClassInfo` graphs
directly. T-054/T-055 (golden/property infra) remain available as bedrock
alternatives.
