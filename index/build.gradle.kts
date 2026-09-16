import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":core"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.util)
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotest.property)
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

// Golden-file update mode (T-010, TESTING.md §14): `./gradlew :index:tier2Test
// -Pgolden.update=true` rewrites every golden under src/test/resources/golden.
// The flag travels as a system property so tests stay environment-agnostic.
// T-054 promotes this to shared per-module wiring.
tasks.named<Test>("tier2Test") {
    systemProperty("jdx.golden.update", (findProperty("golden.update") as String?) ?: "false")
}
