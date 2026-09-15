# Session log — shard 2: sessions 11–20

Append-only session log, **10 sessions per shard** (D-023). `docs/PROGRESS.md` holds the
`CURRENT STATE` handoff, the shard index, and the entry template — this file only holds
entries. Newest entry first; never edit or delete a past entry — if one turned out to be
wrong, say so in a *new* entry.

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
