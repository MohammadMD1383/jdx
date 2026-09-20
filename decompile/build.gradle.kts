plugins {
    alias(libs.plugins.pitest)
}

import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":core"))
    implementation(libs.vineflower)
    testImplementation(libs.kotest.property)
    // The T-021 reuse proof (decompiled text slices through findJavaBodies) —
    // test-only: production decompilation never parses.
    testImplementation(project(":sources"))
    // Fixture bytes for the tier-2 real-engine tests (T-006/T-063).
    testImplementation(testFixtures(project(":testfixtures")))
}

// Real-engine tests read the `testfixtures` jars: depend on them so a clean
// checkout cannot run against stale ones, and hand the directory in as a
// system property so tests never hard-code an absolute path (same pattern as
// `core/build.gradle.kts` and `index/build.gradle.kts`, T-006).
listOf("test", "tier2Test").forEach { taskName ->
    tasks.named<Test>(taskName) {
        dependsOn(":testfixtures:jar", ":testfixtures:sourcesJar")
        systemProperty(
            "jdx.fixturesDir",
            project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        )
    }
}

// Mutation testing (T-060, D-021): measured and reported, not gated. The 85 %
// line gate (root build) applies from here on — T-026 gives `decompile` real
// code, so `failWhenNoMutations` stays false only until the first mutant run.
pitest {
    pitestVersion.set(libs.versions.pitest.get())
    junit5PluginVersion.set(libs.versions.pitestJunitPlugin.get())
    targetClasses.set(setOf("dev.jdx.decompile.*"))
    targetTests.set(setOf("dev.jdx.decompile.*"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    // Same Intrinsics rationale as :core; applies once M3 lands real code here.
    avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
    failWhenNoMutations.set(false)
}
