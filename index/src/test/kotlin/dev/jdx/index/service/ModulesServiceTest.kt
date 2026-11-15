package dev.jdx.index.service

import dev.jdx.index.service.JdxService.ModuleOptions
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
 * Service-level coverage for `jdx modules` (issue #25 module-info slice):
 * hits, miss, regex, limit, `--in`, and directive content.
 */
class ModulesServiceTest {

    @TempDir
    lateinit var temp: Path

    private fun roots(vararg jars: Path): RootsSpec =
        RootsSpec(jarSpecs = jars.map { it.toString() }, includeJdk = false)

    @Test
    fun `lists a matching module with its directives`() {
        val jar = ModulesCaseJars.buildModulesCaseJar(temp)
        val outcome = JdxService.modules("com.example.mod", roots(jar))
        val listing = (outcome as ServiceOutcome.ModuleList).listing
        listing.hits.map { it.name } shouldContainExactly listOf("com.example.mod")
        val hit = listing.hits.single()
        hit.open shouldBe true
        hit.version shouldBe "1.0"
        hit.requires.map { it.module } shouldContainExactly listOf("java.base", "java.logging")
        hit.exports.map { it.packageName } shouldContainExactly listOf("com.example.api", "com.example.spi")
        hit.uses shouldContainExactly listOf("com.example.Spi")
        hit.provides.single().providers shouldContainExactly listOf("com.example.Impl")
        listing.renderText() shouldContain "modules matching 'com.example.mod'"
        listing.renderText() shouldContain "  module com.example.mod open @1.0"
        listing.renderText() shouldContain "    provides com.example.Spi with com.example.Impl"
        listing.toJson("modules") shouldContain "\"name\":\"com.example.mod\""
    }

    @Test
    fun `empty pattern lists every module`() {
        val a = ModulesCaseJars.buildModulesCaseJar(temp)
        val b = ModulesCaseJars.buildOtherModulesJar(temp)
        val outcome = JdxService.modules(null, roots(a, b))
        val listing = (outcome as ServiceOutcome.ModuleList).listing
        listing.hits.map { it.name }.sorted() shouldContainExactly listOf(
            "com.example.mod",
            "com.other.lib",
        )
        listing.renderText() shouldContain "modules"
        listing.renderText() shouldNotContain "matching"
    }

    @Test
    fun `package and provider names also match the pattern`() {
        val jar = ModulesCaseJars.buildModulesCaseJar(temp)
        val byPackage = JdxService.modules("com.example.api", roots(jar))
        (byPackage as ServiceOutcome.ModuleList).listing.hits.map { it.name } shouldContainExactly
            listOf("com.example.mod")

        val byProvider = JdxService.modules("Impl", roots(jar))
        (byProvider as ServiceOutcome.ModuleList).listing.hits.map { it.name } shouldContainExactly
            listOf("com.example.mod")
    }

    @Test
    fun `miss exits 1 with a copy-pasteable detail`() {
        val jar = ModulesCaseJars.buildModulesCaseJar(temp)
        val outcome = JdxService.modules("no.such.mod", roots(jar))
        outcome.shouldBeInstanceOf<ServiceOutcome.Failure>()
        outcome.exitCode shouldBe 1
        outcome.renderText() shouldContain "no modules matching"
    }

    @Test
    fun `regex and ignore-case filter names`() {
        val jar = ModulesCaseJars.buildModulesCaseJar(temp)
        val regex = JdxService.modules(
            "com\\.example\\.mo.",
            roots(jar),
            ModuleOptions(regex = true),
        ) as ServiceOutcome.ModuleList
        regex.listing.hits.map { it.name } shouldContainExactly listOf("com.example.mod")

        val folded = JdxService.modules(
            "EXAMPLE.MOD",
            roots(jar),
            ModuleOptions(ignoreCase = true),
        ) as ServiceOutcome.ModuleList
        folded.listing.hits.size shouldBe 1

        val sensitive = JdxService.modules("EXAMPLE.MOD", roots(jar))
        sensitive.shouldBeInstanceOf<ServiceOutcome.Failure>()
    }

    @Test
    fun `limit truncates module rows`() {
        val a = ModulesCaseJars.buildModulesCaseJar(temp)
        val b = ModulesCaseJars.buildOtherModulesJar(temp)
        val limited = JdxService.modules(
            null,
            roots(a, b),
            ModuleOptions(limit = 1),
        ) as ServiceOutcome.ModuleList
        limited.listing.hits.size shouldBe 1
        limited.listing.truncation!!.shown shouldBe 1
        limited.listing.truncation!!.total shouldBe 2
    }

    @Test
    fun `in and exclude filter by artifact label`() {
        val a = ModulesCaseJars.buildModulesCaseJar(temp)
        val b = ModulesCaseJars.buildOtherModulesJar(temp)
        val onlyOther = JdxService.modules(
            null,
            roots(a, b),
            ModuleOptions(inArtifact = "other-modules.jar"),
        ) as ServiceOutcome.ModuleList
        onlyOther.listing.hits.map { it.name } shouldContainExactly listOf("com.other.lib")

        val withoutOther = JdxService.modules(
            null,
            roots(a, b),
            ModuleOptions(exclude = "other-modules.jar"),
        ) as ServiceOutcome.ModuleList
        withoutOther.listing.hits.map { it.name } shouldContainExactly listOf("com.example.mod")
    }

    @Test
    fun `jdk modules are listed when includeJdk is set`() {
        val outcome = JdxService.modules(
            "java.sql",
            RootsSpec(jarSpecs = emptyList(), includeJdk = true),
            ModuleOptions(limit = 20),
        )
        val listing = (outcome as ServiceOutcome.ModuleList).listing
        listing.hits.any { it.name == "java.sql" } shouldBe true
        val sql = listing.hits.first { it.name == "java.sql" }
        sql.exports.any { it.packageName == "java.sql" } shouldBe true
        sql.uses shouldContainExactly listOf("java.sql.Driver")
        listing.renderText() shouldContain "  module java.sql"
    }
}
