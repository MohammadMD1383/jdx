# Testing strategy

> **Short version:** TDD is mandatory for `core`, but TDD alone would leave this project
> badly under-tested. `jdx` reads files written by other people's compilers; its worst bugs
> are not logic errors in code we wrote, they are *"the real world contains a jar shaped like
> **that**"* errors. Tests written from imagination cannot find those. So we pair TDD with
> five families of test that generate their own inputs or borrow their expected answers from
> an external oracle.

---

## 1. Why not just TDD?

TDD's loop is: *write a failing test for the behaviour you want → make it pass → refactor.*
That works beautifully when **you** define correct behaviour.

Roughly 40 % of this project is like that — the symbol-ref grammar, member resolution,
generic substitution, truncation, rendering. For all of it, TDD is required (§3).

The other 60 % is different in kind:

| Area | Why TDD alone is weak | What we use instead |
|---|---|---|
| ASM class reading | Correct output is "whatever `javac`/`kotlinc` actually emitted" — you cannot invent it | **Differential testing vs `javap`** (§5) |
| Decompilation | Output is whatever Vineflower produces; asserting exact text is brittle | **Property + compile-back testing** (§5.3) |
| Jar/zip handling | Bugs come from malformed input nobody thought to write a test for | **Fault injection + corpus soak** (§7, §8) |
| Index & queries | Bugs appear at 20,000 classes, not at 3 | **Metamorphic + corpus testing** (§6, §8) |
| Four front-ends | Drift between them is invisible to per-front-end tests | **Parity testing** (§9) |
| "Do my tests have teeth?" | Line coverage says yes while asserting nothing | **Mutation testing** (§10) |

The strategy below is the answer to *"more tests guarantee fewer bugs"* — not more
hand-written examples, which plateau fast, but **test families that keep generating new cases
without anyone writing them.**

---

## 2. The ten families at a glance

| # | Family | Catches | Where | Tier |
|---|---|---|---|---|
| 1 | **Unit (TDD)** | logic errors in our own rules | `core` (mandatory), all modules | 1 |
| 2 | **Property-based** | edge cases nobody imagined | parsers, resolvers, truncation | 1 |
| 3 | **Differential / oracle** | wrong bytecode interpretation | `index` ASM reader | 2 |
| 4 | **Golden / approval** | accidental output changes | all renderers, all commands | 1 |
| 5 | **Metamorphic** | inconsistency between related answers | resolver, index, graph queries | 2 |
| 6 | **Fault injection** | crashes on malformed input | artifacts, sources, decompiler | 2 |
| 7 | **Corpus soak** | real-world shapes we never imagined | everything, over ~2,183 local jars | 3 |
| 8 | **Parity / contract** | front-end drift, text↔JSON gaps | `cli`/`mcp`/`server` | 2 |
| 9 | **Performance regression** | silent slowdowns | indexer, queries | 4 |
| 10 | **Mutation** | tests that assert nothing | `core` gated, others measured | 4 |

**Tiers** (so the fast loop stays fast):

| Tier | Command | Budget | When |
|---|---|---|---|
| 1 | `./gradlew test` | **< 30 s** | every save; the TDD loop |
| 2 | `./gradlew check` | **< 3 min** | before every commit |
| 3 | `./gradlew soak` | minutes | before finishing a task; needs local jars |
| 4 | `./gradlew bench mutationTest` | long | on demand / before a milestone closes |

A tier-1 suite that creeps past 30 s kills TDD, and then it kills testing. Guard it: anything
touching disk, network, or a real jar belongs in tier 2 or above.

---

## 3. TDD — where it is mandatory

**`core` is developed test-first. No exceptions.** `core` has no dependencies and no IO
(D-001, CONTRIBUTING.md), which means there is never an excuse for "hard to test".

The loop, concretely, for T-003 (symbol-ref parser):

```kotlin
// RED — write this first, watch it fail for the right reason
@Test fun `nested type accepts dollar and dot forms identically`() {
    assertEquals(parseRef("java.util.Map\$Entry"), parseRef("java.util.Map.Entry"))
}

// GREEN — the smallest change that passes. Resist generalising here.

// REFACTOR — now make it clean, with the test holding the behaviour still.
```

Rules that make this work rather than become ceremony:

- **One behaviour per test.** If the name needs "and", it is two tests.
- **Test names are sentences**, in backticks:
  `` fun `inherited members from a generic supertype are substituted`() ``.
  The test report should read like a specification of what `jdx` does.
- **Watch the test fail first**, and check the failure message is the one you expect. A test
  that was green before you wrote the code is testing nothing.
- **Assert on values, not on calls.** No mocking framework in `core`. If you need a mock, the
  design is wrong — push the IO outward.
- **Every bug fix starts with a failing regression test** that reproduces the report. Name it
  after the issue: `` fun `regression #42 - bridge method shadows its own override`() ``.

**Outside `core`,** test-first is encouraged but not required, because you often cannot know
the expected value until you have looked at what ASM or Vineflower actually returns. There
the honest workflow is: spike → look → **write the differential or golden test that pins it**
→ delete the spike. What is *not* acceptable is landing untested code and promising tests
later.

---

## 4. Property-based testing (§family 2)

Hand-written examples stop finding bugs once they cover what you already thought of.
Properties keep generating inputs forever.

Library: **kotest-property** (or jqwik). Minimum 1,000 cases per property in tier 1; seeds
printed on failure and **pinned as a regression test** when one fails.

Properties we owe the project:

```kotlin
// Symbol refs (T-003)
"parse(print(ref)) == ref"                              // round-trip is a fixed point
"print(parse(s)) is stable"                             // idempotent normalisation
"a malformed ref never throws"                          // returns a positioned error

// Descriptors and signatures (T-002)
"descriptor -> parsed -> printed == descriptor"         // for generated nested generics,
                                                        // wildcards, arrays, type vars
// Member resolution (T-009)
"resolve(type) is deterministic across runs"
"resolve(type) contains every member of declared(type)"
"resolve(type) never contains a private member of a strict supertype"
"resolve of a cyclic hierarchy terminates"              // synthesised cyclic ClassInfo

// Truncation (T-047)
"truncated output never splits an entity"
"shown <= total, and hint is present iff shown < total"
```

Generators live in `core/src/test/kotlin/.../gen/` and are shared. Writing a good
`Arb<TypeName>` once pays for itself across every later property.

---

## 5. Differential / oracle testing (§family 3)

**This is the single most valuable family in the project**, because it gives us correct
answers we did not have to write down.

### 5.1 `javap` as the oracle for bytecode reading
For **every** class in the fixture corpus *and* every class in a sampled slice of the local
jar corpus:

```
javap -p -s -c <class>   ──►  parse  ──►  { member names + descriptors + flags }
jdx  members --declared --access all --include-synthetic --json  ──►  same set
assert the two sets are EQUAL
```

Any disagreement is a bug in `jdx` (or, rarely, a `javap` quirk worth documenting). This one
test family covers a huge surface — access flags, synthetics, bridges, varargs, nested
classes, enum internals, records — **without anyone enumerating those cases.**

### 5.2 `javac`/`kotlinc` as the oracle for source-derived facts
The fixture corpus is compiled from source we control, so for every fixture we independently
*know* the truth. Assert `jdx`'s answer against a machine-readable expectation file generated
from the fixture's own annotations:

```java
@ExpectedMembers({"public int add(int,int)", "private static final long serialVersionUID"})
public class Arithmetic { … }
```

The expectation travels with the fixture, so adding a fixture adds a test automatically.

### 5.3 Compile-back testing for decompilation
Do **not** assert decompiler output text — it changes with every Vineflower release and the
test becomes noise. Assert the property that actually matters:

```
decompile(class) ──► javac ──► class'          (must compile)
members(class) == members(class')              (same API surface)
```
A decompilation that recompiles to the same API is a decompilation an agent can trust. When
it fails, that is real information (`⚠ reconstructed` already warns the user; this test tells
*us* how often it matters).

### 5.4 The JDK as a free oracle
`java.base` ships ~6,000 classes plus `src.zip`. It is a large, stable, sources-paired
corpus that every contributor has. Use it: `jdx members java.util.HashMap --inherited`
compared against the JDK's own source declarations is a strong end-to-end check that needs no
downloads.

---

## 6. Metamorphic testing (§family 5)

When you cannot state the right answer, you can often state a **relation between answers**
that must hold. These catch deep index and resolver bugs that example tests never reach.

```
members(T, --inherited) ⊇ members(T, --declared)
members(T, --inherited) ⊇ members(super(T), --inherited) minus private/overridden
hierarchy(T, --down) contains S  ⟺  hierarchy(S, --up) contains T
usages(X) — every hit, when re-read, genuinely references X
callers(M) contains C  ⟺  calls(C) contains M
search(exact-fqn-of(T)) returns exactly T
show(T) member counts == |members(T, --declared, --access all)|
body(M) parsed back has signature == signature(M)
index(jar) twice ⟹ byte-identical index rows      (determinism)
run(cmd) twice ⟹ byte-identical stdout            (D-007 determinism promise)
```

Run these over the fixture corpus in tier 2, and over a random sample of the local jar corpus
in tier 3. They are cheap to write and they are the tests most likely to find a real bug six
months from now.

---

## 7. Fault injection (§family 6)

`jdx`'s promise is *degrade, don't fail* (CLAUDE.md §2.8). That promise needs adversarial
tests, generated programmatically rather than collected by hand:

- Truncated class file (cut at every 10 % boundary)
- Class-file major version from the future
- Zip entry with `../` traversal (**zip-slip**) — must be rejected, D-017
- Zip bomb: huge declared uncompressed size — must hit the cap, not OOM
- Empty jar; jar with only resources; jar with a directory named `X.class`
- Sources jar that does not match its binary (member added, removed, renamed) — must emit
  `SOURCES_VERSION_MISMATCH`, not silently disagree
- Sources jar for a *different* artifact entirely
- Class compiled `-g:none` (no debug info ⟹ no parameter names) — must fall back, and **say**
  the names are synthesised
- Multi-release jar with conflicting versions
- Duplicate FQN across two jars (shading) — must warn, D-016
- Unreadable file / permission denied / file deleted mid-read
- Decompiler timeout and decompiler crash — must fall back to `javap`, not propagate

**Every one of these asserts two things:** the process exits with a documented code (D-015),
and the output names the problem. A stack trace reaching an agent is a test failure.

---

## 8. Corpus soak testing (§family 7)

The highest-value-per-line test in the project, and the one most teams skip.

The owner's machine has **~2,183 real jars** in `~/.gradle/caches`, including sources jars and
a large obfuscated `minecraft-client.jar`. That is a free adversarial corpus written by
dozens of different compilers and build tools.

```
for each jar in the corpus:
    index it
    for a random sample of classes in it:
        run show / outline / members / signature / doc / body / source
        assert:
          - exit code ∈ {0,1,2}                       (never 5 or 6)
          - no exception reached stdout/stderr
          - --json output parses and validates against the envelope schema
          - text and JSON carry the same entity set   (D-007)
          - running it twice produces identical bytes (determinism)
    record every warning code emitted, with a count
```

Notes:
- **Tagged, off by default** (`./gradlew soak`) — it depends on artifacts not every
  contributor has, and it takes minutes. `./gradlew check` must never require it.
- **It asserts invariants, not values.** It cannot know the right answer for 2,183 jars; it
  can know that `jdx` must never crash, never emit invalid JSON, and never be
  non-deterministic.
- **The warning histogram is a deliverable.** "247 jars emitted `MULTI_RELEASE_VARIANT`" is
  how we learn which real-world shapes matter. Record it in the progress log.
- Point it at a directory via `-Pcorpus=<dir>` so contributors without the owner's cache can
  run it against their own `~/.m2` or `~/.gradle`.

---

## 9. Parity / contract testing (§family 8)

Four front-ends (D-004) will drift unless a test forbids it.

- **Adapter parity:** the same query through CLI `--json`, HTTP, and MCP must produce
  **byte-identical result payloads**. One test, parameterised over every command.
- **Text↔JSON completeness:** every entity and warning in the text rendering must appear in
  the JSON. Enforced structurally, not by eyeball (D-007).
- **Exit-code contract:** a table-driven test asserting the D-015 code for each documented
  situation. Changing a code must break a test — that is the point.
- **`--help` coverage:** every flag documented in `docs/PROPOSAL.md` Appendix B exists, and
  every implemented flag is documented. Drift in either direction fails.
- **Envelope schema:** the JSON envelope is validated against a checked-in JSON Schema, so
  `"jdx": 1` genuinely means something.

---

## 10. Mutation testing (§family 10)

The direct answer to *"don't stand with a few test cases."* Line coverage measures which code
ran; **mutation score measures whether the tests would notice if that code were wrong.**
Pitest mutates the bytecode (flips conditionals, removes calls, changes return values) and
reports how many mutants your tests killed.

Targets:

| Module | Line coverage | Mutation score |
|---|---|---|
| `core` | ≥ 95 % | **≥ 80 %** (gate) |
| `index`, `sources`, `decompile` | ≥ 85 % | ≥ 65 % (measured, reported) |
| `cli`, `mcp`, `server` | no gate — thin by rule, covered by parity + golden tests | — |

Run in tier 4. A surviving mutant in `core` is a genuine gap: either write the test that
kills it, or delete the unreachable code.

---

## 11. The fixture corpus (T-006)

Every family above leans on this, so it is built early and deliberately. Small, nasty Java
and Kotlin classes compiled by Gradle at test time into **both** a binary jar and a sources
jar.

Must include: bounded/wildcard/recursive generics · bridge methods from covariant overrides ·
inner, static-nested, anonymous and local classes · records · sealed hierarchies · enums with
constant bodies · repeatable and array-valued annotations · varargs · `synchronized`/`native`
/`strictfp` · `@Deprecated(forRemoval=true)` · package-private and private members · a class
compiled `-g:none` · a class with a static initialiser that writes a marker file (to prove
D-017: it must **never** appear) · Kotlin: `suspend`, extension functions, default arguments,
`data`/`sealed`/`value`/`object`/companion, `@JvmName`, `@JvmStatic`, nullable types,
properties with custom accessors, typealiases, inline/reified.

Requirements:
- **Deterministic builds** — same bytes on repeat runs, or golden tests become flaky.
- **Self-describing** — fixtures carry `@ExpectedMembers`-style annotations (§5.2) so adding a
  fixture adds test coverage automatically.
- **A `Fixtures` helper** resolves jar paths with no hard-coded absolute paths.

### 11.1 Adding a fixture

1. Add the source under `testfixtures/src/main/java` (or `src/main/kotlin`). The odd one out
   is `src/nodebug/java`: classes there compile with `-g:none` (see
   `testfixtures/build.gradle.kts`), so only a fixture that must lack debug info goes there.
2. Annotate **every** source-declared type with `@ExpectedMembers` — one entry per declared
   field, method and constructor, exactly as `javap -p` prints the member line (modifiers plus
   fully-qualified signature, no trailing `;`). List synthetic members (`this$0`, bridges,
   `$default` stubs) like any other; never list `static {}`. A type declaring nothing carries
   an explicit empty list. Anonymous/local classes, enum constant bodies and the Kotlin file
   facade cannot carry the annotation — they live in `FixtureCorpusTest`'s pinned exempt set
   instead, which you must extend consciously.
3. Run `./gradlew :testfixtures:jar` and copy the member lines from
   `javap -p -classpath testfixtures/build/libs/testfixtures-<version>.jar <YourClass>` into
   the annotation — never hand-write them from memory. The corpus test diffs annotation
   against `javap` member-for-member and fails on any deviation, including order.
4. If the fixture must never execute (a static-initialiser probe like `StaticInitMarker`),
   say so in its KDoc, and never reference it from test code — read bytes via
   `Fixtures.classBytes(...)`, never reflection (D-017).
5. Rebuild twice and compare sha256 of both jars; identical bytes are the acceptance bar.

---

## 12. What we deliberately do **not** test

Testing time is finite; spend it where bugs live.

- **Third-party libraries.** We do not test that ASM parses class files or that SQLite
  commits transactions. We test *our use* of them.
- **Exact decompiler output text.** §5.3 explains the alternative.
- **Rendering cosmetics** beyond what golden files already pin. Do not write assertions on
  individual spaces.
- **Getters, `data class` members, trivial delegation.** Mutation testing will flag these as
  uncovered; that is acceptable and expected.
- **Gradle's own behaviour.**

---

## 13. Running the tests

```bash
./gradlew test                       # tier 1 — fast, the TDD loop (<30s)
./gradlew check                      # tier 2 — + property, golden, fault injection (<3min)
./gradlew test -Pgolden.update=true  # rewrite golden files — THEN READ THE DIFF
./gradlew soak                       # tier 3 — corpus, needs local jars
./gradlew soak -Pcorpus=~/.m2        # ...against your own corpus
./gradlew bench                      # tier 4 — performance budgets (PROPOSAL §15)
./gradlew mutationTest               # tier 4 — mutation score
./gradlew testReport                 # aggregated HTML report
```

**Definition of done for any task:** tier 1 and tier 2 green; tier 3 green if you touched
artifact reading, indexing, or rendering; new behaviour covered by at least one family from
§2 that *generates* cases (2, 3, 5, 6, 7) rather than only hand-written examples.

---

## 14. A note on updating golden files

`-Pgolden.update=true` is the most dangerous command in this repo. It makes every failing
output test pass, instantly, whether or not the new output is correct.

**Always read the resulting diff before committing it.** A golden file you updated without
reading is a test you deleted. If the diff is large, that is a signal to check whether you
changed behaviour you did not intend to change — not a signal to skim faster.
