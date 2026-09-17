package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import dev.jdx.index.cache.CacheService
import dev.jdx.index.index.ArtifactIndexer
import dev.jdx.index.store.NewArtifact
import dev.jdx.index.store.sqlite.SqliteIndexStore
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Behaviour of `jdx cache ...` through the real Clikt commands against a real
 * index on `@TempDir` roots (T-018, tier 2).
 *
 * Reference policy is pinned in `index` tier-1 tests; here the assertions are
 * structural — flags reach the service, exits follow D-015, `--json` carries
 * the same facts as text, runs are deterministic — so output wording can
 * evolve without these going red for the wrong reason.
 */
@Tag("tier2")
class CacheCommandsTest {

    @TempDir
    private lateinit var tempDir: Path

    private class Harness(val temp: Path) {
        val cacheRoot: Path = temp.resolve("cache")
        val configDir: Path = temp.resolve("config")
        val store = FileWorkspaceStore(configDir)

        fun service(): CacheService = CacheService(cacheRoot, store)

        fun group(): CacheCommand = testCacheGroup(service())

        fun indexFixtureJar(): String {
            val jar = fixtureJar().absolutePath
            SqliteIndexStore.open(cacheRoot.resolve("index/v1.db")).use { db ->
                ArtifactIndexer.indexOne(db, Path.of(jar))
            }
            return jar
        }

        fun addStale(path: String = "/nonexistent/ghost.jar", hash: String = "0".repeat(32)) {
            SqliteIndexStore.open(cacheRoot.resolve("index/v1.db")).use { db ->
                db.upsertArtifact(NewArtifact(hash = hash, path = path))
            }
        }
    }

    private fun run(group: CacheCommand, argv: List<String>): Pair<String, Int?> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val thrown = try {
            group.parse(argv)
            null
        } catch (e: CacheExit) {
            e
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8) to thrown?.code
    }

    // -- info ---------------------------------------------------------------

    @Test
    fun `info reports the indexed jar and exits zero`() {
        val harness = Harness(tempDir)
        harness.indexFixtureJar()
        val (output, code) = run(harness.group(), listOf("info"))
        code shouldBe null
        output shouldContain "cache info"
        output shouldContain "artifacts: 1 ("
        output shouldContain "schema 1"
    }

    @Test
    fun `info on an empty cache names the missing database`() {
        val harness = Harness(tempDir)
        val (output, code) = run(harness.group(), listOf("info"))
        code shouldBe null
        output shouldContain "database: none"
        output shouldContain "artifacts: 0 (0 classes)"
    }

    @Test
    fun `info is deterministic across runs`() {
        val harness = Harness(tempDir)
        harness.indexFixtureJar()
        val (first, _) = run(harness.group(), listOf("info"))
        val (second, _) = run(harness.group(), listOf("info"))
        first shouldBe second
    }

    // -- gc -----------------------------------------------------------------

    @Test
    fun `gc collects the stale row and keeps the referenced jar`() {
        val harness = Harness(tempDir)
        val jar = harness.indexFixtureJar()
        harness.addStale()
        harness.store.save(WorkspaceDefinition("app", listOf(jar)))
        val (output, code) = run(harness.group(), listOf("gc"))
        code shouldBe null
        output shouldContain "deleted: 1 artifact(s)"
        output shouldContain "/nonexistent/ghost.jar"
        output shouldContain "kept: 1 artifact(s)"
    }

    @Test
    fun `gc dry-run reports without deleting`() {
        val harness = Harness(tempDir)
        harness.indexFixtureJar()
        harness.addStale()
        val (output, code) = run(harness.group(), listOf("gc", "--dry-run"))
        code shouldBe null
        output shouldContain "would delete"
        output shouldContain "re-run without --dry-run"
        SqliteIndexStore.open(harness.cacheRoot.resolve("index/v1.db")).use { db ->
            db.listArtifacts().size shouldBe 2
        }
    }

    @Test
    fun `gc on an empty cache reports nothing to collect`() {
        val harness = Harness(tempDir)
        val (output, code) = run(harness.group(), listOf("gc"))
        code shouldBe null
        output shouldContain "nothing to collect"
    }

    // -- clear ---------------------------------------------------------------

    @Test
    fun `clear wipes the database and info afterwards is empty`() {
        val harness = Harness(tempDir)
        harness.indexFixtureJar()
        val (cleared, clearCode) = run(harness.group(), listOf("clear"))
        clearCode shouldBe null
        cleared shouldContain "cache cleared"
        cleared shouldContain "deleted: "
        val (info, infoCode) = run(harness.group(), listOf("info"))
        infoCode shouldBe null
        info shouldContain "database: none"
    }

    @Test
    fun `clear on an empty cache reports nothing to clear`() {
        val harness = Harness(tempDir)
        val (output, code) = run(harness.group(), listOf("clear"))
        code shouldBe null
        output shouldContain "nothing to clear"
    }

    // -- exits and parity ------------------------------------------------------

    @Test
    fun `a cache-dir pointing at a file is a usage error`() {
        val harness = Harness(tempDir)
        val file = tempDir.resolve("afile")
        file.toFile().writeText("x")
        val (_, code) = run(harness.group(), listOf("info", "--cache-dir", file.toString()))
        code shouldBe 3
    }

    @Test
    fun `every cache command carries the same facts in json`() {
        val harness = Harness(tempDir)
        val jar = harness.indexFixtureJar()
        harness.addStale()
        harness.store.save(WorkspaceDefinition("app", listOf(jar)))

        val (infoText, _) = run(harness.group(), listOf("info"))
        val (infoJson, _) = run(harness.group(), listOf("info", "--json"))
        val info = Json.parseToJsonElement(infoJson.trim()).jsonObject
        info["ok"]?.jsonPrimitive?.content shouldBe "true"
        info["command"]?.jsonPrimitive?.content shouldBe "cache info"
        val artifacts = info["result"]!!.jsonObject["artifacts"]!!.jsonPrimitive.int
        artifacts shouldBe 2
        infoText shouldContain "artifacts: $artifacts ("

        val (gcJson, _) = run(harness.group(), listOf("gc", "--json"))
        val gc = Json.parseToJsonElement(gcJson.trim()).jsonObject
        gc["command"]?.jsonPrimitive?.content shouldBe "cache gc"
        val deleted = gc["result"]!!.jsonObject["deleted"]!!.jsonArray
        deleted.size shouldBe 1
        deleted[0].jsonObject["path"]?.jsonPrimitive?.content shouldBe "/nonexistent/ghost.jar"

        val (clearJson, clearCode) = run(harness.group(), listOf("clear", "--json"))
        clearCode shouldBe null
        val clear = Json.parseToJsonElement(clearJson.trim()).jsonObject
        clear["ok"]?.jsonPrimitive?.content shouldBe "true"
        (clear["result"]!!.jsonObject["filesDeleted"]!!.jsonPrimitive.int >= 1) shouldBe true
    }

    @Test
    fun `root-position json reaches grouped subcommands`() {
        val harness = Harness(tempDir)
        harness.indexFixtureJar()
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            JdxCli().subcommands(harness.group()).parse(listOf("--json", "cache", "info"))
        } finally {
            System.setOut(original)
        }
        val parsed = Json.parseToJsonElement(buffer.toString(Charsets.UTF_8).trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "cache info"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `cache runs through the root cli`() {
        val harness = Harness(tempDir)
        harness.indexFixtureJar()
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            JdxCli().subcommands(harness.group()).parse(listOf("cache", "info"))
        } finally {
            System.setOut(original)
        }
        buffer.toString(Charsets.UTF_8) shouldContain "cache info"
    }
}

/** The binary fixture jar, resolved without hard-coded paths (cf. T-063). */
private fun fixtureJar(): File {
    val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
        ?: throw AssertionError("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
    return dir.listFiles { file ->
        file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
    }?.singleOrNull() ?: throw AssertionError("expected exactly one binary fixture jar in $dir")
}
