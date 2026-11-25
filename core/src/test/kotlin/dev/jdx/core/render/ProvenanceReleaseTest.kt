package dev.jdx.core.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A null release keeps the historical `source:` line and JSON object.
 * A versioned winner names the feature and nothing else moves.
 */
class ProvenanceReleaseTest {

    @Test
    fun `a base entry omits release from text and json`() {
        val provenance = Provenance(artifact = "app.jar", origin = Origin.BYTECODE)
        provenance.sourceLine() shouldBe "source: app.jar (bytecode)"
        provenance.toJson() shouldBe """{"artifact":"app.jar","origin":"bytecode"}"""
        provenance.toJson() shouldNotContain "release"
    }

    @Test
    fun `a versioned entry names its release after the origin`() {
        val provenance = Provenance(artifact = "app.jar", origin = Origin.BYTECODE, release = 17)
        provenance.sourceLine() shouldBe "source: app.jar (bytecode, release 17)"
        provenance.toJson() shouldBe """{"artifact":"app.jar","origin":"bytecode","release":17}"""
    }

    @Test
    fun `body source lines keep the reconstructed marker last`() {
        val provenance = Provenance(
            artifact = "app.jar",
            origin = Origin.DECOMPILED_VINEFLOWER,
            file = "Api.java",
            lineRange = 4..8,
            release = 11,
        )
        renderSourceLine(provenance, "Api.java", 4, 8) shouldBe
            "source: decompiled by vineflower from app.jar · Api.java:4-8 (release 11)  ⚠ reconstructed"
        renderSourceLine(provenance.copy(origin = Origin.LEXER), "Api.kt", 4, 8) shouldBe
            "source: lexer scan of app.jar · Api.kt:4-8 (release 11)  ⚠ brace-matched, not PSI"
        Provenance(artifact = "app.jar", origin = Origin.SOURCES, file = "Api.java", lineRange = 1..3)
            .let { renderSourceLine(it, "Api.java", 1, 3) } shouldBe
            "source: app.jar · Api.java:1-3"
    }
}
