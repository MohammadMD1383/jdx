package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative family for ServiceLoader file parsing (issue #25): comments and
 * whitespace never invent provider names; round-trip of clean names is stable.
 */
class ServiceEntriesPropertyTest {

    @Test
    fun `comment only and blank lines never yield providers`(): Unit = runBlocking {
        checkAll(200, Arb.list(Arb.string(0..40, Codepoint.az()), 0..20)) { noise ->
            val bytes = noise.joinToString("\n") { line ->
                when (line.hashCode() and 3) {
                    0 -> "# $line"
                    1 -> "   "
                    2 -> ""
                    else -> "#$line"
                }
            }.toByteArray()
            ServiceEntries.parseProviders(bytes) shouldBe emptyList()
        }
    }

    @Test
    fun `clean binary names round trip through the parser`(): Unit = runBlocking {
        val segment = Arb.string(1..8, Codepoint.az())
        checkAll(200, Arb.list(segment, 1..4)) { segments ->
            val name = segments.joinToString(".")
            if (ServiceEntries.isBinaryClassName(name)) {
                ServiceEntries.parseProviders("$name\n".toByteArray()) shouldBe listOf(name)
                ServiceEntries.parseProviders("  $name  # note\n".toByteArray()) shouldBe listOf(name)
            }
        }
    }
}
