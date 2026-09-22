package dev.jdx.sources

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Java body extraction against real sources (T-021, tier 2): the T-006
 * fixture `-sources.jar` plus crafted hostile jars and a source dir.
 * Everything here touches the filesystem, so it is tagged out of tier 1.
 * Pure mapping, overload and hostile-text behaviour lives in
 * `JavaBodiesTest` (tier 1).
 */
@Tag("tier2")
class JavaBodiesSourcesTest {

    private fun ref(
        type: String,
        name: String,
        params: List<String>? = null,
        returns: String? = null,
    ): MemberSymbolRef = MemberSymbolRef(
        declaringType = typeNameFromBinaryName(type),
        name = name,
        parameterTypes = params?.map { typeNameFromBinaryName(it) },
        returnType = returns?.let { typeNameFromBinaryName(it) },
    )

    private fun fixturesDir(): File {
        System.getProperty("jdx.fixturesDir")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/libs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("fixture jars not found: build :testfixtures first")
    }

    private fun fixtureSourcesJar(dir: File = fixturesDir()): File {
        val jars = dir.listFiles { file ->
            file.isFile && file.name.endsWith("-sources.jar")
        }?.toList().orEmpty()
        require(jars.size == 1) { "expected exactly one fixture sources jar in $dir, found: $jars" }
        return jars.single()
    }

    private fun foundBodies(root: SourceRoot, ref: MemberSymbolRef): List<SourceBody> =
        findJavaBodies(root, ref).shouldBeInstanceOf<JavaBodyResult.Found>().bodies

    @Test
    fun `fixture Generics identity body is verbatim source`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            val bodies = foundBodies(root, ref("dev.jdx.fixtures.Generics", "identity", listOf("U")))
            bodies shouldHaveSize 1
            val body = bodies.single()
            body.file shouldBe "dev/jdx/fixtures/Generics.java"
            body.parameterTypes shouldBe listOf("U")
            body.returnType shouldBe "U"
            body.text shouldContain "return value;"
            (body.startLine >= 1) shouldBe true
            (body.endLine > body.startLine) shouldBe true
        }
    }

    @Test
    fun `fixture nested Inner outer resolves through dollar nesting`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            val bodies = foundBodies(
                root,
                ref("dev.jdx.fixtures.Nesting\$Inner", "outer", emptyList()),
            )
            bodies shouldHaveSize 1
            bodies.single().file shouldBe "dev/jdx/fixtures/Nesting.java"
            bodies.single().text shouldContain "Nesting.this"
        }
    }

    @Test
    fun `bridge overload in bytecode has one source declaration`() {
        // `Child#copy` exists twice in bytecode (covariant + bridge) but once
        // in sources — the skeleton/flesh split (D-009) made concrete.
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            val bodies = foundBodies(root, ref("dev.jdx.fixtures.CovariantOverrides\$Child", "copy"))
            bodies shouldHaveSize 1
            bodies.single().text shouldContain "return this;"
        }
    }

    @Test
    fun `fixture enum field explicit ctor and compact record ctor`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            // `seconds` names both the field and the zero-arg method: the
            // seam returns both (D-016 ambiguity) instead of guessing one.
            val seconds = foundBodies(root, ref("dev.jdx.fixtures.TrafficLight", "seconds"))
            seconds shouldHaveSize 2
            seconds.map { it.kind }.toSet() shouldBe
                setOf(SourceBodyKind.FIELD, SourceBodyKind.METHOD)
            val field = seconds.first { it.kind == SourceBodyKind.FIELD }
            field.text shouldBe "    private final int seconds;"

            val ctor = foundBodies(
                root,
                ref("dev.jdx.fixtures.TrafficLight", "<init>", listOf("int")),
            ).single()
            ctor.kind shouldBe SourceBodyKind.CONSTRUCTOR
            ctor.text shouldContain "this.seconds = seconds;"

            val compact = foundBodies(root, ref("dev.jdx.fixtures.PersonRecord", "<init>")).single()
            compact.kind shouldBe SourceBodyKind.CONSTRUCTOR
            compact.text shouldContain "IllegalArgumentException"
        }
    }

    @Test
    fun `fixture varargs match array-typed ref params`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            val bodies = foundBodies(
                root,
                ref(
                    "dev.jdx.fixtures.VarargsAndModifiers",
                    "join",
                    listOf("java.lang.String", "[Ljava/lang/String;"),
                ),
            )
            bodies shouldHaveSize 1
            bodies.single().text shouldContain "String.join(separator, parts)"
        }
    }

    @Test
    fun `fixture body-less native method slices to its declaration`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            val bodies = foundBodies(
                root,
                ref("dev.jdx.fixtures.VarargsAndModifiers", "nativeMethod", emptyList()),
            )
            bodies shouldHaveSize 1
            bodies.single().text shouldBe "    public native int nativeMethod();"
        }
    }

    @Test
    fun `implicit members have no source node`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            // `Nesting` declares no constructor: the implicit default ctor
            // exists in bytecode only — sources honestly report absence.
            findJavaBodies(root, ref("dev.jdx.fixtures.Nesting", "<init>")) shouldBe
                JavaBodyResult.MemberNotFound
            // `this$0` is a synthetic field: same story.
            findJavaBodies(root, ref("dev.jdx.fixtures.Nesting\$Inner", "this\$0")) shouldBe
                JavaBodyResult.MemberNotFound
        }
    }

    @Test
    fun `fixture same-file siblings resolve to Annos dot java`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            findJavaSourcePath(root, "dev.jdx.fixtures.Tag") shouldBe "dev/jdx/fixtures/Annos.java"
            findJavaSourcePath(root, "dev.jdx.fixtures.Matrix") shouldBe "dev/jdx/fixtures/Annos.java"
            findJavaSourcePath(root, "dev.jdx.fixtures.Tags") shouldBe "dev/jdx/fixtures/Annos.java"
            val listed = listJavaMembers(root, "dev.jdx.fixtures.Tag")
                .shouldBeInstanceOf<JavaMemberList.Listed>().members
            (listed.map { it.name }.contains("value")) shouldBe true
            val bodies = foundBodies(root, ref("dev.jdx.fixtures.Annos", "tagged", emptyList()))
            bodies shouldHaveSize 1
            bodies.single().file shouldBe "dev/jdx/fixtures/Annos.java"
            // Annotation elements are methods in bytecode but
            // annotation-member declarations in JavaParser (T-079).
            val element = foundBodies(root, ref("dev.jdx.fixtures.Tag", "value", emptyList()))
            element shouldHaveSize 1
            element.single().kind shouldBe SourceBodyKind.METHOD
            element.single().file shouldBe "dev/jdx/fixtures/Annos.java"
            element.single().text shouldContain "String value();"
        }
    }

    @Test
    fun `kotlin-only file is NotJava`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            val ktPath = root.sourcePaths().first { it.endsWith(".kt") }
            val binary = ktPath.removeSuffix(".kt").replace('/', '.')
            findJavaBodies(root, ref(binary, "anything")) shouldBe JavaBodyResult.NotJava
            listJavaMembers(root, binary) shouldBe JavaMemberList.NotJava
        }
    }

    @Test
    fun `extraction is deterministic across opens`() {
        JarSourceRoot(fixtureSourcesJar().toPath()).use { root ->
            val query = ref("dev.jdx.fixtures.Generics", "bounded", listOf("V"))
            findJavaBodies(root, query) shouldBe findJavaBodies(root, query)
        }
    }

    @Test
    fun `source dir root extracts end to end`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        Files.createDirectories(src.resolve("com/example"))
        Files.write(
            src.resolve("com/example/Calc.java"),
            """
            package com.example;
            public class Calc {
                public int twice(int x) {
                    return x * 2;
                }
            }
            """.trimIndent().toByteArray(),
        )
        DirSourceRoot(src).use { root ->
            val bodies = foundBodies(root, ref("com.example.Calc", "twice", listOf("int")))
            bodies shouldHaveSize 1
            bodies.single().text shouldContain "x * 2"
        }
    }

    private fun craftJar(jar: Path, entries: Map<String, ByteArray>): Path {
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            entries.entries.sortedBy { it.key }.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `truncated source is a ParseError value never a throw`(@TempDir tmp: Path) {
        val jar = craftJar(
            tmp.resolve("cut-sources.jar"),
            mapOf(
                "com/example/Cut.java" to
                    "package com.example; public class Cut { public int half(".toByteArray(),
            ),
        )
        JarSourceRoot(jar).use { root ->
            val result = shouldNotThrowAny {
                findJavaBodies(root, ref("com.example.Cut", "half", listOf("int")))
            }
            result.shouldBeInstanceOf<JavaBodyResult.ParseError>().message shouldContain "does not parse"
        }
    }

    @Test
    fun `deflate-corrupt entry is a read ParseError value`(@TempDir tmp: Path) {
        // Zeroing the zlib header (CMF) of a DEFLATED entry keeps the central
        // directory intact — the file still lists — while the first read
        // throws, pinning the read-failure branch of `loadJavaUnit` (the
        // TOCTOU shape of a jar changing mid-read). CRC games do not work:
        // this JDK's ZipFile read path does not verify entry CRCs (L-063).
        val jar = craftJar(
            tmp.resolve("corrupt-sources.jar"),
            mapOf(
                "com/example/Foo.java" to
                    """
                    package com.example;
                    public class Foo {
                        public int answer() {
                            return 42;
                        }
                        public String describe(String name) {
                            return "Foo(" + name + ")";
                        }
                    }
                    """.trimIndent().toByteArray(),
            ),
        )
        val bytes = Files.readAllBytes(jar)
        // Local file header: PK\x03\x04 + 26 fixed bytes, nameLen at +26,
        // extraLen at +28 (both little-endian); entry data follows the name.
        val name = "com/example/Foo.java".toByteArray(Charsets.UTF_8)
        val nameAt = bytes.indexOf(name, start = 0)
        require(nameAt != -1) { "entry name not found in crafted jar" }
        val headerStart = nameAt - 30
        require(bytes[headerStart] == 0x50.toByte() && bytes[headerStart + 1] == 0x4b.toByte()) {
            "local file header signature not found in crafted jar"
        }
        val nameLen = le16(bytes, headerStart + 26)
        val extraLen = le16(bytes, headerStart + 28)
        require(nameLen == name.size) { "unexpected name length in crafted jar" }
        val contentStart = nameAt + nameLen + extraLen
        bytes[contentStart] = 0x00
        Files.write(jar, bytes)
        JarSourceRoot(jar).use { root ->
            // The central directory is intact, so the file still lists.
            root.sourcePaths() shouldBe listOf("com/example/Foo.java")
            val result = shouldNotThrowAny {
                findJavaBodies(root, ref("com.example.Foo", "answer", emptyList()))
            }
            result.shouldBeInstanceOf<JavaBodyResult.ParseError>().message shouldContain "cannot read"
        }
    }

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.indexOf(marker: ByteArray, start: Int): Int {
        outer@ for (i in start..size - marker.size) {
            for (j in marker.indices) {
                if (this[i + j] != marker[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
