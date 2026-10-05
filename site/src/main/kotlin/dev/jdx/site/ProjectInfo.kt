package dev.jdx.site

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/** One published GitHub Release, as the changelog page shows it. */
data class Release(val tag: String, val name: String, val date: String, val body: String, val url: String) {
    val version: String get() = tag.removePrefix("v")
}

/**
 * Git and GitHub facts: page last-modified dates (from git history, so they are deterministic
 * for a given commit — no wall clock), the build commit, and the release list.
 */
class ProjectInfo(private val repoRoot: File) {
    private val lastModifiedCache = mutableMapOf<String, String?>()

    /** ISO-8601 committer date of the last commit touching [paths]; null outside git. */
    fun lastModified(vararg paths: String): String? =
        lastModifiedCache.getOrPut(paths.joinToString("\u0000")) {
            git(listOf("log", "-1", "--format=%cI", "--") + paths)?.trim()?.takeIf { it.isNotEmpty() }
        }

    fun headCommit(): String? = git(listOf("rev-parse", "--short=12", "HEAD"))?.trim()?.takeIf { it.isNotEmpty() }

    /** Newest `vX.Y.Z` tag reachable in this clone — the fallback when no release list is given. */
    fun latestTag(): String? =
        git(listOf("tag", "--list", "v*.*.*", "--sort=-v:refname"))?.lines()?.firstOrNull { it.isNotBlank() }?.trim()

    fun tagDate(tag: String): String? = git(listOf("log", "-1", "--format=%cI", tag))?.trim()?.takeIf { it.isNotEmpty() }

    private fun git(arguments: List<String>): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repoRoot)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) null else output
    }.getOrNull()

    companion object {
        /**
         * Parses the GitHub REST `GET /repos/{owner}/{repo}/releases` array (the pages workflow
         * saves it with `gh api`). Drafts and pre-releases are skipped; newest first.
         */
        fun parseReleases(json: String): List<Release> =
            Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
                .filter { it.flag("draft") != true && it.flag("prerelease") != true }
                .map { release ->
                    val tag = release.string("tag_name").orEmpty()
                    Release(
                        tag = tag,
                        name = release.string("name")?.takeIf { it.isNotBlank() } ?: tag,
                        date = release.string("published_at") ?: release.string("created_at").orEmpty(),
                        body = release.string("body").orEmpty(),
                        url = release.string("html_url").orEmpty(),
                    )
                }
                .filter { it.tag.isNotEmpty() }
                .sortedByDescending { it.date }

        private fun JsonObject.string(key: String): String? =
            this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

        private fun JsonObject.flag(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    }
}
