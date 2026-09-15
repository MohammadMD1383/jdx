package dev.jdx.index.asm

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.TypeKind
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactTestJars
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2 acceptance tests for [AsmClassReader] (T-008).
 *
 * Tagged `tier2` (docs/TESTING.md §2): every test here reads real jars off disk or shells
 * out to `javap`. The fast in-memory suites live in [AsmClassReaderTest].
 *
 * The differential test below is the correctness oracle for this task (TESTING.md §5.1):
 * for every fixture class, the member set and descriptors read by ASM must equal what
 * `javap -p -s` reports — exactly, member by member.
 */
@Tag("tier2")
class AsmClassReaderDifferentialTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    // -- the oracle ---------------------------------------------------------------

    @Test
    fun `every fixture class agrees with javap member for member`() {
        val javap = assumeJavap()
        val failures = mutableListOf<String>()
        for (binaryName in fixtureClassNames()) {
            val expected = javapMembers(runJavap(javap, binaryName))
            val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, entryPath(binaryName))
            val result = AsmClassReader.read(bytes, entryPath(binaryName))
            val info = result.shouldBeInstanceOf<ClassReadResult.Ok>().info
            val actual = readerMembers(info)
            if (actual != expected) {
                failures.add(formatMismatch(binaryName, expected, actual))
            }
        }
        failures shouldBe emptyList<String>()
    }

    // -- whole-jar behaviour --------------------------------------------------------

    @Test
    fun `reading every fixture class through artifact roots succeeds`() {
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            val entries = root.classEntryPaths()
            (entries.isNotEmpty()) shouldBe true
            entries.forEach { path ->
                val result = root.openClass(path).use { AsmClassReader.read(it, path) }
                result.shouldBeInstanceOf<ClassReadResult.Ok>()
            }
        }
    }

    @Test
    fun `a corrupt entry never aborts its neighbours`() {
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            val entries = root.classEntryPaths()
            entries.forEachIndexed { index, path ->
                val bytes = root.openClass(path).use { it.readBytes() }
                // Every 7th entry is truncated mid-file; the rest must still read clean.
                val probe = if (index % 7 == 0) bytes.copyOfRange(0, bytes.size / 2) else bytes
                val result = AsmClassReader.read(probe, path)
                if (index % 7 == 0) {
                    result.shouldBeInstanceOf<ClassReadResult.Corrupt>()
                } else {
                    result.shouldBeInstanceOf<ClassReadResult.Ok>()
                }
            }
        }
    }

    @Test
    fun `reading every fixture class never initialises one`() {
        // StaticInitMarker's `<clinit>` writes this file. The reader parses bytes (D-017),
        // so the marker must be absent even after a full-jar read through this very reader.
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            root.classEntryPaths().forEach { path ->
                root.openClass(path).use { AsmClassReader.read(it, path) }
            }
        }
        File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker").exists() shouldBe false
    }

    // -- running-JDK majors parse ----------------------------------------------------

    @Test
    fun `classes from the running jdk parse cleanly`() {
        // Fixture classes are major 65 (toolchain Java 21); the JDK itself ships major 70
        // (JDK 26) — this proves "majors up to the running JDK parse" on real bytes.
        ArtifactLoader.openJdk().use { root ->
            for (path in listOf("java/lang/Object.class", "java/util/HashMap.class")) {
                val result = root.openClass(path).use { AsmClassReader.read(it, path) }
                val info = result.shouldBeInstanceOf<ClassReadResult.Ok>().info
                (info.methods.isNotEmpty()) shouldBe true
            }
            val map = root.openClass("java/util/HashMap.class").use {
                (AsmClassReader.read(it, "HashMap") as ClassReadResult.Ok).info
            }
            map.methods.map { it.name } shouldContain "put"
        }
    }

    // -- flag spot-checks on real compiler output -------------------------------------

    @Test
    fun `real compiler flags survive the round trip`() {
        val info = readFixture("dev.jdx.fixtures.VarargsAndModifiers")
        info.methods.single { it.name == "syncMethod" }.access.has(AccessFlag.SYNCHRONIZED) shouldBe true
        info.methods.single { it.name == "nativeMethod" }.access.has(AccessFlag.NATIVE) shouldBe true
        info.methods.single { it.name == "join" }.access.has(AccessFlag.VARARGS) shouldBe true
        info.methods.single { it.name == "oldApi" }.deprecated shouldBe true
        info.fields.single { it.name == "serialVersionUID" }.constantValue shouldBe "1"
        // Package-private and private members are members too — the reader hides nothing.
        info.methods.single { it.name == "packagePrivate" }.access.visibility shouldBe
            dev.jdx.core.model.Visibility.PACKAGE_PRIVATE
        info.methods.single { it.name == "hidden" }.access.visibility shouldBe
            dev.jdx.core.model.Visibility.PRIVATE
    }

    @Test
    fun `annotation defaults nesting and no-debug names read from real output`() {
        val matrix = readFixture("dev.jdx.fixtures.Matrix")
        matrix.kind shouldBe TypeKind.ANNOTATION
        matrix.methods.forEach { (it.annotationDefault != null) shouldBe true }
        val annos = readFixture("dev.jdx.fixtures.Annos")
        val tagged = annos.methods.single { it.name == "tagged" }
        tagged.annotations.map { it.type.fqn } shouldContain "dev.jdx.fixtures.Tag"
        val inner = readFixture("dev.jdx.fixtures.Nesting\$Inner")
        inner.outerClass?.fqn shouldBe "dev.jdx.fixtures.Nesting"
        val noDebug = readFixture("dev.jdx.fixtures.NoDebug")
        noDebug.methods.forEach { it.parameterNames.forEach { name -> (name == null) shouldBe true } }
    }

    // -- machinery -------------------------------------------------------------------

    private fun readFixture(binaryName: String): dev.jdx.core.model.ClassInfo {
        val result = AsmClassReader.read(
            ArtifactTestJars.fixtureClassBytes(binaryJar, entryPath(binaryName)),
            entryPath(binaryName),
        )
        return result.shouldBeInstanceOf<ClassReadResult.Ok>().info
    }

    private fun fixtureClassNames(): List<String> {
        val names = mutableListOf<String>()
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            root.classEntryPaths().forEach { path ->
                names.add(path.removeSuffix(".class").replace('/', '.'))
            }
        }
        return names.sorted()
    }

    private fun entryPath(binaryName: String): String = binaryName.replace('.', '/') + ".class"

    private data class MemberKey(val name: String, val descriptor: String)

    private fun readerMembers(info: dev.jdx.core.model.ClassInfo): Set<MemberKey> =
        (info.fields.map { MemberKey(it.name, it.type.descriptor) } +
            info.methods.map { MemberKey(it.name, it.descriptor.descriptor) }).toSet()

    /**
     * Parses `javap -p -s` output into `(name, descriptor)` pairs. Each member line is
     * followed by its `descriptor:` line; constructors print as the FQN (they contain a
     * `.`, unlike method names) and `static {};` is the class initialiser.
     */
    private fun javapMembers(output: List<String>): Set<MemberKey> {
        val members = mutableSetOf<MemberKey>()
        var pending: String? = null
        for (line in output) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "}" ||
                trimmed.startsWith("Compiled from") || trimmed.endsWith("{")
            ) {
                continue
            }
            if (trimmed.startsWith("descriptor:")) {
                val descriptor = trimmed.removePrefix("descriptor:").trim()
                check(pending != null) { "javap descriptor without a member line: $trimmed" }
                members.add(MemberKey(pending, descriptor))
                pending = null
            } else {
                pending = normaliseMemberLine(trimmed)
            }
        }
        return members
    }

    private fun normaliseMemberLine(line: String): String {
        val bare = line.removeSuffix(";")
        if ('(' in bare) {
            val nameToken = bare.substringBefore('(').trim().substringAfterLast(' ')
            return if ('.' in nameToken) "<init>" else nameToken
        }
        if (bare.trim() == "static {}") return "<clinit>"
        return bare.trim().substringAfterLast(' ')
    }

    private fun formatMismatch(
        binaryName: String,
        expected: Set<MemberKey>,
        actual: Set<MemberKey>,
    ): String = buildString {
        appendLine("MISMATCH $binaryName (javap -p -s vs AsmClassReader):")
        (expected - actual).sortedBy { it.name }.forEach { appendLine("- javap only:  ${it.name} ${it.descriptor}") }
        (actual - expected).sortedBy { it.name }.forEach { appendLine("+ reader only: ${it.name} ${it.descriptor}") }
    }

    /** Locates `javap`: the test runtime's JDK first, `PATH` as a fallback. Never throws. */
    private fun assumeJavap(): String {
        val homeJavap = File(System.getProperty("java.home"), "bin/javap")
        if (homeJavap.canExecute()) return homeJavap.absolutePath
        val onPath = try {
            val probe = ProcessBuilder("javap", "-version").redirectErrorStream(true).start()
            val exitedZero = probe.waitFor() == 0
            probe.inputStream.readBytes()
            exitedZero
        } catch (_: Exception) {
            false
        }
        assumeTrue(onPath, "javap not found: skipping javap-agreement checks (TESTING.md §5.1)")
        return "javap"
    }

    private fun runJavap(javap: String, binaryName: String): List<String> {
        // Flags before the class name: a trailing `-s` is parsed as a class name (L-018).
        val process = ProcessBuilder(javap, "-p", "-s", "-classpath", binaryJar.absolutePath, binaryName)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        check(exit == 0) { "javap failed for $binaryName:\n$output" }
        return output.lines()
    }
}
