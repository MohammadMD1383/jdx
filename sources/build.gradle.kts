import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.pitest)
}

kotlin {
    // T-052: same contract as :core — every public declaration needs an explicit
    // visibility and return type. Trial-compile before trusting: index passed clean.
    explicitApi()
}

dependencies {
    implementation(project(":core"))
    implementation(libs.javaparser.core)
    testImplementation(libs.kotest.property)
}

// Fixture `-sources.jar` reads (T-071 tier-2 suite). Depend on the jars so a clean
// checkout cannot run against stale ones, and hand the directory in as a system
// property so tests never hard-code an absolute path (same pattern as
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

// Mutation testing (T-060, D-021): measured and reported, not gated. This module is a
// KDoc-only stub until M3 lands real code, so there are no mutants to kill yet —
// `failWhenNoMutations = false` keeps the tier-4 run green until then, at which point the
// 85 % line gate (root build) starts biting on its own.
pitest {
    pitestVersion.set(libs.versions.pitest.get())
    junit5PluginVersion.set(libs.versions.pitestJunitPlugin.get())
    targetClasses.set(setOf("dev.jdx.sources.*"))
    targetTests.set(setOf("dev.jdx.sources.*"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    // Same Intrinsics rationale as :core; applies once M3 lands real code here.
    avoidCallsTo.set(setOf("kotlin.jvm.internal.Intrinsics"))
    failWhenNoMutations.set(false)
}
