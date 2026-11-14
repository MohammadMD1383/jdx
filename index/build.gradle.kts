plugins {
    alias(libs.plugins.pitest)
}

kotlin {
    // T-052: same contract as :core — every public declaration needs an explicit
    // visibility and return type. Trial-compiled clean before gating.
    explicitApi()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sources"))
    implementation(project(":decompile"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.util)
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotest.property)
    // Golden suites compare against committed files via the shared T-054
    // helper, which lives in testfixtures' testFixtures source set — never in
    // `src/main`, where every class would pollute the fixture corpus scans.
    testImplementation(testFixtures(project(":testfixtures")))
}

// Fixture-corpus wiring for `test`/`integrationTest`/`benchTest` (including the
// diff-fixture pair) lives in the root `build.gradle.kts` projectsEvaluated hook
// (#82). Do not reintroduce a private copy — `verifyFixtureCorpusWiring` fails
// `check` if one reappears.

// Mutation testing (T-060, docs/TESTING.md §10, D-021). Measured and reported, NOT gated:
// `index` carries an 85 % line gate (root build) but no mutation threshold. Runs via
// `./gradlew mutationTest` (tier 4). Same JUnit-6 discovery caveat as `core` (see its
// build file); the T-060 session verified discovery empirically.
pitest {
    pitestVersion.set(libs.versions.pitest.get())
    junit5PluginVersion.set(libs.versions.pitestJunitPlugin.get())
    targetClasses.set(setOf("dev.jdx.index.*"))
    targetTests.set(setOf("dev.jdx.index.*"))
    // Fast mutant iteration — see :core for the `-PpitestScope` contract.
    (findProperty("pitestScope") as String?)?.let { scope ->
        targetClasses.set(setOf(scope))
        targetTests.set(setOf(scope))
    }
    // Tier-3 soak tests need a live corpus (`jdx.corpusDir`, `jdx.tier=soak`) and
    // minutes of runtime: they assert runner invariants, not product behaviour,
    // so no mutant is covered by them. Same rationale as :core's tiers exclusion.
    excludedTestClasses.set(
        setOf(
            "dev.jdx.index.index.ArtifactIndexerSoakTest",
            "dev.jdx.index.metamorphic.MetamorphicCorpusSoakTest",
            "dev.jdx.index.soak.CorpusSoakTest",
            "dev.jdx.index.differential.JavapCorpusSoakTest",
        ),
    )
    // The PIT minion is a fresh JVM: it does not inherit the `test` task's system
    // properties, and it does not build the corpus itself. Both corpus properties and
    // the four `:testfixtures` jar task dependencies are wired once for every PIT
    // module in the root build (#66) — do not repeat them here; `verifyPitestWiring`
    // fails the build if a module loses them.
    // #57: same CI relaxation as :core — slow shared runners must not read as
    // killed mutants. Local runs keep the 10 s gate.
    val isCi: Boolean =
        (findProperty("ci")?.toString()?.let { it != "false" } ?: false) ||
            System.getenv("CI") != null ||
            System.getenv("GITHUB_ACTIONS") == "true"
    timeoutConstInMillis.set(if (isCi) 60_000 else 10_000)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    // #81: `threads` is owned by the root `projectsEvaluated` hook
    // (`pitestThreadCount`) — index is subprocess-heavy (`javap` per class), so
    // it caps at 4 unless `-PpitestThreads=<n>` overrides. Do not set threads
    // here; a private copy would drift from the gate.
    // Same Intrinsics rationale as :core (equivalent mutants, not hidden logic).
    avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
}

// (No golden wiring here: `-Pgolden.update` reaches every module's `tier2Test`
// from the root build, T-054.)
