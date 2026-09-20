package dev.jdx.decompile

import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The real `javap` engine over fixture bytes (T-027, tier 2): disassembly
 * output, determinism, disk-cache behaviour, timeouts and honest failures.
 * Spawns subprocesses and touches disk, so tagged out of tier 1. Skips
 * gracefully when no `javap` is on the machine (the T-056 precedent).
 */
@Tag("tier2")
class JavapDecompilerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun systemJavap(): String {
        val found = JavapEnvironment.system().resolveExecutable()
        assumeTrue(found != null, "no javap on this machine — skipping javap engine tests")
        return found!!
    }

    private fun engine(
        javap: String,
        timeout: kotlin.time.Duration = 30.seconds,
    ): JavapDecompiler = JavapDecompiler(
        cache = DecompileCache(tempDir.resolve("cache")),
        timeout = timeout,
        environment = JavapEnvironment(explicitExecutable = javap),
    )

    private fun genericsBytes(): ByteArray =
        FixtureJars.classBytes(FixtureJars.binaryJar(), "dev.jdx.fixtures.Generics")

    @Test
    fun `fixture class disassembles with descriptors naming its members`() {
        val result = engine(systemJavap()).decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        result.shouldBeInstanceOf<DecompileResult.Decompiled>()
        result.text shouldContain "public U identity(U);"
        result.text shouldContain "descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
        result.text shouldContain "Code:"
        result.engineVersion.trim().isNotEmpty() shouldBe true
    }

    @Test
    fun `disassembling twice yields identical text`() {
        val first = engine(systemJavap()).decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        val second = engine(systemJavap()).decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        first.shouldBeInstanceOf<DecompileResult.Decompiled>()
        second.shouldBeInstanceOf<DecompileResult.Decompiled>()
        second.text shouldBe first.text
        second.engineVersion shouldBe first.engineVersion
    }

    @Test
    fun `first disassemble populates the disk cache under the javap version`() {
        val javap = systemJavap()
        val bytes = genericsBytes()
        val first = engine(javap).decompileClass(bytes, "dev.jdx.fixtures.Generics")
        first.shouldBeInstanceOf<DecompileResult.Decompiled>()
        Files.isRegularFile(DecompileCache(tempDir.resolve("cache")).entryFile(bytes, "javap", first.engineVersion)) shouldBe true
    }

    @Test
    fun `a cache hit never re-runs the disassembly`() {
        // A counting wrapper proves the spawn count: version probe + one
        // disassembly on the first call, then silence on the repeat.
        val realJavap = systemJavap()
        val counter = tempDir.resolve("spawns.txt")
        Files.writeString(counter, "0")
        val wrapper = tempDir.resolve("counting-javap.sh")
        Files.writeString(
            wrapper,
            "#!/bin/sh\n" +
                "echo x >> \"${counter.toAbsolutePath()}\"\n" +
                "exec \"$realJavap\" \"\$@\"\n",
        )
        wrapper.toFile().setExecutable(true)
        val bytes = genericsBytes()
        val first = engine(wrapper.toString()).decompileClass(bytes, "dev.jdx.fixtures.Generics")
        first.shouldBeInstanceOf<DecompileResult.Decompiled>()
        val afterFirst = Files.readAllLines(counter).size
        (afterFirst >= 2) shouldBe true
        val second = engine(wrapper.toString()).decompileClass(bytes, "dev.jdx.fixtures.Generics")
        second.shouldBeInstanceOf<DecompileResult.Decompiled>()
        second.text shouldBe first.text
        // A fresh engine re-probes `-version` (one spawn) but the cached
        // disassembly itself never runs again.
        Files.readAllLines(counter).size shouldBe afterFirst + 1
    }

    @Test
    fun `corrupt bytes fail with the cause named, never a throw`() {
        val result = engine(systemJavap()).decompileClass("not a class file".toByteArray(), "com.example.Broken")
        result.shouldBeInstanceOf<DecompileResult.Failed>()
        result.message shouldContain "com.example.Broken"
    }

    @Test
    fun `empty bytes and hostile names fail without spawning`() {
        val javap = systemJavap()
        engine(javap).decompileClass(ByteArray(0), "com.example.Empty")
            .shouldBeInstanceOf<DecompileResult.Failed>()
        engine(javap).decompileClass(genericsBytes(), "")
            .shouldBeInstanceOf<DecompileResult.Failed>()
        engine(javap).decompileClass(genericsBytes(), "../evil")
            .shouldBeInstanceOf<DecompileResult.Failed>()
    }

    @Test
    fun `a missing binary fails naming the path, never a throw`() {
        val bogus = tempDir.resolve("no-such-javap").toString()
        val result = engine(bogus).decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        result.shouldBeInstanceOf<DecompileResult.Failed>()
        result.message shouldContain bogus
    }

    @Test
    fun `an expired budget fails as a timeout, not a hang`() {
        // Answers `-version` instantly, hangs on anything else.
        val wrapper = tempDir.resolve("hanging-javap.sh")
        Files.writeString(
            wrapper,
            "#!/bin/sh\n" +
                "if [ \"\$1\" = \"-version\" ]; then echo \"fake-javap test\"; else sleep 30; fi\n",
        )
        wrapper.toFile().setExecutable(true)
        val result = engine(wrapper.toString(), timeout = 1.seconds)
            .decompileClass(genericsBytes(), "dev.jdx.fixtures.Generics")
        result.shouldBeInstanceOf<DecompileResult.Failed>()
        result.message shouldContain "timed out"
    }

    @Test
    fun `resolution prefers JAVA_HOME over PATH`() {
        val fakeHome = tempDir.resolve("fake-home")
        Files.createDirectories(fakeHome.resolve("bin"))
        val homeJavap = fakeHome.resolve("bin/javap")
        Files.writeString(homeJavap, "#!/bin/sh\necho fake\n")
        homeJavap.toFile().setExecutable(true)
        val environment = JavapEnvironment(
            javaHomeEnv = fakeHome.toString(),
            javaHome = tempDir.resolve("no-such-home").toString(),
            pathDirs = emptyList(),
        )
        environment.resolveExecutable() shouldBe homeJavap.toString()
        val missing = JavapEnvironment(
            javaHomeEnv = tempDir.resolve("no-home").toString(),
            javaHome = tempDir.resolve("no-home-either").toString(),
            pathDirs = emptyList(),
        )
        missing.resolveExecutable() shouldBe null
    }
}
