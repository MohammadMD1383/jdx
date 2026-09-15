rootProject.name = "jdx"

// Module layout — see CLAUDE.md §4.
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
