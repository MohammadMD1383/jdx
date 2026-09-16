import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":index"))
    implementation(project(":sources"))
    implementation(project(":decompile"))
    // clikt-core, NOT clikt: the mordant flavor eagerly loads JNA (native-access warnings
    // on JDK 22+, slower startup, ~2 MB of fat jar) and jdx never renders a terminal.
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.property)
}

// The CLI must report the version it was built as (`jdx --version`, later `jdx version --json`).
// The single source of that version is the Gradle project version, so it is generated into a
// resource at build time — never hard-coded in source. Gradle's own `WriteProperties` task is
// deliberately NOT used: it stamps the wall-clock date into the file, which would break the
// byte-determinism every jar here promises (CLAUDE.md §2.5).
val generateBuildProperties = tasks.register("generateBuildProperties") {
    val outputFile = layout.buildDirectory.file("generated/build-info/dev/jdx/cli/build.properties")
    inputs.property("version", provider { project.version.toString() })
    outputs.file(outputFile)
    doLast {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version=${project.version}\n")
    }
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/build-info"))
    }
}

tasks.named("processResources") { dependsOn(generateBuildProperties) }

// Read-command tests (T-011) run the real `JdxService` over the `testfixtures` jars:
// hand the directory in as a system property so tests never hard-code paths
// (same pattern as `index/build.gradle.kts`, T-006).
listOf("test", "tier2Test").forEach { taskName ->
    tasks.named<Test>(taskName) {
        dependsOn(":testfixtures:jar", ":testfixtures:sourcesJar")
        systemProperty(
            "jdx.fixturesDir",
            project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile.absolutePath,
        )
    }
}

// Golden-file update mode (TESTING.md §14): `./gradlew :cli:tier2Test
// -Pgolden.update=true` rewrites every golden under src/test/resources/golden.
tasks.named<Test>("tier2Test") {
    systemProperty("jdx.golden.update", (findProperty("golden.update") as String?) ?: "false")
}
