package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the ServiceLoader file grammar (issue #25).
 */
class ServiceEntriesTest {

    @Test
    fun `base META-INF services paths are recognised`() {
        ServiceEntries.isServicePath("META-INF/services/com.foo.Spi") shouldBe true
        ServiceEntries.serviceName("META-INF/services/com.foo.Spi") shouldBe "com.foo.Spi"
        ServiceEntries.isServicePath("META-INF/services/") shouldBe false
        ServiceEntries.isServicePath("META-INF/services/com/foo/Spi") shouldBe false
        ServiceEntries.isServicePath("META-INF/versions/11/META-INF/services/com.foo.Spi") shouldBe false
        ServiceEntries.isServicePath("com/foo/Bar.class") shouldBe false
    }

    @Test
    fun `parseProviders strips comments whitespace and duplicates`() {
        val bytes = """
            # comment
            com.foo.A
            com.foo.B  # trailing comment
              com.foo.A
            com.foo.C

            ../evil.Path
            123Bad
        """.trimIndent().toByteArray()
        ServiceEntries.parseProviders(bytes) shouldBe listOf("com.foo.A", "com.foo.B", "com.foo.C")
    }

    @Test
    fun `parseProviders accepts dollar nested and underscore names`() {
        val bytes = "com.foo.Outer${'$'}Inner\n_Pkg.Name\n".toByteArray()
        ServiceEntries.parseProviders(bytes) shouldBe listOf("com.foo.Outer${'$'}Inner", "_Pkg.Name")
    }
}
