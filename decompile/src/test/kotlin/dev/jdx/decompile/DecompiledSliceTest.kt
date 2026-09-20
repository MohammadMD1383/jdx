package dev.jdx.decompile

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.sources.JavaBodyResult
import dev.jdx.sources.MemorySourceRoot
import dev.jdx.sources.findJavaBodies
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The PROPOSAL.md §11.2 reuse proof (T-026, tier 2): decompiled text enters
 * the pipeline through [MemorySourceRoot] and slices through the T-021
 * `findJavaBodies` seam — so `jdx body` output has the same shape whether it
 * came from real sources or Vineflower.
 */
@Tag("tier2")
class DecompiledSliceTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun decompiledText(): String {
        val engine = VineflowerDecompiler(
            cache = DecompileCache(tempDir.resolve("cache")),
            timeout = 30.seconds,
        )
        val bytes = FixtureJars.classBytes(FixtureJars.binaryJar(), "dev.jdx.fixtures.Generics")
        val result = engine.decompileClass(bytes, "dev.jdx.fixtures.Generics")
        result.shouldBeInstanceOf<DecompileResult.Decompiled>()
        return result.text
    }

    @Test
    fun `a decompiled member slices through the java seam`() {
        val root = MemorySourceRoot(mapOf("dev/jdx/fixtures/Generics.java" to decompiledText()))
        // Decompiled text keeps the generic spelling (`U identity(U)`), so the
        // lookup uses it — the same erased→generic retry `JdxService` performs
        // before slicing (D-009: both spellings are bytecode's own words).
        val ref = MemberSymbolRef(
            declaringType = typeNameFromBinaryName("dev.jdx.fixtures.Generics"),
            name = "identity",
            parameterTypes = listOf(typeNameFromBinaryName("U")),
        )
        val found = findJavaBodies(root, ref)
        found.shouldBeInstanceOf<JavaBodyResult.Found>()
        found.bodies.size shouldBe 1
        found.bodies.single().text shouldContain "return value;"
    }
}
