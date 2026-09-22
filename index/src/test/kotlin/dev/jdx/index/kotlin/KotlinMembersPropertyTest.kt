package dev.jdx.index.kotlin

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.metadata.KmClass
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative tests for [KotlinMembers] (T-077, TESTING.md §4): whatever shapes
 * the fuzzer invents for metadata containers, mapping never throws and stays
 * keyed on its inputs. Fixture truth decodes in `KotlinClassesTest` (tier 2).
 */
class KotlinMembersPropertyTest {

    private val nameArb: Arb<String> = Arb.string(0..24).filter { it.none { c -> c == '(' || c == ')' } }

    private val descriptorArb: Arb<String> = Arb.string(0..48)

    private fun functionArb(): Arb<KmFunction> = Arb.bind(nameArb, nameArb, descriptorArb) { kotlin, jvm, desc ->
        KmFunction(kotlin).apply {
            signature = JvmMethodSignature(jvm, desc)
            isSuspend = kotlin.length % 2 == 0
            returnType = KmType()
        }
    }

    @Test
    fun `mapping never throws on arbitrary functions`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(functionArb(), 0..8)) { functions ->
            val kmClass = KmClass().apply { this.functions.addAll(functions) }
            // The assertion is the absence of a throw: any map (empty or not) is fine.
            KotlinMembers.viewsFor(kmClass).size
        }
    }

    @Test
    fun `every view key joins one of its inputs`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(functionArb(), 0..8)) { functions ->
            val kmClass = KmClass().apply { this.functions.addAll(functions) }
            val keys = functions.map { (it.signature?.name ?: "") + (it.signature?.descriptor ?: "") }.toSet()
            for (key in KotlinMembers.viewsFor(kmClass).keys) {
                check(key in keys) { "view key $key was not built from an input signature" }
            }
        }
    }

    @Test
    fun `rendering never throws on arbitrary classifiers`() = runBlocking<Unit> {
        val classifierArb = Arb.string(0..48)
        checkAll(1_000, classifierArb) { name ->
            val type = KmType().apply {
                classifier = kotlin.metadata.KmClassifier.Class(name)
            }
            KotlinMembers.renderType(type)?.length
        }
    }

    @Test
    fun `mapping is deterministic`() = runBlocking<Unit> {
        checkAll(200, Arb.list(functionArb(), 0..8)) { functions ->
            val first = KmClass().apply { this.functions.addAll(functions) }
            val second = KmClass().apply {
                this.functions.addAll(
                    functions.map { original ->
                        KmFunction(original.name).apply {
                            signature = original.signature?.let {
                                JvmMethodSignature(it.name, it.descriptor)
                            }
                            isSuspend = original.isSuspend
                            returnType = KmType()
                        }
                    },
                )
            }
            KotlinMembers.viewsFor(first) shouldBe KotlinMembers.viewsFor(second)
        }
    }

    @Test
    fun `properties mapping never throws on arbitrary names`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(nameArb, 0..8)) { names ->
            val kmClass = KmClass().apply {
                properties.addAll(
                    names.map { name ->
                        kotlin.metadata.KmProperty(name).apply {
                            if (name.isNotEmpty()) {
                                getterSignature = JvmMethodSignature("get$name", "()V")
                            }
                        }
                    },
                )
            }
            // The assertion is the absence of a throw: any map is fine.
            KotlinMembers.propertiesFor(kmClass).properties.size
        }
    }
}
