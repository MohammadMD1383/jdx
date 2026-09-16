package dev.jdx.core.render

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.resolve.publicField
import dev.jdx.core.resolve.publicMethod
import dev.jdx.core.resolve.testClass
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The class card behind `jdx show` (T-011): kind, modifiers, supertypes, member
 * counts, warnings, provenance and a `next:` hint — in text and in JSON alike.
 */
class ClassCardTest {

    private val point = testClass(
        binary = "com.example.Point",
        superclass = "java.lang.Object",
        interfaces = listOf("java.io.Serializable"),
        fields = listOf(publicField("y", "I"), publicField("x", "I")),
        methods = listOf(
            publicMethod("<init>", "(II)V"),
            publicMethod("getX", "()I"),
        ),
    )

    private fun cardOf() = buildClassCard(
        target = point,
        provenance = listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE)),
    )

    @Test
    fun `show header names the kind and the binary name`() {
        cardOf().renderText().lines().first() shouldBe "class com.example.Point"
    }

    @Test
    fun `show prints supertypes and member counts`() {
        val text = cardOf().renderText()
        text shouldContain "extends java.lang.Object"
        text shouldContain "implements java.io.Serializable"
        text shouldContain "members: 1 constructor, 1 method, 2 fields"
    }

    @Test
    fun `show ends with a copy-pasteable next hint`() {
        cardOf().renderText() shouldContain "next: jdx members com.example.Point --inherited"
    }

    @Test
    fun `show marks deprecated types`() {
        val text = buildClassCard(
            target = point.copy(deprecated = true),
            provenance = emptyList(),
        ).renderText()
        text shouldContain "deprecated"
    }

    @Test
    fun `show omits the extends line for object and bare interfaces`() {
        val objectText = buildClassCard(
            target = testClass(binary = "java.lang.Object"),
            provenance = emptyList(),
        ).renderText()
        objectText shouldNotContain "extends "

        val ifaceText = buildClassCard(
            target = testClass(binary = "com.example.Marker").copy(kind = TypeKind.INTERFACE),
            provenance = emptyList(),
        ).renderText()
        ifaceText shouldNotContain "extends "
    }

    @Test
    fun `show renders warnings and provenance`() {
        val text = buildClassCard(
            target = point,
            provenance = listOf(Provenance(artifact = "fixture-corpus.jar", origin = Origin.BYTECODE)),
            warnings = listOf(
                Warning(WarningCode.DUPLICATE_FQN, "same FQN in two artifacts", "com.example.Point"),
            ),
        ).renderText()
        text shouldContain "warning DUPLICATE_FQN"
        text shouldContain "source: fixture-corpus.jar (bytecode)"
    }

    @Test
    fun `show text is deterministic`() {
        cardOf().renderText() shouldBe cardOf().renderText()
    }

    @Test
    fun `show json carries every text fact`() {
        val card = cardOf()
        val json = card.toJson(command = "show")
        json shouldContain "\"command\":\"show\""
        json shouldContain "\"query\":\"com.example.Point\""
        json shouldContain "\"kind\":\"class\""
        json shouldContain "java.lang.Object"
        json shouldContain "java.io.Serializable"
        json shouldContain "\"constructors\":1"
        json shouldContain "\"artifact\":\"app.jar\""
    }

    @Test
    fun `private members count as declared but print in the modifier line`() {
        val withPrivate = testClass(
            binary = "com.example.Encapsulated",
            superclass = "java.lang.Object",
            fields = listOf(
                publicField("open", "I").copy(access = Access.of(AccessFlag.PRIVATE)),
            ),
            methods = listOf(publicMethod("get", "()I")),
        )
        val text = buildClassCard(target = withPrivate, provenance = emptyList()).renderText()
        text shouldContain "members: 0 constructors, 1 method, 1 field"
    }
}
