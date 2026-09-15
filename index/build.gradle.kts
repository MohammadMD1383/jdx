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
