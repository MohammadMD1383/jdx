package dev.jdx.testsupport.golden

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for the shared golden-file helper (T-054): verify/update modes, the
 * per-file rewrite summary, and the orphan check.
 *
 * Everything here touches the filesystem (even if only a [TempDir]), so the
 * class is `@Tag("tier2")` — the fast loop must never wait on disk
 * (docs/TESTING.md §2). Pure string logic (the diff itself) is pinned tier-1
 * in [UnifiedDiffTest].
 */
@Tag("tier2")
class GoldenFilesTest {

    @TempDir
    lateinit var workDir: File

    @Test
    fun `verify mode passes when every golden matches`() {
        val dir = File(workDir, "golden").apply { mkdirs() }
        File(dir, "a.txt").writeText("hello\n")
        GoldenFiles.verifyAll(dir, mapOf("a.txt" to "hello"), updateMode = false)
    }

    @Test
    fun `verify mode fails with a unified diff on mismatch`() {
        val dir = File(workDir, "golden").apply { mkdirs() }
        File(dir, "a.txt").writeText("one\ntwo\n")
        try {
            GoldenFiles.verifyAll(dir, mapOf("a.txt" to "one\nTWO"), updateMode = false)
            fail("expected a golden mismatch failure")
        } catch (failure: AssertionError) {
            val message = failure.message ?: ""
            assertTrue("golden mismatch" in message, "message:\n$message")
            assertTrue("--- a.txt (expected)" in message, "message:\n$message")
            assertTrue("@@ " in message, "message:\n$message")
            assertTrue("-two" in message && "+TWO" in message, "message:\n$message")
        }
    }

    @Test
    fun `verify mode names the update flag on a missing golden`() {
        val dir = File(workDir, "golden").apply { mkdirs() }
        try {
            GoldenFiles.verifyAll(dir, mapOf("missing.txt" to "new"), updateMode = false)
            fail("expected a missing-golden failure")
        } catch (failure: AssertionError) {
            assertTrue("-Pgolden.update=true" in (failure.message ?: ""), "message:\n${failure.message}")
        }
    }

    @Test
    fun `an orphaned golden fails even when everything matches`() {
        val dir = File(workDir, "golden").apply { mkdirs() }
        File(dir, "a.txt").writeText("hello\n")
        File(dir, "stale.txt").writeText("nobody references me\n")
        try {
            GoldenFiles.verifyAll(dir, mapOf("a.txt" to "hello"), updateMode = false)
            fail("expected an orphan failure")
        } catch (failure: AssertionError) {
            assertTrue("stale.txt" in (failure.message ?: ""), "message:\n${failure.message}")
        }
    }

    @Test
    fun `update mode writes trailing newlines and lists every rewritten file`() {
        val dir = File(workDir, "golden")
        val printed = mutableListOf<String>()
        val rewritten = GoldenFiles.verifyAll(
            dir,
            mapOf("b.txt" to "bee", "a.txt" to "ay"),
            updateMode = true,
            out = printed::add,
        )
        assertEquals(listOf("a.txt", "b.txt"), rewritten)
        assertEquals("ay\n", File(dir, "a.txt").readText())
        assertEquals("bee\n", File(dir, "b.txt").readText())
        // The summary names every file it rewrote (T-054): a bare count hides
        // the blast radius a reviewer must read before committing.
        assertTrue(printed.size == 3, "printed:\n$printed")
        assertTrue(printed[0].contains("rewrote 2 file(s)"), "printed:\n$printed")
        assertTrue("a.txt" in printed[1] && "b.txt" in printed[2], "printed:\n$printed")
        // Update-then-verify round-trips: accepting output once makes it pass.
        GoldenFiles.verifyAll(dir, mapOf("a.txt" to "ay", "b.txt" to "bee"), updateMode = false)
    }

    @Test
    fun `update mode still fails on orphans so removals stay conscious`() {
        val dir = File(workDir, "golden").apply { mkdirs() }
        File(dir, "stale.txt").writeText("left behind\n")
        try {
            GoldenFiles.verifyAll(dir, mapOf("a.txt" to "ay"), updateMode = true)
            fail("expected an orphan failure")
        } catch (failure: AssertionError) {
            assertTrue("stale.txt" in (failure.message ?: ""), "message:\n${failure.message}")
        }
        // The non-orphan golden was still written before the orphan check fired.
        assertTrue(File(dir, "a.txt").isFile)
    }

    @Test
    fun `update mode is driven by the golden update system property`() {
        assertFalse(GoldenFiles.isUpdateMode(getProperty = { null }))
        assertTrue(GoldenFiles.isUpdateMode(getProperty = { if (it == "jdx.golden.update") "true" else null }))
        assertFalse(GoldenFiles.isUpdateMode(getProperty = { "false" }))
    }

    @Test
    fun `verify mode tolerates CRLF on disk (autocrlf checkout)`() {
        // A Windows `core.autocrlf=true` checkout reads LF-pinned goldens back
        // as CRLF — comparison normalises, so no phantom mismatch (issue #52).
        val dir = File(workDir, "golden").apply { mkdirs() }
        File(dir, "a.txt").writeBytes("hello\r\nworld\r\n".toByteArray(Charsets.UTF_8))
        GoldenFiles.verifyAll(dir, mapOf("a.txt" to "hello\nworld"), updateMode = false)
    }

    @Test
    fun `verify mode tolerates CRLF in actual text`() {
        val dir = File(workDir, "golden").apply { mkdirs() }
        File(dir, "a.txt").writeText("hello\nworld\n")
        GoldenFiles.verifyAll(dir, mapOf("a.txt" to "hello\r\nworld"), updateMode = false)
    }
}
