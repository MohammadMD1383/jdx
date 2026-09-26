package dev.jdx.index.workspace

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [FileWorkspaceStore] (T-015) over fabricated config homes — no real home
 * directory, no environment surgery, millisecond IO, so this suite stays in tier 1
 * (same rationale as `JdkLayoutTest`). Real-workspace reads through `JdxService`
 * are tier 2 in `WorkspaceServiceTest`.
 */
class WorkspaceStoreTest {

    @Test
    fun `an empty config reads as no workspaces and no active`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        store.listNames() shouldBe emptyList()
        store.load("mc") shouldBe null
        store.activeName() shouldBe null
    }

    @Test
    fun `save then load round-trips exactly`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        val definition = WorkspaceDefinition("mc", listOf("a.jar", "b/*.jar"), includeJdk = false)
        store.save(definition)
        store.load("mc") shouldBe definition
        store.listNames() shouldBe listOf("mc")
    }

    @Test
    fun `names list in sorted order`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        store.save(WorkspaceDefinition("zeta", emptyList()))
        store.save(WorkspaceDefinition("alpha", emptyList()))
        store.listNames() shouldBe listOf("alpha", "zeta")
    }

    @Test
    fun `delete removes and reports`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        store.save(WorkspaceDefinition("mc", emptyList()))
        store.delete("mc") shouldBe true
        store.delete("mc") shouldBe false
        store.listNames() shouldBe emptyList()
    }

    @Test
    fun `use selection round-trips and clears`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        store.setActive("mc")
        store.activeName() shouldBe "mc"
        store.setActive(null)
        store.activeName() shouldBe null
    }

    @Test
    fun `garbage active content reads as none`(@TempDir config: Path) {
        Files.createDirectories(config)
        Files.writeString(config.resolve("active-workspace"), "not a valid name!\nsecond line\n")
        FileWorkspaceStore(config).activeName() shouldBe null
    }

    @Test
    fun `a corrupt workspace file errors naming the workspace`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        Files.createDirectories(config.resolve("workspaces"))
        Files.writeString(config.resolve("workspaces/mc.toml"), "name = \"other\"\n")
        var message = ""
        try {
            store.load("mc")
        } catch (e: java.io.IOException) {
            message = e.message ?: ""
        }
        message shouldContain "workspace 'mc' is corrupt"
    }

    @Test
    fun `a non-toml file beside the workspaces is not listed`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        store.save(WorkspaceDefinition("mc", emptyList()))
        Files.writeString(config.resolve("workspaces/notes.txt"), "hello")
        store.listNames() shouldBe listOf("mc")
    }

    @Test
    fun `invalid names never touch the filesystem`(@TempDir config: Path) {
        val store = FileWorkspaceStore(config)
        var message = ""
        try {
            store.save(WorkspaceDefinition("../evil", emptyList()))
        } catch (e: java.io.IOException) {
            message = e.message ?: ""
        }
        message shouldContain "invalid workspace name"
        Files.exists(config.resolve("workspaces")) shouldBe false
    }

    @Test
    fun `in-memory store mirrors the file store contract`() {
        val store = InMemoryWorkspaceStore()
        store.listNames() shouldBe emptyList()
        store.save(WorkspaceDefinition("mc", listOf("a.jar")))
        store.load("mc") shouldBe WorkspaceDefinition("mc", listOf("a.jar"))
        store.setActive("mc")
        store.activeName() shouldBe "mc"
        store.delete("mc") shouldBe true
        store.activeName() shouldBe "mc"
    }

    @Test
    fun `the active file is LF-terminated, never CRLF`(@TempDir config: Path) {
        // Pinned byte-exact: `activeName` must read identically on Windows and
        // Unix checkouts, so the writer never uses `lineSeparator()`.
        val store = FileWorkspaceStore(config)
        store.setActive("mc")
        val bytes = Files.readAllBytes(config.resolve("active-workspace"))
        bytes shouldBe "mc\n".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `a CRLF active file still selects the workspace`(@TempDir config: Path) {
        Files.createDirectories(config)
        Files.write(config.resolve("active-workspace"), "mc\r\n".toByteArray(Charsets.UTF_8))
        FileWorkspaceStore(config).activeName() shouldBe "mc"
    }
}
