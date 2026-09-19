package dev.jdx.cli.commands

import dev.jdx.index.maven.MavenFetch
import dev.jdx.index.maven.MavenResolver
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for `--coord`/`--fetch` root resolution (T-019): explicit coordinates
 * merge in front like `--jars`, stored workspace coords resolve behind the
 * workspace's jars, malformed values are usage errors, and absent artifacts
 * fail with the `--fetch` hint — never a stack trace.
 *
 * Hermetic by construction: an isolated store, a null environment and
 * discovery, and fabricated repositories under `@TempDir` holding a copy of
 * the fixture corpus jar. Tier 2: jar-shaped IO, no network.
 */
@Tag("tier2")
class MavenRootsTest {

    private fun fixtureJar(): Path {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: error("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        return dir.listFiles { file -> file.name.endsWith(".jar") && !file.name.contains("sources") }
            ?.sortedBy { it.name }?.firstOrNull()?.toPath()
            ?: error("no fixture binary jar in $dir")
    }

    private fun m2WithDemo(root: Path): Path {
        val versionDir = root.resolve("m2/com/example/demo/1.0")
        Files.createDirectories(versionDir)
        Files.copy(fixtureJar(), versionDir.resolve("demo-1.0.jar"))
        return root.resolve("m2")
    }

    private fun reposFor(root: Path, m2: Path): MavenResolver.Repositories =
        MavenResolver.Repositories(
            gradleFilesRoot = null,
            m2Repo = m2,
            fetchCacheRoot = root.resolve("cache"),
        )

    private fun resolve(
        root: Path,
        m2: Path,
        coords: List<String>,
        allowFetch: Boolean = false,
        workspace: String? = null,
        store: dev.jdx.index.workspace.WorkspaceStore = InMemoryWorkspaceStore(),
        repos: List<String> = emptyList(),
        fetcher: MavenFetch.Fetcher = MavenFetch.Fetcher { error("must not fetch") },
    ): ReadCommandSupport.RootsOrFailure = ReadCommandSupport.resolveRoots(
        jars = emptyList(),
        noJdk = true,
        workspace = workspace,
        store = store,
        getenv = { null },
        workingDir = root,
        cacheBase = root.resolve("auto"),
        gradleFilesRoot = null,
        m2Repo = null,
        discover = { _, _, _, _ -> null },
        coords = coords,
        allowFetch = allowFetch,
        mavenRepositories = reposFor(root, m2),
        mavenFetcher = fetcher,
        repos = repos,
    )

    @Test
    fun `an explicit coord resolves to its jar and answers queries`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val resolved = resolve(root, m2, listOf("com.example:demo:1.0"))
        ((resolved is ReadCommandSupport.RootsOrFailure.Ready)) shouldBe true
        val roots = (resolved as ReadCommandSupport.RootsOrFailure.Ready).roots
        (roots.jarSpecs.size) shouldBe 1
        roots.jarSpecs.single() shouldContain "demo-1.0.jar"
        val outcome = JdxService.show("dev.jdx.fixtures.Generics", roots)
        (outcome.exitCode) shouldBe 0
        outcome.renderText() shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `a malformed coord is a usage error`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val resolved = resolve(root, m2, listOf("not-a-coordinate"))
        ((resolved is ReadCommandSupport.RootsOrFailure.Failed)) shouldBe true
        val outcome = (resolved as ReadCommandSupport.RootsOrFailure.Failed).outcome
        (outcome.exitCode) shouldBe 3
        outcome.renderText() shouldContain "group:artifact:version"
    }

    @Test
    fun `an absent coord without fetch fails naming --fetch`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val resolved = resolve(root, m2, listOf("com.example:missing:9.9.9"))
        ((resolved is ReadCommandSupport.RootsOrFailure.Failed)) shouldBe true
        val outcome = (resolved as ReadCommandSupport.RootsOrFailure.Failed).outcome
        (outcome.exitCode) shouldBe 5
        outcome.renderText() shouldContain "--fetch"
    }

    @Test
    fun `stored workspace coords resolve behind the workspace jars`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val store = InMemoryWorkspaceStore()
        store.save(WorkspaceDefinition("ws", listOf("other.jar"), false, listOf("com.example:demo:1.0")))
        val resolved = resolve(root, m2, emptyList(), workspace = "ws", store = store)
        // `other.jar` names nothing real, so resolution of the stored coord must
        // still succeed on its own — the jar failure surfaces at query time.
        // Here the stored coord resolves; the assertion is on its position.
        ((resolved is ReadCommandSupport.RootsOrFailure.Ready)) shouldBe true
        val roots = (resolved as ReadCommandSupport.RootsOrFailure.Ready).roots
        (roots.jarSpecs.size) shouldBe 2
        roots.jarSpecs[0] shouldBe "other.jar"
        roots.jarSpecs[1] shouldContain "demo-1.0.jar"
    }

    // -- T-069: configurable repositories -------------------------------------

    @Test
    fun `an invalid repo is a usage error naming the value`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val resolved = resolve(root, m2, listOf("com.example:demo:1.0"), repos = listOf("ftp://mirror.example.com/maven2"))
        ((resolved is ReadCommandSupport.RootsOrFailure.Failed)) shouldBe true
        val outcome = (resolved as ReadCommandSupport.RootsOrFailure.Failed).outcome
        (outcome.exitCode) shouldBe 3
        outcome.renderText() shouldContain "ftp://mirror.example.com/maven2"
    }

    @Test
    fun `a trailing-slash-less loopback repo fetches explicit coords`(@TempDir root: Path) {
        val jarBytes = Files.readAllBytes(fixtureJar())
        // 9.9.9 is absent from the local m2, so only the loopback mirror can serve it.
        val path = "/repo/com/example/demo/9.9.9"
        val files = mapOf(
            "$path/demo-9.9.9.jar" to jarBytes,
            "$path/demo-9.9.9.jar.sha1" to "${MavenFetch.sha1Hex(jarBytes)}\n".toByteArray(Charsets.UTF_8),
        )
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            for ((contextPath, bytes) in files) {
                server.createContext(contextPath) { exchange ->
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
            // No trailing slash — the T-069 acceptance shape.
            val base = "http://127.0.0.1:${server.address.port}/repo"
            val m2 = m2WithDemo(root)
            val resolved = resolve(
                root, m2,
                coords = listOf("com.example:demo:9.9.9"),
                allowFetch = true,
                repos = listOf(base),
                fetcher = MavenFetch.httpFetcher(),
            )
            ((resolved is ReadCommandSupport.RootsOrFailure.Ready)) shouldBe true
            val roots = (resolved as ReadCommandSupport.RootsOrFailure.Ready).roots
            (roots.jarSpecs.size) shouldBe 1
            roots.jarSpecs.single() shouldContain "demo-9.9.9.jar"
            // The fetched jar answers queries — the full --repo → fetch → read path.
            // Note: the served bytes are the fixture jar, so any fixture class answers.
            val outcome = JdxService.show("dev.jdx.fixtures.Generics", roots)
            (outcome.exitCode) shouldBe 0
            outcome.renderText() shouldContain "dev.jdx.fixtures.Generics"
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stored workspace repos serve explicit coords`(@TempDir root: Path) {
        val jarBytes = Files.readAllBytes(fixtureJar())
        val path = "/repo/com/example/demo/1.0"
        val files = mapOf(
            "$path/demo-1.0.jar" to jarBytes,
            "$path/demo-1.0.jar.sha1" to "${MavenFetch.sha1Hex(jarBytes)}\n".toByteArray(Charsets.UTF_8),
        )
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            for ((contextPath, bytes) in files) {
                server.createContext(contextPath) { exchange ->
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}/repo"
            // Empty local m2: only the stored mirror can serve the coordinate.
            val emptyM2 = root.resolve("empty-m2")
            Files.createDirectories(emptyM2)
            val store = InMemoryWorkspaceStore()
            store.save(WorkspaceDefinition("ws", emptyList(), false, emptyList(), listOf(base)))
            val resolved = resolve(
                root, emptyM2,
                coords = listOf("com.example:demo:1.0"),
                allowFetch = true,
                workspace = "ws",
                store = store,
                fetcher = MavenFetch.httpFetcher(),
            )
            ((resolved is ReadCommandSupport.RootsOrFailure.Ready)) shouldBe true
            val roots = (resolved as ReadCommandSupport.RootsOrFailure.Ready).roots
            (roots.jarSpecs.size) shouldBe 1
            roots.jarSpecs.single() shouldContain "demo-1.0.jar"
        } finally {
            server.stop(0)
        }
    }
}
