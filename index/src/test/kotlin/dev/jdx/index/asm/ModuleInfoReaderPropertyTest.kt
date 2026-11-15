package dev.jdx.index.asm

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes

/**
 * Generative family for [ModuleInfoReader] (issue #25): craft → parse
 * round-trips keep module name, open flag, and sorted requires/exports.
 */
class ModuleInfoReaderPropertyTest {

    @Test
    fun `crafted modules round trip name open and requires`(): Unit = runBlocking {
        val segment = Arb.string(1..6, Codepoint.az())
        checkAll(100, segment, Arb.boolean(), Arb.list(segment, 0..3)) { head, open, reqSegs ->
            val name = "m.$head"
            val requires = (listOf("java.base") + reqSegs.map { "req.$it" }.distinct())
                .mapIndexed { i, module ->
                    val flags = if (i == 0) Opcodes.ACC_MANDATED else 0
                    Triple(module, flags, null as String?)
                }
            val bytes = craftModuleInfo(
                name = name,
                open = open,
                requires = requires,
                exports = listOf("pkg/$head" to emptyArray()),
            )
            val parsed = ModuleInfoReader.read(bytes)!!
            parsed.name shouldBe name
            parsed.open shouldBe open
            parsed.requires.map { it.module } shouldBe requires.map { it.first }.sorted()
            parsed.exports.single().packageName shouldBe "pkg.$head"
        }
    }
}
