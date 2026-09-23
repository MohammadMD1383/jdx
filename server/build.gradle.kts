import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":core"))
    // Query dispatch (T-082): the handler answers every read RpcCommand through
    // the same JdxService the one-shot CLI calls. :server stays a thin adapter
    // (D-004) — param parsing lives in :index next to the service.
    implementation(project(":index"))
    implementation(libs.kotlinx.serialization.json)
    // Generating family for the socket tests (TESTING.md §2): the framing
    // round-trip property in DaemonServerTest.
    testImplementation(libs.kotest.property)
    // Dispatch tests (T-082) answer over the fixture jar through the live
    // socket: hand the directory in as a system property so tests never
    // hard-code paths (same pattern as index/build.gradle.kts, T-006).
    testImplementation(testFixtures(project(":testfixtures")))
}

// Dispatch tests (T-082) run the real `JdxService` over the `testfixtures` jars.
listOf("test", "tier2Test").forEach { taskName ->
    tasks.named<Test>(taskName) {
        dependsOn(":testfixtures:jar", ":testfixtures:sourcesJar")
        systemProperty(
            "jdx.fixturesDir",
            project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        )
    }
}
