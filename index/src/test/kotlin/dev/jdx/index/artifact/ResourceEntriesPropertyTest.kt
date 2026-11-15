package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative coverage for [ResourceEntries] (issue #25): class files and
 * directories are never resources; every other non-empty path is.
 */
class ResourceEntriesPropertyTest {

    @Test
    fun `class files are never resources and non-class files always are`(): Unit = runBlocking {
        val arbSegment = Arb.string(1..8, Codepoint.az())
        val arbPath = Arb.bind(arbSegment, Arb.int(1..4)) { seg, depth ->
            (1..depth).joinToString("/") { seg }
        }
        checkAll(100, arbPath, Arb.boolean()) { base, asClass ->
            val path = if (asClass) "$base.class" else "$base.properties"
            ResourceEntries.isResourcePath(path) shouldBe !asClass
        }
    }

    @Test
    fun `directories and empty paths are never resources`(): Unit = runBlocking {
        val arbName = Arb.string(1..12, Codepoint.az())
        checkAll(60, arbName) { name ->
            ResourceEntries.isResourcePath("") shouldBe false
            ResourceEntries.isResourcePath("$name/") shouldBe false
        }
    }

    @Test
    fun `versioned non-class paths stay resources`(): Unit = runBlocking {
        val arbName = Arb.string(1..10, Codepoint.az())
        val arbExt = Arb.choice(
            Arb.constant("properties"),
            Arb.constant("xml"),
            Arb.constant("txt"),
        )
        checkAll(60, arbName, arbExt, Arb.int(9..21)) { name, ext, ver ->
            val path = "META-INF/versions/$ver/com/example/$name.$ext"
            ResourceEntries.isResourcePath(path) shouldBe true
            ResourceEntries.isResourcePath("META-INF/versions/$ver/com/example/$name.class") shouldBe false
        }
    }
}
