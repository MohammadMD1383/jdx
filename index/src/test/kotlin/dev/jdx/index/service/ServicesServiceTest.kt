package dev.jdx.index.service

import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOptions
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
 * Service-level coverage for `jdx services` (issue #25): hits, miss, regex,
 * limit, `--in`, and ignore of versioned META-INF/services copies.
 */
class ServicesServiceTest {

    @TempDir
    lateinit var temp: Path

    private fun roots(vararg jars: Path): RootsSpec =
        RootsSpec(jarSpecs = jars.map { it.toString() }, includeJdk = false)

    @Test
    fun `lists providers for a matching service interface`() {
        val jar = ServicesCaseJars.buildServicesCaseJar(temp)
        val outcome = JdxService.services("com.example.Spi", roots(jar))
        val listing = (outcome as ServiceOutcome.ServiceList).listing
        listing.hits.map { it.provider } shouldContainExactly listOf("com.example.Alpha", "com.example.Beta")
        listing.renderText() shouldContain "services matching 'com.example.Spi'"
        listing.renderText() shouldContain "service com.example.Spi"
        listing.renderText() shouldContain "provider com.example.Alpha"
        listing.toJson("services") shouldContain "\"provider\":\"com.example.Alpha\""
        listing.renderText() shouldNotContain "VersionedOnly"
    }

    @Test
    fun `empty pattern lists every registration`() {
        val jar = ServicesCaseJars.buildServicesCaseJar(temp)
        val outcome = JdxService.services(null, roots(jar))
        val listing = (outcome as ServiceOutcome.ServiceList).listing
        listing.hits.map { "${it.service}->${it.provider}" }.sorted() shouldContainExactly listOf(
            "com.example.Other->com.example.Gamma",
            "com.example.Spi->com.example.Alpha",
            "com.example.Spi->com.example.Beta",
        )
        listing.renderText() shouldContain "services"
        listing.renderText() shouldNotContain "matching"
    }

    @Test
    fun `provider name also matches the pattern`() {
        val jar = ServicesCaseJars.buildServicesCaseJar(temp)
        val outcome = JdxService.services("Alpha", roots(jar))
        val listing = (outcome as ServiceOutcome.ServiceList).listing
        listing.hits.map { it.provider } shouldContainExactly listOf("com.example.Alpha")
    }

    @Test
    fun `miss exits 1 with a copy-pasteable detail`() {
        val jar = ServicesCaseJars.buildServicesCaseJar(temp)
        val outcome = JdxService.services("no.such.Spi", roots(jar))
        outcome.shouldBeInstanceOf<ServiceOutcome.Failure>()
        outcome.exitCode shouldBe 1
        outcome.renderText() shouldContain "no services matching"
    }

    @Test
    fun `regex and ignore-case filter names`() {
        val jar = ServicesCaseJars.buildServicesCaseJar(temp)
        val regex = JdxService.services(
            "com\\.example\\.Sp.",
            roots(jar),
            ServiceOptions(regex = true),
        ) as ServiceOutcome.ServiceList
        regex.listing.hits.map { it.service }.distinct() shouldContainExactly listOf("com.example.Spi")

        val folded = JdxService.services(
            "SPI",
            roots(jar),
            ServiceOptions(ignoreCase = true),
        ) as ServiceOutcome.ServiceList
        folded.listing.hits.size shouldBe 2

        val sensitive = JdxService.services("SPI", roots(jar))
        sensitive.shouldBeInstanceOf<ServiceOutcome.Failure>()
    }

    @Test
    fun `limit truncates provider rows`() {
        val jar = ServicesCaseJars.buildServicesCaseJar(temp)
        val limited = JdxService.services(
            null,
            roots(jar),
            ServiceOptions(limit = 1),
        ) as ServiceOutcome.ServiceList
        limited.listing.hits.size shouldBe 1
        limited.listing.renderText() shouldContain "1 of 3 providers shown"
    }

    @Test
    fun `in and exclude filter by artifact label`() {
        val a = ServicesCaseJars.buildServicesCaseJar(temp)
        val b = ServicesCaseJars.buildOtherServicesJar(temp)
        val onlyOther = JdxService.services(
            null,
            roots(a, b),
            ServiceOptions(inArtifact = "other-services.jar"),
        ) as ServiceOutcome.ServiceList
        onlyOther.listing.hits.map { it.artifact }.distinct() shouldContainExactly listOf("other-services.jar")

        val withoutOther = JdxService.services(
            null,
            roots(a, b),
            ServiceOptions(exclude = "other-services.jar"),
        ) as ServiceOutcome.ServiceList
        withoutOther.listing.hits.any { it.artifact == "other-services.jar" } shouldBe false
    }

    @Test
    fun `directory roots expose META-INF services too`() {
        val dir = temp.resolve("classes")
        val services = dir.resolve("META-INF/services")
        java.nio.file.Files.createDirectories(services)
        java.nio.file.Files.writeString(services.resolve("com.dir.Spi"), "com.dir.Impl\n")
        val outcome = JdxService.services("com.dir.Spi", roots(dir))
        val listing = (outcome as ServiceOutcome.ServiceList).listing
        listing.hits.map { it.provider } shouldContainExactly listOf("com.dir.Impl")
    }
}
