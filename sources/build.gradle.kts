plugins {
    alias(libs.plugins.pitest)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.javaparser.core)
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
