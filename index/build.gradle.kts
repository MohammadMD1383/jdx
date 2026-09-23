import org.gradle.api.tasks.testing.Test

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

// Artifact tests (T-007) read the `testfixtures` jars. Depend on them so a clean checkout
// cannot run against stale jars, and hand the directory in as a system property so tests
// never hard-code an absolute path (same pattern as `core/build.gradle.kts`, T-006).
listOf("test", "tier2Test").forEach { taskName ->
    tasks.named<Test>(taskName) {
        dependsOn(":testfixtures:jar", ":testfixtures:sourcesJar")
        systemProperty(
            "jdx.fixturesDir",
            project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        )
    }
}

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
    // properties, but the fixture-corpus tests resolve jars through
    // `jdx.fixturesDir` (T-006). Forward it as a JVM arg, computed the same way
    // as the `test`/`tier2Test` wiring above (an absolute path is fine — PIT runs
    // locally, never in a relocatable cache entry).
    jvmArgs.add(
        "-Djdx.fixturesDir=" +
            project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile.absolutePath,
    )
    timeoutConstInMillis.set(10_000)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    threads.set(maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
    // Same Intrinsics rationale as :core (equivalent mutants, not hidden logic).
    avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
}

// (No golden wiring here: `-Pgolden.update` reaches every module's `tier2Test`
// from the root build, T-054.)
