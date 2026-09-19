import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.pitest)
}

kotlin {
    // Every public declaration in `core` needs an explicit visibility and return type.
    // This module is the vocabulary the whole project speaks; an accidentally-public helper
    // becomes someone else's dependency. See CONTRIBUTING.md.
    explicitApi()
}

// No production dependencies by design — see ModuleInfo.kt in this module.
// Test-only: property testing (TESTING.md §4); T-055 promotes the shared generators
// in src/test/kotlin/.../gen/ to a project-wide generator library.
dependencies {
    testImplementation(libs.kotest.property)
}

// Mutation testing (T-060, docs/TESTING.md §10, D-021). `core` is the one gated module:
// ≥ 80 % mutation score, build-failing. A surviving mutant here is a genuine gap — kill it
// with a test, or delete the unreachable code. Runs via `./gradlew mutationTest` (tier 4,
// on demand); never in `check`, so the pre-commit loop stays under 3 min.
pitest {
    // PIT tool version, not the Gradle plugin version — both pinned in the catalog (T-001).
    pitestVersion.set(libs.versions.pitest.get())
    // JUnit Platform discovery for PIT. Caveat: this plugin officially supports Platform 1.x
    // (JUnit 5); this build runs JUnit 6.1.3 (pitest/pitest-junit5-plugin#113). The T-060
    // session verified empirically that test discovery works before trusting the gate.
    junit5PluginVersion.set(libs.versions.pitestJunitPlugin.get())
    targetClasses.set(setOf("dev.jdx.core.*"))
    targetTests.set(setOf("dev.jdx.core.*"))
    // `dev.jdx.core.tiers` holds the tier-wiring proof tests (T-053), not product behaviour:
    // `SoakExclusionProofTest` asserts `-Djdx.tier=soak`, a property only the `soakTest` task
    // sets, so it fails under ANY other runner by design — including the PIT minion, which
    // then refuses with "requires a green suite". Excluding it is not hiding a gap: PIT
    // mutates product code, and no mutant there is covered by a build-wiring proof.
    excludedTestClasses.set(setOf("dev.jdx.core.tiers.*"))
    // Fast mutant iteration: `-PpitestScope='dev.jdx.core.render.Truncation*'` narrows both
    // filters to that glob (e.g. while killing one surviving mutant). Never used in CI.
    (findProperty("pitestScope") as String?)?.let { scope ->
        targetClasses.set(setOf(scope))
        targetTests.set(setOf(scope))
    }
    // Property tests run 1,000 cases each; give slow mutants room before calling them kills.
    timeoutConstInMillis.set(10_000)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    threads.set(maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
    // Kotlin compiles `!!` and not-null assertions into `Intrinsics.checkNotNullExpressionValue`
    // calls. Removing them is an equivalent mutant by construction — no correct test can kill
    // it — so they are excluded from mutation, not from coverage. This is PIT's standard
    // `avoidCallsTo` mechanism, not a weakened gate: every product-logic call is still mutated.
    avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
    mutationThreshold.set(80)
}
// The corpus tests (`src/test/.../fixtures/`) read the `testfixtures` jars. Depend on them so a
// clean checkout cannot run tests against stale or missing jars, and hand the directory
// in as a system property so `Fixtures` never hard-codes an absolute path (T-006). The
// dependency is up-to-date-checked, so the incremental loop pays nothing after the first build.
// Both tiers that touch jars (`test` for the fast `FixturesTest`, `tier2Test` for the heavy
// `FixtureCorpusTest`) need the wiring; `soakTest` inherits nothing here — T-059 wires it.
listOf("test", "tier2Test").forEach { taskName ->
    tasks.named<Test>(taskName) {
        dependsOn(":testfixtures:jar", ":testfixtures:sourcesJar")
        systemProperty(
            "jdx.fixturesDir",
            project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        )
    }
}
