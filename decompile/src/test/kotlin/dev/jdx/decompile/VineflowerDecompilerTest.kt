package dev.jdx.decompile

import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The real Vineflower engine over fixture bytes (T-026, tier 2): readable
 * output, determinism, disk-cache behaviour, and honest failures. Slow
 * (~1 s first run — engine init) and disk-touching, so tagged out of tier 1.
 */
@Tag("tier2")
class VineflowerDecompilerTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun engine(timeout: kotlin.time.Duration = 30.seconds): VineflowerDecompiler =
        VineflowerDecompiler(cache = DecompileCache(tempDir.resolve("cache")), timeout = timeout)

    private fun genericsBytes(): ByteArray =
        FixtureJars.classBytes(FixtureJars.binaryJar(), "dev.jdx.fixtures.Generics")

    @Test
    fun `fixture class decompiles to readable java naming its members`() {
        val result = engine().decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        result.shouldBeInstanceOf<DecompileResult.Decompiled>()
        result.text shouldContain "class Generics"
        result.text shouldContain "identity"
        result.engineVersion shouldBe VINEFLOWER_VERSION
    }

    @Test
    fun `decompiling twice yields identical text`() {
        val first = engine().decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        val second = engine().decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        first.shouldBeInstanceOf<DecompileResult.Decompiled>()
        second.shouldBeInstanceOf<DecompileResult.Decompiled>()
        second.text shouldBe first.text
    }

    @Test
    fun `first decompile populates the disk cache for the next engine`() {
        val bytes = genericsBytes()
        val first = engine().decompileClass(bytes, "dev.jdx.fixtures.Generics")
        first.shouldBeInstanceOf<DecompileResult.Decompiled>()
        val cache = DecompileCache(tempDir.resolve("cache"))
        Files.isRegularFile(cache.entryFile(bytes, "vineflower", VINEFLOWER_VERSION)) shouldBe true
        // A fresh engine over the same cache dir answers from disk.
        val second = engine().decompileClass(bytes, "dev.jdx.fixtures.Generics")
        second.shouldBeInstanceOf<DecompileResult.Decompiled>()
        second.text shouldBe first.text
    }

    @Test
    fun `corrupt bytes fail with the cause named, never a throw`() {
        val result = engine().decompileClass("not a class file".toByteArray(), "com.example.Broken")
        result.shouldBeInstanceOf<DecompileResult.Failed>()
        result.message shouldContain "com.example.Broken"
    }

    @Test
    fun `empty bytes and hostile names fail without touching the engine`() {
        engine().decompileClass(ByteArray(0), "com.example.Empty")
            .shouldBeInstanceOf<DecompileResult.Failed>()
        engine().decompileClass(genericsBytes(), "")
            .shouldBeInstanceOf<DecompileResult.Failed>()
        engine().decompileClass(genericsBytes(), "../evil")
            .shouldBeInstanceOf<DecompileResult.Failed>()
    }

    @Test
    fun `an expired budget fails as a timeout, not a hang`() {
        val result = engine(timeout = kotlin.time.Duration.ZERO)
            .decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        result.shouldBeInstanceOf<DecompileResult.Failed>()
        result.message shouldContain "timed out"
    }

    @Test
    fun `the runner loads in isolation`() {
        // A non-null isolated runner plus the green decompile above proves the
        // child-first path is the one taken (failures fall back in-process).
        (engine().loadIsolatedRunner() != null) shouldBe true
    }
}
