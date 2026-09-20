package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.model.WarningCode
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

/**
 * Project auto-discovery in root resolution (T-016, tier 2): with no workspace
 * selected, a Gradle project enclosing the working directory contributes its
 * `build/classes` roots (plus a fallback warning); a named workspace suppresses
 * discovery; bare directories resolve as before.
 */
@Tag("tier2")
class ReadCommandDiscoveryTest {

    private fun project(temp: Path): Path {
        val root = temp.resolve("proj").also { Files.createDirectories(it) }
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = \"demo\"\n")
        root.resolve("build/classes/java/main").also { Files.createDirectories(it) }
        return root
    }

    private fun ready(
        temp: Path,
        workingDir: Path,
        workspace: String? = null,
        store: InMemoryWorkspaceStore = InMemoryWorkspaceStore(),
    ): JdxService.RootsSpec {
        val resolved = ReadCommandSupport.resolveRoots(
            jars = emptyList(),
            noJdk = false,
            workspace = workspace,
            store = store,
            getenv = { null },
            workingDir = workingDir,
            cacheBase = temp.resolve("auto"),
            gradleFilesRoot = temp.resolve("gradle-missing"),
            m2Repo = temp.resolve("m2-missing"),
        )
        check(resolved is ReadCommandSupport.RootsOrFailure.Ready) { "expected roots, got $resolved" }
        return resolved.roots
    }

    @Test
    fun `an enclosing gradle project contributes its classes dir`(@TempDir temp: Path) {
        val root = project(temp)
        val roots = ready(temp, root.resolve("sub/inner").also { Files.createDirectories(it) })
        roots.jarSpecs shouldBe listOf(root.resolve("build/classes/java/main").toString())
        roots.includeJdk shouldBe true
        roots.extraWarnings.map { it.code } shouldBe listOf(WarningCode.PROJECT_DISCOVERY_FALLBACK)
    }

    @Test
    fun `discovery populates the auto-cache`(@TempDir temp: Path) {
        val root = project(temp)
        ready(temp, root)
        val cached = Files.list(temp.resolve("auto")).use { stream -> stream.toList() }
        (cached.size shouldBe 2)
        (cached.map { it.fileName.toString() }.sorted().joinToString(",") shouldContain ".toml")
    }

    @Test
    fun `a named workspace suppresses discovery`(@TempDir temp: Path) {
        val root = project(temp)
        val store = InMemoryWorkspaceStore()
        store.save(WorkspaceDefinition("ws", listOf("ws.jar"), includeJdk = false))
        val roots = ready(temp, root, workspace = "ws", store = store)
        roots.jarSpecs shouldBe listOf("ws.jar")
        roots.includeJdk shouldBe false
        roots.extraWarnings shouldBe emptyList()
    }

    @Test
    fun `a bare directory resolves as before`(@TempDir temp: Path) {
        val bare = temp.resolve("bare").also { Files.createDirectories(it) }
        val roots = ready(temp, bare)
        roots.jarSpecs shouldBe emptyList()
        roots.includeJdk shouldBe true
        roots.extraWarnings shouldBe emptyList()
    }

    @Test
    fun `members answers from the discovered project end to end`(@TempDir temp: Path) {
        val root = project(temp)
        val binary = copyFirstFixtureClass(root)
        val cache = temp.resolve("auto")
        // The real derivation, pointed at the fabricated project regardless of the
        // test runner's own working directory.
        val discovered: ProjectDiscoveryFn = { _, _, _, _ ->
            ReadCommandSupport.discoverProject(root, cache, null, null)
        }
        var exit = 0
        val output = captureStdout {
            try {
                MembersCommand(
                    query = ::defaultMemberQuery,
                    terminate = { exit = it; throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = { null },
                    discover = discovered,
                ).parse(listOf(binary, "--no-jdk"))
            } catch (e: TestExit) {
                exit = e.code
            }
        }
        exit shouldBe 0
        output shouldContain "members of $binary"
        output shouldContain "PROJECT_DISCOVERY_FALLBACK"
    }

    private class TestExit(val code: Int) : RuntimeException()

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    /** Copies the first top-level fixture class into the project's classes dir. */
    private fun copyFirstFixtureClass(root: Path): String {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        val jar = dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
        ZipFile(jar).use { zip ->
            val entry = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && '$' !in it.name }
                .first()
            val target = root.resolve("build/classes/java/main").resolve(entry.name)
            Files.createDirectories(target.parent)
            zip.getInputStream(entry).use { input -> Files.copy(input, target) }
            return entry.name.removeSuffix(".class").replace('/', '.')
        }
    }
}
