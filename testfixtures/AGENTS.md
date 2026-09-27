# AGENTS.md — `testfixtures/`

Deliberately nasty Java + Kotlin classes, compiled by Gradle into a binary jar plus
a `-sources.jar`. Nine of ten test families lean on this — it is load-bearing test
infrastructure, not sample code.

## Contents

`Generics` (bridges, substitution traps) · `Nesting` · records / sealed / enums /
annotations (`Annos` incl. same-file siblings `Matrix`/`Tag` and annotation
elements) · `VarargsAndModifiers` (`synchronized`/`native`/`strictfp`) ·
`-g:none` class · `StaticInitMarker` · every Kotlin shape whose JVM projection
misleads (`KotlinShapes`: suspend, properties, default args, `@JvmName`, objects,
companions, facades, data/value classes).

**The diff pair** (`src/diffv1/`, `src/diffv2/`, `src/diffabsent/`) is a *second*,
separate corpus for `jdx diff` (issue #23, `docs/TESTING.md` §11.2): two versions of
the **same** package `dev.jdx.diffapi`, built into `build/diff-fixtures/`. Read those
rules below before touching either.

## The diff pair — its own rules

- **Same package on both sides.** The two versions must share binary names, or every
  type reads as `TYPE_REMOVED` + `TYPE_ADDED` and the intra-type rules never fire. The
  version difference is the *package* being the same, not a version suffix.
- **Its own directory, its own base name.** `build/diff-fixtures/` and
  `diff-fixtures-v1`/`-v2`, never `build/libs` and never a `testfixtures-` name —
  `FixtureJars.singleJar`, core's `Fixtures.singleJar` and `ArtifactTestJars.binaryJar`
  all `require(size == 1)` over that directory. Resolved by `DiffFixtureJars`.
- **No `@ExpectedMembers`, and that is deliberate.** An annotation describes one type;
  the property these fixtures exist to prove is a property of the *pair* — that every
  rule in `core/diff/CompatRule.kt` fires against real `javac` output. The
  self-describing mechanism is `DiffFixtureRuleCoverageTest` in `:index`: add a rule
  there with the fixture that triggers it, and a rule with no fixture fails the build.
- **`src/diffabsent/` is compiled but never packaged.** `javac` will not compile a class
  whose supertype is missing, so the "supertype in neither artifact" fixture has to
  exist to compile and then be kept out of both jars — which is exactly the situation
  the differ's `inheritance not checked` path exists for.
- **One type must stay identical** (`Stable.java`, byte-for-byte in both trees): a
  finding naming it is a false alarm, and a false alarm in an upgrade report is how an
  agent decides not to take an upgrade for nothing.
- **Rules javac cannot produce** are listed in `unreachableFromJavac` in that test with
  the reason — e.g. `VARARGS_CHANGED`, because `f(String)` and `f(String...)` have
  different descriptors. Do not add one to the required list "to make it pass"; that
  would be claiming coverage the corpus does not have.

## Rules

- Every fixture type in `src/main` carries `@ExpectedMembers` with its **`javap -p`
  truth** — adding a fixture adds coverage automatically. Copy member lines from
  `javap -p` output, never from memory; rebuild twice and check the jars are
  byte-identical. Full rules: `docs/TESTING.md` §11.1.
- `java-test-fixtures` output must **not** land in `build/libs`: point
  `testFixturesJar.destinationDirectory` at `build/test-fixtures-libs`, or every
  "exactly one binary fixture jar" assertion sees two jars and fails.
- Java `-Werror` is on for all `JavaCompile`: the deliberately-`strictfp` fixture
  carries `@SuppressWarnings("strictfp")` at the declaration (don't "fix" it).
