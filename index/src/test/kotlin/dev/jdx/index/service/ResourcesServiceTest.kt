package dev.jdx.index.service

import dev.jdx.index.service.JdxService.ResourceOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Service-level coverage for `jdx resources` (issue #25): hits, miss, regex,
 * limit, `--in`, versioned paths listed as stored, and class files excluded.
 */
class ResourcesServiceTest {

    @TempDir
    lateinit var temp: Path

    private fun roots(vararg jars: Path): RootsSpec =
        RootsSpec(jarSpecs = jars.map { it.toString() }, includeJdk = false)

    @Test
    fun `lists paths matching a substring pattern`() {
        val jar = ResourcesCaseJars.buildResourcesCaseJar(temp)
        val outcome = JdxService.resources("messages.properties", roots(jar))
        val listing = (outcome as ServiceOutcome.ResourceList).listing
        listing.hits.map { it.path } shouldContainExactly listOf(
            "META-INF/versions/11/com/example/messages.properties",
            "com/example/messages.properties",
        )
        listing.renderText() shouldContain "resources matching 'messages.properties'"
        listing.toJson("resources") shouldContain "\"path\":\"com/example/messages.properties\""
        listing.renderText() shouldNotContain ".class"
    }

    @Test
    fun `empty pattern lists every non-class resource including versioned`() {
        val jar = ResourcesCaseJars.buildResourcesCaseJar(temp)
        val outcome = JdxService.resources(null, roots(jar))
        val listing = (outcome as ServiceOutcome.ResourceList).listing
        listing.hits.map { it.path } shouldContainExactly listOf(
            "META-INF/MANIFEST.MF",
            "META-INF/services/com.example.Spi",
            "META-INF/versions/11/com/example/messages.properties",
            "com/example/messages.properties",
        )
        listing.renderText() shouldContain "resources"
        listing.renderText() shouldNotContain "matching"
        listing.renderText() shouldNotContain "Main.class"
    }

    @Test
    fun `miss exits 1 with a copy-pasteable detail`() {
        val jar = ResourcesCaseJars.buildResourcesCaseJar(temp)
        val outcome = JdxService.resources("no-such-resource", roots(jar))
        outcome.shouldBeInstanceOf<ServiceOutcome.Failure>()
        outcome.exitCode shouldBe 1
        outcome.renderText() shouldContain "no resources matching"
    }

    @Test
    fun `regex and ignore-case filter paths`() {
        val jar = ResourcesCaseJars.buildResourcesCaseJar(temp)
        val regex = JdxService.resources(
            "messages\\.propertie.",
            roots(jar),
            ResourceOptions(regex = true),
        ) as ServiceOutcome.ResourceList
        regex.listing.hits.size shouldBe 2

        val folded = JdxService.resources(
            "MESSAGES.PROPERTIES",
            roots(jar),
            ResourceOptions(ignoreCase = true),
        ) as ServiceOutcome.ResourceList
        folded.listing.hits.size shouldBe 2

        val sensitive = JdxService.resources("MESSAGES.PROPERTIES", roots(jar))
        sensitive.shouldBeInstanceOf<ServiceOutcome.Failure>()
    }

    @Test
    fun `limit truncates resource rows`() {
        val jar = ResourcesCaseJars.buildResourcesCaseJar(temp)
        val limited = JdxService.resources(
            null,
            roots(jar),
            ResourceOptions(limit = 2),
        ) as ServiceOutcome.ResourceList
        limited.listing.hits.size shouldBe 2
        limited.listing.renderText() shouldContain "2 of 4 resources shown"
    }

    @Test
    fun `in and exclude filter by artifact label`() {
        val a = ResourcesCaseJars.buildResourcesCaseJar(temp)
        val b = ResourcesCaseJars.buildOtherResourcesJar(temp)
        val onlyOther = JdxService.resources(
            null,
            roots(a, b),
            ResourceOptions(inArtifact = "other-resources.jar"),
        ) as ServiceOutcome.ResourceList
        onlyOther.listing.hits.map { it.artifact }.distinct() shouldContainExactly listOf(
            "other-resources.jar",
        )

        val withoutOther = JdxService.resources(
            null,
            roots(a, b),
            ResourceOptions(exclude = "other-resources.jar"),
        ) as ServiceOutcome.ResourceList
        withoutOther.listing.hits.any { it.artifact == "other-resources.jar" } shouldBe false
    }

    @Test
    fun `class-only jar is a miss for empty pattern`() {
        val jar = ResourcesCaseJars.buildClassOnlyJar(temp)
        val outcome = JdxService.resources(null, roots(jar))
        outcome.shouldBeInstanceOf<ServiceOutcome.Failure>()
        outcome.exitCode shouldBe 1
    }

    @Test
    fun `directory roots expose non-class resources too`() {
        val dir = temp.resolve("classes")
        val meta = dir.resolve("META-INF")
        java.nio.file.Files.createDirectories(meta)
        java.nio.file.Files.writeString(meta.resolve("app.properties"), "k=v\n")
        java.nio.file.Files.createDirectories(dir.resolve("com/example"))
        java.nio.file.Files.write(
            dir.resolve("com/example/Main.class"),
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
        )
        val outcome = JdxService.resources("app.properties", roots(dir))
        val listing = (outcome as ServiceOutcome.ResourceList).listing
        listing.hits.map { it.path } shouldContainExactly listOf("META-INF/app.properties")
    }
}
