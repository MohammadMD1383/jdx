package dev.jdx.site

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Retry policy for the site's Maven fixture downloads. No network: the attempt
 * function returns canned [JdxResult]s, and the sleeper records delays.
 */
class FixtureFetchTest {

    private val networkFailure = JdxResult(
        exitCode = 5,
        stdout = "",
        stderr = "artifact read error: cannot fetch Maven coordinate 'com.google.code.gson:gson:2.14.0' " +
            "from https://repo.maven.apache.org/maven2/ (network unreachable, artifact missing, " +
            "or checksum rejected — nothing was written)",
    )

    private val checksumFailure = JdxResult(
        exitCode = 5,
        stdout = "",
        stderr = "artifact read error: cannot fetch Maven coordinate 'com.google.code.gson:gson:2.14.0' " +
            "from https://repo.maven.apache.org/maven2/ (checksum rejected — nothing was written)",
    )

    @Test
    fun `a successful fetch is one attempt`() {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val result = FixtureFetch.retry(
            attempt = { calls++; JdxResult(0, "class com.google.gson.Gson", "") },
            sleep = { sleeps += it },
        )
        calls shouldBe 1
        result.exitCode shouldBe 0
        sleeps.shouldBeEmpty()
    }

    @Test
    fun `a transient failure retries with backoff then returns success`() {
        val sleeps = mutableListOf<Long>()
        val retries = mutableListOf<Pair<Int, Long>>()
        var calls = 0
        val result = FixtureFetch.retry(
            attempt = {
                calls++
                if (calls < 3) networkFailure else JdxResult(0, "ok", "")
            },
            sleep = { sleeps += it },
            onRetry = { attempt, delay -> retries += attempt to delay },
        )
        calls shouldBe 3
        result.exitCode shouldBe 0
        sleeps shouldBe listOf(2_000L, 5_000L)
        retries shouldBe listOf(1 to 2_000L, 2 to 5_000L)
    }

    @Test
    fun `four transient failures return the last one after three sleeps`() {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val result = FixtureFetch.retry(
            attempt = {
                calls++
                networkFailure.copy(stdout = "attempt $calls")
            },
            sleep = { sleeps += it },
        )
        calls shouldBe FixtureFetch.MAX_ATTEMPTS
        result.stdout shouldBe "attempt 4"
        sleeps shouldBe FixtureFetch.BACKOFF_MS
        result.stderr shouldContain "network unreachable"
    }

    @Test
    fun `a checksum mismatch is not retried`() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val result = FixtureFetch.retry(
            attempt = { calls++; checksumFailure },
            sleep = { sleeps += it },
        )
        calls shouldBe 1
        result.stderr shouldContain "checksum rejected"
        sleeps.shouldBeEmpty()
    }

    @Test
    fun `a raw checksum mismatch phrase is not retried`() {
        var calls = 0
        val result = FixtureFetch.retry(
            attempt = {
                calls++
                JdxResult(5, "", "checksum mismatch (want abc, got def)")
            },
            sleep = { error("must not sleep") },
        )
        calls shouldBe 1
        result.exitCode shouldBe 5
    }

    @Test
    fun `a non-fetch failure is not retried`() {
        var calls = 0
        val result = FixtureFetch.retry(
            attempt = { calls++; JdxResult(1, "not found: com.google.gson.Gson", "") },
            sleep = { error("must not sleep") },
        )
        calls shouldBe 1
        result.exitCode shouldBe 1
    }

    @Test
    fun `sandbox rebuild keeps the maven fixture cache and drops the rest`() {
        val work = java.nio.file.Files.createTempDirectory("jdx-site-sandbox").toFile()
        try {
            val jar = File(work, "$FIXTURE_CACHE_PATH/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar")
            jar.parentFile.mkdirs()
            jar.writeText("cached")
            File(work, "sandbox/config/workspaces/demo.toml").apply {
                parentFile.mkdirs()
                writeText("leak")
            }
            val sandbox = prepareSandbox(work)
            File(sandbox, "cache/jdx/m2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar").readText() shouldBe "cached"
            File(sandbox, "config/workspaces/demo.toml").exists() shouldBe false
            File(work, "fixture-cache-hold").exists() shouldBe false
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `a held fixture cache from a cancelled build is restored`() {
        val work = java.nio.file.Files.createTempDirectory("jdx-site-sandbox").toFile()
        try {
            val held = File(work, "fixture-cache-hold/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar")
            held.parentFile.mkdirs()
            held.writeText("held")
            val sandbox = prepareSandbox(work)
            File(sandbox, "cache/jdx/m2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar").readText() shouldBe "held"
            File(work, "fixture-cache-hold").exists() shouldBe false
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `pages workflow caches the fixture directory on the fetched coordinates`() {
        val repo = File(System.getProperty("jdx.repoRoot") ?: error("jdx.repoRoot not set"))
        val workflow = File(repo, ".github/workflows/pages.yml").readText()
        workflow shouldContain "site/build/site-work/$FIXTURE_CACHE_PATH"
        val keyLine = workflow.lines().first { "site-fixtures-" in it && "key:" in it }
        for (token in fetchedCoordinateTokens(repo)) {
            (token in keyLine) shouldBe true
        }
    }

    /**
     * `artifact-version` for every coordinate an executed example actually downloads:
     * `$ jdx ... --fetch` lines in the docs, `--fetch` strings in ReferencePages, and
     * the prefetch constant.
     */
    private fun fetchedCoordinateTokens(repo: File): Set<String> {
        val coord = Regex("""[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][A-Za-z0-9_.-]*""")
        val found = mutableSetOf(ReferencePages.GSON)
        val files = repo.resolve("docs").walkTopDown().filter { it.isFile && it.extension == "md" }.toList() +
            listOf(File(repo, "README.md"), File(repo, "site/src/main/kotlin/dev/jdx/site/ReferencePages.kt"))
        for (file in files) {
            if (!file.isFile) continue
            for (line in file.readLines()) {
                val trimmed = line.trim()
                val executed = trimmed.startsWith("$ ") || (file.name == "ReferencePages.kt" && "--fetch" in line)
                if (!executed || "--fetch" !in line) continue
                found += coord.findAll(line).map { it.value }
            }
        }
        return found.map { coordinate ->
            val artifact = coordinate.substringAfter(':').substringBefore(':')
            val version = coordinate.substringAfterLast(':')
            "$artifact-$version"
        }.toSet()
    }
}
