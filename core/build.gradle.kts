import org.gradle.api.tasks.testing.Test

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
