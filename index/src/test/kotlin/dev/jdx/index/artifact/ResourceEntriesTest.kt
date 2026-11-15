package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the non-class resource path predicate and `--cat`
 * content classifier (issue #25).
 */
class ResourceEntriesTest {

    @Test
    fun `non-class files are resources including versioned and META-INF`() {
        ResourceEntries.isResourcePath("META-INF/MANIFEST.MF") shouldBe true
        ResourceEntries.isResourcePath("META-INF/services/com.foo.Spi") shouldBe true
        ResourceEntries.isResourcePath("com/foo/messages.properties") shouldBe true
        ResourceEntries.isResourcePath("META-INF/versions/11/com/foo/x.properties") shouldBe true
        ResourceEntries.isResourcePath("native/lib.so") shouldBe true
    }

    @Test
    fun `class files and directories are not resources`() {
        ResourceEntries.isResourcePath("com/foo/Bar.class") shouldBe false
        ResourceEntries.isResourcePath("module-info.class") shouldBe false
        ResourceEntries.isResourcePath("META-INF/versions/11/com/foo/Bar.class") shouldBe false
        ResourceEntries.isResourcePath("com/foo/") shouldBe false
        ResourceEntries.isResourcePath("") shouldBe false
    }

    @Test
    fun `UTF-8 text decodes and empty bytes are text`() {
        ResourceEntries.isBinary(ByteArray(0)) shouldBe false
        ResourceEntries.decodeText(ByteArray(0)) shouldBe ""
        ResourceEntries.isBinary("hello=world\n".toByteArray()) shouldBe false
        ResourceEntries.decodeText("hello=world\n".toByteArray()) shouldBe "hello=world\n"
    }

    @Test
    fun `NUL and malformed UTF-8 are binary`() {
        ResourceEntries.isBinary(byteArrayOf(0x00)) shouldBe true
        ResourceEntries.decodeText(byteArrayOf(0x00)) shouldBe null
        // Lone continuation byte — not well-formed UTF-8.
        ResourceEntries.isBinary(byteArrayOf(0x80.toByte())) shouldBe true
        ResourceEntries.decodeText(byteArrayOf(0x80.toByte())) shouldBe null
        // PNG-ish header with NUL later still binary via NUL probe.
        val pngish = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00)
        ResourceEntries.isBinary(pngish) shouldBe true
    }
}
