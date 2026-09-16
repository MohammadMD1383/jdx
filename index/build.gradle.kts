import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":core"))
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

// (No golden wiring here: `-Pgolden.update` reaches every module's `tier2Test`
// from the root build, T-054.)
