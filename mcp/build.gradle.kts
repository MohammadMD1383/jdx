import org.gradle.api.tasks.testing.Test

kotlin {
    // T-052: same contract as :core — every public declaration needs an explicit
    // visibility and return type. Thin adapters (D-004) still have public API.
    explicitApi()
}

dependencies {
    implementation(project(":core"))
    // Query dispatch (T-043): every tool answers through the same `JdxService`
    // the one-shot CLI calls. :mcp stays a thin adapter (D-004) — param parsing
    // lives in :index next to the service, next to the daemon's (T-082).
    implementation(project(":index"))
    implementation(libs.mcp.kotlin.sdk)
    // Stdio is the protocol: kotlin-logging's startup banner goes to stdout by
    // default, which would corrupt the JSON-RPC stream — silenced at startup
    // in serveMcpStdioBlocking (McpServer.kt). Slf4j has no binding in the fat
    // jar, so all further SDK logging is NOP (its one stderr warning is fine).
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    // Generating family for the tool-table tests (TESTING.md §2): schema
    // determinism and hostile-args never-throws properties in McpToolsTest.
    testImplementation(libs.kotest.property)
    // Dispatch tests answer over the fixture jar: hand the directory in as a
    // system property so tests never hard-code paths (same pattern as
    // index/build.gradle.kts and server/build.gradle.kts, T-006).
    testImplementation(testFixtures(project(":testfixtures")))
}

// Dispatch tests (T-043) run the real `JdxService` over the `testfixtures` jars.
listOf("test", "tier2Test").forEach { taskName ->
    tasks.named<Test>(taskName) {
        dependsOn(":testfixtures:jar", ":testfixtures:sourcesJar")
        systemProperty(
            "jdx.fixturesDir",
            project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        )
    }
}
