plugins {
    // Toolchain download source (Gradle 10 forbids undeclared auto-provisioning).
    // Version literal lives here, not in the catalog: catalog accessors are not
    // generated for settings scripts in this build (probed 2026-09-17), so this
    // file is the single source for this settings-only plugin. Fresh machines
    // without a local JDK 21 download it from Foojay; machines with one (e.g.
    // /usr/lib/jvm/java-21-openjdk) never hit the network for it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jdx"

// Module layout — see AGENTS.md
//
// Dependency rule, enforced by review: `core` depends on nothing project-local and has no
// third-party runtime dependencies. Everything else depends on `core`. `cli`, `mcp` and
// `server` are ADAPTERS and must contain no behaviour (D-004).
include(
    "core",       // domain model, symbol refs, resolution, rendering. Pure Kotlin, no IO.
    "index",      // ASM readers, Kotlin metadata, SQLite store, indexer
    "sources",    // sources-jar handling, JavaParser, Kotlin PSI (isolated classloader)
    "decompile",  // Vineflower + javap engines
    "cli",        // Clikt commands, text/JSON renderers
    "mcp",        // MCP stdio server
    "server",     // HTTP/JSON API + daemon
    "app",        // fat-jar assembly + `jdx` launcher script
    "testfixtures", // nasty Java/Kotlin classes compiled to a binary jar + sources jar (T-006)
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
