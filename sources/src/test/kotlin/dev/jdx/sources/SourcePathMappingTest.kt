package dev.jdx.sources

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure `binaryName → source file` mapping rules (T-071). No disk, no jars —
 * the tier-1 half of the acceptance. Disk behaviour lives in `SourceRootTest`
 * (tier 2); generating coverage lives in the properties below.
 */
class SourcePathMappingTest {

    @Test
    fun `nested classes map to the outer java file first`() {
        sourceCandidatesFor("dev.jdx.fixtures.Nesting\$Inner") shouldBe listOf(
            "dev/jdx/fixtures/Nesting.java",
            "dev/jdx/fixtures/Nesting.kt",
        )
    }

    @Test
    fun `plain classes offer java before kotlin`() {
        sourceCandidatesFor("com.example.Foo") shouldBe listOf(
            "com/example/Foo.java",
            "com/example/Foo.kt",
        )
    }

    @Test
    fun `default-package classes map without a directory`() {
        sourceCandidatesFor("Top\$Inner") shouldBe listOf("Top.java", "Top.kt")
    }

    @Test
    fun `blank names map to nothing instead of throwing`() {
        sourceCandidatesFor("") shouldBe emptyList()
        sourceCandidatesFor("   ") shouldBe emptyList()
        sourceCandidatesFor("\$Orphan") shouldBe emptyList()
    }

    @Test
    fun `jdk src zip module prefix resolves to the nested file`() {
        // JDK 9+ src.zip nests entries under the module directory
        // (`java.base/java/util/ArrayList.java`, JEP 201); the flat candidate
        // misses, so findSource strips one leading segment as fallback.
        MemorySourceRoot(
            mapOf("java.base/java/util/ArrayList.java" to "class ArrayList {}"),
        ).use { root ->
            root.findSource("java.util.ArrayList") shouldBe "java.base/java/util/ArrayList.java"
            root.findSource("java.util.ArrayList\$SubList") shouldBe "java.base/java/util/ArrayList.java"
        }
    }

    @Test
    fun `flat entries win over module-prefixed ones and ties sort first`() {
        MemorySourceRoot(
            mapOf(
                "com/example/Foo.java" to "flat",
                "zeta/com/example/Foo.java" to "prefixed",
            ),
        ).use { root ->
            root.findSource("com.example.Foo") shouldBe "com/example/Foo.java"
        }
        MemorySourceRoot(
            mapOf(
                "zeta/Top.java" to "z",
                "alpha/Top.java" to "a",
            ),
        ).use { root ->
            root.findSource("Top") shouldBe "alpha/Top.java"
        }
    }

    private fun arbPackage(): Arb<List<String>> =
        Arb.list(Arb.of("com", "example", "a", "b2", "_x"), 0..3)

    private fun arbClass(): Arb<String> =
        Arb.of("Foo", "Bar", "Outer", "KotlinShapes", "NoDebug", "A1", "_Hidden")

    private fun arbNesting(): Arb<List<String>> =
        Arb.list(Arb.of("Inner", "Nested", "Companion", "Kt", "1"), 0..2)

    @Test
    fun `nesting never changes the mapped file and java stays first`() = runBlocking<Unit> {
        checkAll(1000, arbPackage(), arbClass(), arbNesting()) { pkg, cls, nesting ->
            val outer = (pkg + cls).joinToString(".")
            val nested = outer + nesting.joinToString("") { "\$$it" }
            val candidates = sourceCandidatesFor(nested)
            candidates shouldBe sourceCandidatesFor(outer)
            candidates.size shouldBe 2
            val stem = outer.replace('.', '/')
            candidates shouldBe listOf("$stem.java", "$stem.kt")
        }
    }

    @Test
    fun `mapping never throws on hostile input`() = runBlocking<Unit> {
        // Raw strings: slashes, dots, whitespace, unicode — the mapping is total.
        checkAll(1000, Arb.string(0..24).filter { it.length <= 24 }) { raw ->
            val candidates = sourceCandidatesFor(raw)
            if (raw.isBlank() || raw.substringBefore('$').isEmpty()) {
                candidates shouldBe emptyList()
            } else {
                candidates.size shouldBe 2
                candidates[0].endsWith(".java") shouldBe true
                candidates[1].endsWith(".kt") shouldBe true
                candidates[0].removeSuffix(".java") shouldBe candidates[1].removeSuffix(".kt")
            }
        }
    }
}
