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

## Rules

- Every fixture type carries `@ExpectedMembers` with its **`javap -p` truth** —
  adding a fixture adds coverage automatically. Copy member lines from `javap -p`
  output, never from memory; rebuild twice and check the jars are byte-identical.
  Full rules: `docs/TESTING.md` §11.1.
- `java-test-fixtures` output must **not** land in `build/libs`: point
  `testFixturesJar.destinationDirectory` at `build/test-fixtures-libs`, or every
  "exactly one binary fixture jar" assertion sees two jars and fails.
- Java `-Werror` is on for all `JavaCompile`: the deliberately-`strictfp` fixture
  carries `@SuppressWarnings("strictfp")` at the declaration (don't "fix" it).
