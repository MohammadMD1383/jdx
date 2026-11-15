package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the non-class resource path predicate (issue #25
 * resource-listing slice).
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
}
