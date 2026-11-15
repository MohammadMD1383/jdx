package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the modules listing (issue #25 module-info slice):
 * empty/miss text, artifact grouping, directives, truncation, text⊆JSON.
 */
class ModulesTest {

    @Test
    fun `empty listing with a pattern renders the miss line`() {
        val listing = buildModuleListing("java.sql", emptyList())
        listing.renderText() shouldBe "no modules matching 'java.sql'"
        listing.toJson("modules") shouldContain "\"ok\":true"
        listing.toJson("modules") shouldContain "\"modules\":[]"
    }

    @Test
    fun `empty listing with no pattern says no modules found`() {
        val listing = buildModuleListing("", emptyList())
        listing.renderText() shouldBe "no modules found"
    }

    @Test
    fun `hits group by artifact and emit directives`() {
        val hits = listOf(
            ModuleHit(
                name = "com.foo",
                artifact = "lib.jar",
                open = true,
                version = "1.0",
                requires = listOf(
                    ModuleRequire("java.base", listOf("mandated")),
                    ModuleRequire("java.logging", listOf("transitive")),
                ),
                exports = listOf(ModulePackageTarget("com.foo.api")),
                opens = listOf(ModulePackageTarget("com.foo.spi", listOf("com.friend"))),
                uses = listOf("com.foo.Spi"),
                provides = listOf(ModuleProvide("com.foo.Spi", listOf("com.foo.Impl"))),
            ),
            ModuleHit(name = "com.bar", artifact = "other.jar"),
        )
        val listing = buildModuleListing("foo", hits)
        val text = listing.renderText()
        text shouldContain "modules matching 'foo'"
        text shouldContain "lib.jar (1)"
        text shouldContain "other.jar (1)"
        text shouldContain "  module com.foo open @1.0"
        text shouldContain "    requires java.base mandated"
        text shouldContain "    requires java.logging transitive"
        text shouldContain "    exports com.foo.api"
        text shouldContain "    opens com.foo.spi to com.friend"
        text shouldContain "    uses com.foo.Spi"
        text shouldContain "    provides com.foo.Spi with com.foo.Impl"
        text shouldContain "  module com.bar"
        val json = listing.toJson("modules")
        json shouldContain "\"command\":\"modules\""
        json shouldContain "\"pattern\":\"foo\""
        json shouldContain "\"name\":\"com.foo\""
        json shouldContain "\"open\":true"
        json shouldContain "\"version\":\"1.0\""
        json shouldContain "\"module\":\"java.base\""
        json shouldContain "\"package\":\"com.foo.api\""
        json shouldContain "\"service\":\"com.foo.Spi\""
        for (hit in hits) {
            json shouldContain hit.name
            json shouldContain hit.artifact
        }
    }

    @Test
    fun `all-modules header drops the matching clause`() {
        val hits = listOf(ModuleHit("com.foo", "lib.jar"))
        val text = buildModuleListing("", hits).renderText()
        text shouldContain "modules"
        text shouldNotContain "matching"
    }

    @Test
    fun `truncation keeps the prefix and names --limit`() {
        val hits = (1..5).map { i -> ModuleHit("com.foo.m$i", "lib.jar") }
        val listing = buildModuleListing("foo", hits, limit = 2)
        listing.hits.size shouldBe 2
        listing.truncation shouldNotBe null
        val text = listing.renderText()
        text shouldContain "2 of 5 modules shown (--limit 5 to see more)"
        text shouldContain "com.foo.m1"
        text shouldContain "com.foo.m2"
        text shouldNotContain "com.foo.m3"
        listing.toJson("modules") shouldContain "\"shown\":2"
        listing.toJson("modules") shouldContain "\"total\":5"
    }
}
