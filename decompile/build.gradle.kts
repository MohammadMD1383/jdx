plugins {
    alias(libs.plugins.pitest)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.vineflower)
}

// Mutation testing (T-060, D-021): measured and reported, not gated. Same stub status as
// `sources` (KDoc-only until the M3 Vineflower engine lands) — see its build file.
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
