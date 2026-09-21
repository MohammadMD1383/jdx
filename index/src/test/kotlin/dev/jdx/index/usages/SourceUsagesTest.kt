package dev.jdx.index.usages

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure scanner laws for source-dir usages (T-031, tier 1): whole-word
 * matching, determinism, never-throws on hostile input, and the directory
 * scan contract (sorted, relative paths, source files only).
 */
class SourceUsagesTest {

    @Test
    fun `whole-word mentions match calls and reads but never substrings`() {
        SourceUsages.mentionsInLines(listOf("  greet(\"x\");"), "greet") shouldBe listOf(1)
        SourceUsages.mentionsInLines(listOf("  x.greet(y);"), "greet") shouldBe listOf(1)
        SourceUsages.mentionsInLines(listOf("  // greet the user"), "greet") shouldBe listOf(1)
        SourceUsages.mentionsInLines(listOf("  greetings();"), "greet") shouldBe emptyList()
        SourceUsages.mentionsInLines(listOf("  mygreet();"), "greet") shouldBe emptyList()
        SourceUsages.mentionsInLines(listOf("  greet2();"), "greet") shouldBe emptyList()
    }

    @Test
    fun `type simple names match imports and declarations`() {
        SourceUsages.mentionsInLines(listOf("import u.Lib;", "Lib x = new Lib();"), "Lib") shouldBe listOf(1, 2)
        SourceUsages.mentionsInLines(listOf("class Library {}"), "Lib") shouldBe emptyList()
    }

    @Test
    fun `blank or non-identifier names match nothing`() {
        SourceUsages.mentionsInLines(listOf("greet();"), "") shouldBe emptyList()
        SourceUsages.mentionsInLines(listOf("a-b c"), "a-b") shouldBe emptyList()
        SourceUsages.mentionsInLines(listOf("a b c"), "a b") shouldBe emptyList()
    }

    @Test
    fun `dollar names are identifier chars`() {
        SourceUsages.mentionsInLines(listOf("  A\$B\$x();"), "A\$B\$x") shouldBe listOf(1)
    }

    @Test
    fun `scan lists java and kt files only with relative paths in order`() {
        val root = Files.createTempDirectory("src-scan-test")
        val sub = Files.createDirectories(root.resolve("com/example"))
        Files.writeString(sub.resolve("App.java"), "import u.Lib;\nclass App { Lib x; }\n")
        Files.writeString(sub.resolve("Note.txt"), "Lib Lib Lib\n")
        Files.writeString(sub.resolve("Main.class"), "Lib\n")
        Files.writeString(sub.resolve("Helper.kt"), "val l = Lib()\n")
        val mentions = SourceUsages.scanSourceDir(root, "Lib")
        mentions.map { it.relativePath } shouldBe listOf(
            "com/example/App.java",
            "com/example/App.java",
            "com/example/Helper.kt",
        )
        mentions.map { it.line } shouldBe listOf(1, 2, 1)
    }

    @Test
    fun `scan of a missing dir is empty, never a throw`() {
        SourceUsages.scanSourceDir(Files.createTempDirectory("src-scan-missing").resolve("nope"), "Lib") shouldBe emptyList()
    }

    @Test
    fun `never throws on hostile lines`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(Arb.string(), 0..8), Arb.string(0..24)) { lines, name ->
            SourceUsages.mentionsInLines(lines, name)
        }
    }

    @Test
    fun `twice identical over generated lines`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(Arb.string(0..60), 0..8), Arb.string(0..12)) { lines, name ->
            val first = SourceUsages.mentionsInLines(lines, name)
            val second = SourceUsages.mentionsInLines(lines, name)
            assert(first == second) { "mentions not deterministic" }
        }
    }

    @Test
    fun `whole-word law on generated lines`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(Arb.string(0..60), 1..6)) { lines ->
            val hits = SourceUsages.mentionsInLines(lines, "greet")
            for (line in hits) {
                val text = lines[line - 1]
                assert(text.contains("greet")) { "hit without the name: '$text'" }
                assert(!text.contains("greetings") || text.contains("greet")) { "vacuous guard" }
            }
            // A line of exactly the name always hits; a line embedding it in a
            // longer identifier never does on its own.
            assert(SourceUsages.mentionsInLines(listOf("greet"), "greet") == listOf(1))
            assert(SourceUsages.mentionsInLines(listOf("agreet"), "greet") == emptyList<Int>())
            assert(SourceUsages.mentionsInLines(listOf("greetz"), "greet") == emptyList<Int>())
        }
    }

    @Test
    fun `scan is sorted by path then line`() = runBlocking<Unit> {
        checkAll(100, Arb.int(1..4)) { files ->
            val root = Files.createTempDirectory("src-scan-prop")
            for (i in 0 until files) {
                Files.writeString(root.resolve("F$i.java"), "Lib x$i;\nLib y$i;\n")
            }
            val mentions = SourceUsages.scanSourceDir(root, "Lib")
            assert(mentions.size == files * 2) { "expected 2 mentions per file, got ${mentions.size}" }
            val ordered = mentions.sortedWith(compareBy({ it.relativePath }, { it.line }))
            assert(mentions == ordered) { "scan not sorted" }
            assert(mentions == SourceUsages.scanSourceDir(root, "Lib")) { "scan not deterministic" }
        }
    }
}
