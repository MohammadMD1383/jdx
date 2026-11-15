package dev.jdx.index.artifact

import io.kotest.matchers.collections.shouldBeSortedBy
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative coverage for [ManifestEntries] (issue #25): sorted output and
 * never-throw on adversarial bytes / attribute sets.
 */
class ManifestEntriesPropertyTest {

    @Test
    fun `parseMainAttributes never throws and returns sorted names`(): Unit = runBlocking {
        val arbName = Arb.string(1..12, Codepoint.az()).map { it.replaceFirstChar(Char::uppercase) }
        val arbValue = Arb.string(0..24, Codepoint.az())
        val arbPair = Arb.bind(arbName, arbValue) { n, v -> n to v }
        val arbAttrs = Arb.list(arbPair, 0..8)
        checkAll(80, arbAttrs) { pairs ->
            // Distinct names so Manifest keeps every row (duplicate keys collapse).
            val unique = pairs.distinctBy { it.first.lowercase() }
            val bytes = buildString {
                append("Manifest-Version: 1.0\r\n")
                for ((name, value) in unique) {
                    append(name).append(": ").append(value).append("\r\n")
                }
                append("\r\n")
            }.toByteArray()
            val parsed = ManifestEntries.parseMainAttributes(bytes)
            parsed.shouldBeSortedBy { it.first }
            // Manifest-Version is always present; remaining names ⊆ what we wrote.
            val names = parsed.map { it.first }.toSet()
            names shouldBe (unique.map { it.first }.toSet() + "Manifest-Version")
        }
    }

    @Test
    fun `hostile bytes never throw`(): Unit = runBlocking {
        checkAll(60, Arb.list(Arb.string(0..64), 0..4)) { chunks ->
            val bytes = chunks.joinToString("").toByteArray()
            ManifestEntries.parseMainAttributes(bytes) // must not throw
            ManifestEntries.parseEntryAttributes(bytes) // must not throw
        }
    }

    @Test
    fun `parseEntryAttributes never throws and returns sorted rows`(): Unit = runBlocking {
        val arbSegment = Arb.string(1..8, Codepoint.az())
        val arbEntry = Arb.bind(arbSegment, arbSegment) { a, b -> "$a/$b.class" }
        val arbName = Arb.string(1..12, Codepoint.az()).map { it.replaceFirstChar(Char::uppercase) }
        val arbValue = Arb.string(0..16, Codepoint.az())
        val arbRow = Arb.bind(arbEntry, arbName, arbValue) { e, n, v -> Triple(e, n, v) }
        checkAll(60, Arb.list(arbRow, 0..6)) { rows ->
            val unique = rows.distinctBy { (e, n, _) -> e.lowercase() to n.lowercase() }
            val byEntry = unique.groupBy { it.first }
            val bytes = buildString {
                append("Manifest-Version: 1.0\r\n\r\n")
                for ((entry, attrs) in byEntry.toSortedMap()) {
                    append("Name: ").append(entry).append("\r\n")
                    for ((_, name, value) in attrs) {
                        append(name).append(": ").append(value).append("\r\n")
                    }
                    append("\r\n")
                }
            }.toByteArray()
            val parsed = ManifestEntries.parseEntryAttributes(bytes)
            parsed.shouldBeSortedBy { "${it.entry}\u0000${it.name}" }
            parsed.map { Triple(it.entry, it.name, it.value) }.toSet() shouldBe
                unique.map { Triple(it.first, it.second, it.third) }.toSet()
        }
    }
}
