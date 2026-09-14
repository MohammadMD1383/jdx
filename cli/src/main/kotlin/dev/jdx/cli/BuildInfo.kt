package dev.jdx.cli

/**
 * Build-time facts about this jdx distribution. Produced by the Gradle build (see
 * `cli/build.gradle.kts`, task `generateBuildProperties`) — the version is the project
 * version and must never be hand-edited in source.
 */
object BuildInfo {

    /** The version this CLI was built as, e.g. `0.1.0-SNAPSHOT`; `dev` when running from a context without the Gradle-generated resource. */
    val version: String = BuildInfo::class.java.getResourceAsStream(RESOURCE_PATH)
        ?.bufferedReader()
        ?.readLines()
        ?.firstOrNull { it.startsWith(VERSION_LINE_PREFIX) }
        ?.removePrefix(VERSION_LINE_PREFIX)
        ?.trim()
        ?: FALLBACK_VERSION

    private const val RESOURCE_PATH = "/dev/jdx/cli/build.properties"
    private const val VERSION_LINE_PREFIX = "version="
    private const val FALLBACK_VERSION = "dev"
}
