package dev.jdx.index.refs

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import dev.jdx.index.differential.Javap
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes

/**
 * Line-number differential for [ReferenceExtractor] (issue #8, TESTING.md §5.1):
 * every non-null [dev.jdx.core.model.ReferenceEdge.line] must appear in the
 * enclosing method's `LineNumberTable` as reported by `javap -l`. Stripped
 * classes (`-g:none` fixture) yield null lines without failing.
 *
 * Tier 2: shells out to `javap` and reads the fixture jar.
 */
@Tag("tier2")
class ReferenceLineDifferentialTest {

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `extracted lines are a subset of javap -l LineNumberTable`() {
        val javap = Javap.findJavap()
        assumeTrue(javap != null, "javap not on PATH / java.home")
        val bytes = buildClass("com/example/Lined", access = Opcodes.ACC_PUBLIC) {
            source("Lined.java")
            method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                line(10)
                invoke(Opcodes.INVOKEVIRTUAL, "com/example/Service", "ping", "()V")
                line(20)
                fieldInsn(Opcodes.GETFIELD, "com/example/Service", "count", "I")
                code(Opcodes.POP to null)
                line(30)
                typeInsn(Opcodes.CHECKCAST, "com/example/Service")
                code(Opcodes.POP to null, Opcodes.RETURN to null)
            }
        }
        val jar = ArtifactTestJars.craftJar(
            tempDir.resolve("lined.jar"),
            mapOf("com/example/Lined.class" to bytes),
        )
        val edges = ReferenceExtractor.extract(bytes)
        edges.mapNotNull { it.line }.toSet() shouldBe setOf(10, 20, 30)
        edges.forEach { it.sourceFile shouldBe "Lined.java" }

        val output = runJavapLines(javap!!, jar.toString(), "com.example.Lined")
        check(output != null) { "javap -l failed for com.example.Lined" }
        val table = parseLineNumberTable(output)
        table.keys shouldContain "run"
        val known = table.getValue("run")
        for (edge in edges) {
            val line = edge.line ?: error("expected a line on $edge")
            known shouldContain line
        }
    }

    @Test
    fun `NoDebug fixture edges carry null lines`() {
        assumeTrue(FixtureJars.binaryCorpusAvailable(), "fixture corpus missing")
        val binaryJar = ArtifactTestJars.binaryJar()
        val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/NoDebug.class")
        val edges = ReferenceExtractor.extract(bytes)
        // NoDebug has a body that mentions String via concatenation — whatever
        // edges survive, none may invent a line number from absent debug info.
        edges.forEach { edge ->
            edge.line shouldBe null
        }
        // And extracting still yields a list (possibly empty) — never throws.
        ReferenceExtractor.extract(bytes)
    }

    @Test
    fun `fixture corpus lines never invent entries absent from LineNumberTable`() {
        assumeTrue(FixtureJars.binaryCorpusAvailable(), "fixture corpus missing")
        val javap = Javap.findJavap()
        assumeTrue(javap != null, "javap not on PATH / java.home")
        val binaryJar = ArtifactTestJars.binaryJar()
        val failures = mutableListOf<String>()
        for (entry in listOf(
            "dev/jdx/fixtures/Generics.class",
            "dev/jdx/fixtures/Nesting.class",
            "dev/jdx/fixtures/VarargsAndModifiers.class",
        )) {
            val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, entry)
            val binary = entry.removeSuffix(".class").replace('/', '.')
            val output = runJavapLines(javap!!, binaryJar.absolutePath, binary) ?: continue
            val table = parseLineNumberTable(output)
            for (edge in ReferenceExtractor.extract(bytes)) {
                val line = edge.line ?: continue
                val known = table[edge.fromMember]
                if (known == null || line !in known) {
                    failures.add(
                        "$binary ${edge.fromMember} line=$line not in javap -l $known " +
                            "(kind=${edge.kind})",
                    )
                }
            }
        }
        failures shouldBe emptyList()
    }

    private fun runJavapLines(javap: String, classpath: String, binaryName: String): List<String>? {
        return try {
            val process = ProcessBuilder(javap, "-l", "-classpath", classpath, binaryName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val exit = process.waitFor()
            if (exit == 0) output.lines() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses `javap -l` into `methodName → set of lines`. Plain `javap -l`
     * (no `-s`) prints member lines like `public void run();` followed by a
     * `LineNumberTable:` block; constructors print as the FQN.
     *
     * Overloads that share a simple name merge their line sets — the
     * differential only asserts "extracted line ∈ some LineNumberTable of
     * this name", which is the safe direction (never invent a line javap
     * does not know).
     */
    private fun parseLineNumberTable(output: List<String>): Map<String, Set<Int>> {
        val result = linkedMapOf<String, MutableSet<Int>>()
        var current: String? = null
        var inTable = false
        for (raw in output) {
            val line = raw.trim()
            when {
                line.startsWith("LineNumberTable:") -> inTable = true
                inTable && line.startsWith("line ") -> {
                    val number = line.removePrefix("line ").substringBefore(':').trim().toIntOrNull()
                    if (number != null && current != null) {
                        result.getOrPut(current) { linkedSetOf() }.add(number)
                    }
                }
                line.startsWith("LocalVariableTable:") ||
                    line.startsWith("StackMapTable:") ||
                    line == "}" -> {
                    inTable = false
                }
                '(' in line && line.endsWith(";") && !line.startsWith("line ") -> {
                    inTable = false
                    current = normaliseMemberName(line)
                }
                line.endsWith("{") || line.startsWith("Compiled from") -> {
                    inTable = false
                    current = null
                }
            }
        }
        return result
    }

    private fun normaliseMemberName(line: String): String {
        val bare = line.removeSuffix(";").trim()
        if ('(' !in bare) return bare.substringAfterLast(' ')
        val nameToken = bare.substringBefore('(').trim().substringAfterLast(' ')
        return if ('.' in nameToken) "<init>" else nameToken
    }
}
