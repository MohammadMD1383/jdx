package dev.jdx.core.render

import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.kotlinViewKey
import dev.jdx.core.resolve.publicMethod
import dev.jdx.core.resolve.testClassType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Kotlin member views over JVM members (T-077): `@JvmName` renames, mangled
 * `internal` demangling, and `suspend` Continuation-stripping with the
 * `suspend` keyword and the metadata return type.
 *
 * Views are plain data produced by `index` from `@Metadata`; `core` only
 * applies them. A `null` view is the JVM projection, byte-identical to today.
 */
class KotlinViewTest {

    private val declaring = testClassType("dev.jdx.fixtures.KotlinMembers")

    @Test
    fun `a rename view replaces the JVM name`() {
        val member = publicMethod("renamedForJvm", "(I)I")
        val view = KotlinMethodView(displayName = "originalName")
        SignatureLines.methodLine(member, kotlinView = view) shouldBe
            "public int originalName(int arg0)"
    }

    @Test
    fun `a demangled internal name replaces the JVM name`() {
        val member = publicMethod("internalHelper\$testfixtures", "()V")
        val view = KotlinMethodView(displayName = "internalHelper")
        SignatureLines.methodLine(member, kotlinView = view) shouldBe
            "public void internalHelper()"
    }

    @Test
    fun `a suspend view strips the Continuation and marks suspend with the metadata return`() {
        val member = publicMethod(
            "fetch",
            "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        ).copy(parameterNames = listOf("id", "\$completion"))
        val view = KotlinMethodView(
            displayName = "fetch",
            stripTrailingContinuation = true,
            displayReturn = "java.lang.String",
            markSuspend = true,
        )
        SignatureLines.methodLine(member, kotlinView = view) shouldBe
            "public suspend java.lang.String fetch(java.lang.String id)"
    }

    @Test
    fun `stripping is ignored when the tail is not a Continuation`() {
        val member = publicMethod("add", "(II)I").copy(
            parameterNames = listOf("a", "b"),
        )
        val view = KotlinMethodView(
            displayName = "add",
            stripTrailingContinuation = true,
        )
        SignatureLines.methodLine(member, kotlinView = view) shouldBe
            "public int add(int a, int b)"
    }

    @Test
    fun `a null view renders the JVM projection`() {
        val member = publicMethod(
            "fetch",
            "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        ).copy(parameterNames = listOf("id", "\$completion"))
        SignatureLines.methodLine(member, kotlinView = null) shouldBe
            "public java.lang.Object fetch(java.lang.String id, " +
            "kotlin.coroutines.Continuation \$completion)"
    }

    @Test
    fun `a view never renames a constructor`() {
        val member = publicMethod("<init>", "()V")
        val view = KotlinMethodView(displayName = "KotlinMembers")
        SignatureLines.methodLine(member, declaringSimpleName = "KotlinMembers", kotlinView = view) shouldBe
            "public KotlinMembers()"
    }

    @Test
    fun `the view key joins the JVM name and descriptor`() {
        kotlinViewKey("renamedForJvm", "(I)I") shouldBe "renamedForJvm(I)I"
    }

    @Test
    fun `refs use the Kotlin name`() {
        val member = publicMethod("renamedForJvm", "(I)I")
        val view = KotlinMethodView(displayName = "originalName")
        methodRefString(declaring, member, view, disambiguateReturn = false) shouldBe
            "dev.jdx.fixtures.KotlinMembers#originalName(int)"
    }

    @Test
    fun `refs use stripped parameters for suspend`() {
        val member = publicMethod(
            "fetch",
            "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        )
        val view = KotlinMethodView(
            displayName = "fetch",
            stripTrailingContinuation = true,
            displayReturn = "java.lang.String",
            markSuspend = true,
        )
        methodRefString(declaring, member, view, disambiguateReturn = false) shouldBe
            "dev.jdx.fixtures.KotlinMembers#fetch(java.lang.String)"
    }

    @Test
    fun `disambiguated refs suffix the display return`() {
        val member = publicMethod("renamedForJvm", "(I)I")
        val view = KotlinMethodView(displayName = "originalName", displayReturn = "int")
        methodRefString(declaring, member, view, disambiguateReturn = true) shouldBe
            "dev.jdx.fixtures.KotlinMembers#originalName(int):int"
    }
}
