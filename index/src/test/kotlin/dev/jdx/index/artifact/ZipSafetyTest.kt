package dev.jdx.index.artifact

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Tier-1 unit tests for [ZipSafety]: entry-name normalisation and decompression caps.
 * Pure string/byte logic — no disk, no jars — so it stays in the fast loop.
 */
class ZipSafetyTest {

    @Test
    fun `plain class paths pass through untouched`() {
        ZipSafety.normalizeEntryName("com/foo/Bar.class") shouldBe "com/foo/Bar.class"
    }

    @Test
    fun `dollar unicode and spaces survive normalisation`() {
        ZipSafety.normalizeEntryName("com/foo/Outer\$Inner.class") shouldBe "com/foo/Outer\$Inner.class"
        ZipSafety.normalizeEntryName("com/ünicode/Sp ace.class") shouldBe "com/ünicode/Sp ace.class"
    }

    @Test
    fun `backslashes become separators`() {
        ZipSafety.normalizeEntryName("com\\foo\\Bar.class") shouldBe "com/foo/Bar.class"
    }

    @Test
    fun `dot segments collapse`() {
        ZipSafety.normalizeEntryName("com/./foo//Bar.class") shouldBe "com/foo/Bar.class"
    }

    @Test
    fun `traversal entries are rejected`() {
        ZipSafety.normalizeEntryName("../../evil.class") shouldBe null
        ZipSafety.normalizeEntryName("com/../../evil.class") shouldBe null
        ZipSafety.normalizeEntryName("com/foo/../../../evil.class") shouldBe null
        ZipSafety.normalizeEntryName("..") shouldBe null
    }

    @Test
    fun `absolute entries are rejected`() {
        ZipSafety.normalizeEntryName("/etc/passwd") shouldBe null
        ZipSafety.normalizeEntryName("C:/Windows/evil.class") shouldBe null
    }

    @Test
    fun `empty and root-only names are rejected`() {
        ZipSafety.normalizeEntryName("") shouldBe null
        ZipSafety.normalizeEntryName("/") shouldBe null
        ZipSafety.normalizeEntryName("./") shouldBe null
    }

    @Test
    fun `normal names pass the declared-size guard`() {
        ZipSafety.checkDeclaredSize("com/foo/Bar.class", 41_000, "test.jar")
        ZipSafety.checkDeclaredSize("com/foo/Bar.class", -1, "test.jar")
    }

    @Test
    fun `oversized declared sizes throw a named error`() {
        val ex = shouldThrow<ArtifactReadException> {
            ZipSafety.checkDeclaredSize("evil.class", ZipSafety.MAX_SINGLE_ENTRY_BYTES + 1, "evil.jar")
        }
        (ex.message?.contains("evil.jar") ?: false) shouldBe true
    }

    @Test
    fun `oversized entry counts throw a named error`() {
        shouldThrow<ArtifactReadException> {
            ZipSafety.checkEntryCount(ZipSafety.MAX_ENTRY_COUNT + 1, "bomb.jar")
        }
    }

    @Test
    fun `oversized totals throw a named error`() {
        shouldThrow<ArtifactReadException> {
            ZipSafety.checkTotalSize(ZipSafety.MAX_TOTAL_UNCOMPRESSED_BYTES + 1, "bomb.jar")
        }
    }

    @Test
    fun `streams at exactly the cap pass, one byte over fails`() {
        val atCap = ByteArray(16)
        ZipSafety.readCapped(atCap.inputStream(), "ok.class", 16).size shouldBe 16
        shouldThrow<ArtifactReadException> {
            ZipSafety.readCapped(ByteArray(17).inputStream(), "big.class", 16)
        }
    }

    @Test
    fun `readCapped returns the exact bytes`() {
        val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        ZipSafety.readCapped(bytes.inputStream(), "A.class", 1024) shouldNotBe null
    }
}
