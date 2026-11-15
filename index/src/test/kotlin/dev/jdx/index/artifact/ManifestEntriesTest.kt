package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the jar-manifest grammar (issue #25 manifest slice).
 */
class ManifestEntriesTest {

    @Test
    fun `only the base META-INF MANIFEST path is recognised`() {
        ManifestEntries.isManifestPath("META-INF/MANIFEST.MF") shouldBe true
        ManifestEntries.isManifestPath("META-INF/manifest.mf") shouldBe false
        ManifestEntries.isManifestPath("META-INF/MANIFEST.MF/") shouldBe false
        ManifestEntries.isManifestPath("META-INF/versions/11/META-INF/MANIFEST.MF") shouldBe false
        ManifestEntries.isManifestPath("com/foo/Bar.class") shouldBe false
    }

    @Test
    fun `parseMainAttributes returns sorted name value pairs`() {
        val bytes = """
            Manifest-Version: 1.0
            Implementation-Title: Gson
            Main-Class: com.foo.Main
            Multi-Release: true

        """.trimIndent().replace("\n", "\r\n").toByteArray()
        ManifestEntries.parseMainAttributes(bytes) shouldBe listOf(
            "Implementation-Title" to "Gson",
            "Main-Class" to "com.foo.Main",
            "Manifest-Version" to "1.0",
            "Multi-Release" to "true",
        )
    }

    @Test
    fun `parseMainAttributes degrades on empty or corrupt bytes`() {
        ManifestEntries.parseMainAttributes(byteArrayOf()) shouldBe emptyList()
        ManifestEntries.parseMainAttributes(byteArrayOf(0x00, 0x01, 0x02)) shouldBe emptyList()
    }

    @Test
    fun `continuation lines are folded into one value`() {
        val bytes = (
            "Manifest-Version: 1.0\r\n" +
                "Long-Name: first\r\n" +
                " second\r\n" +
                "\r\n"
            ).toByteArray()
        val attrs = ManifestEntries.parseMainAttributes(bytes).toMap()
        attrs["Long-Name"] shouldBe "firstsecond"
    }
}
