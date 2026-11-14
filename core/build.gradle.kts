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
    // #57: CI runners are slow and shared — lift the timeout there (report-only budgets
    // elsewhere); local runs keep the 10 s gate. Product timeouts (Vineflower/javap,
    // Maven) are degradation behavior and stay intact.
    val isCi: Boolean =
        (findProperty("ci")?.toString()?.let { it != "false" } ?: false) ||
            System.getenv("CI") != null ||
            System.getenv("GITHUB_ACTIONS") == "true"
    timeoutConstInMillis.set(if (isCi) 60_000 else 10_000)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    // #81: `threads` is owned by the root `projectsEvaluated` hook
    // (`pitestThreadCount`) — core stays `cores - 1` (no `javap` fan-out) unless
    // `-PpitestThreads=<n>` overrides. Do not set threads here.
    // Kotlin compiles `!!` and not-null assertions into `Intrinsics.checkNotNullExpressionValue`
    // calls. Removing them is an equivalent mutant by construction — no correct test can kill
    // it — so they are excluded from mutation, not from coverage. This is PIT's standard
    // `avoidCallsTo` mechanism, not a weakened gate: every product-logic call is still mutated.
    avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
    mutationThreshold.set(80)
}
// Fixture-corpus wiring for `test`/`integrationTest`/`benchTest` lives in the root
// `build.gradle.kts` projectsEvaluated hook (#82), next to the PIT wiring (#66). Do not
// reintroduce a private copy here — `verifyFixtureCorpusWiring` fails `check` if one
// reappears.
