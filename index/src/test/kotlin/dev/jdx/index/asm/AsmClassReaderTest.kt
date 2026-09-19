package dev.jdx.index.asm

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.WarningCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Tier-1 tests for [AsmClassReader] (T-008): classes are built in memory with ASM's own
 * writer, so no disk, no subprocesses, no jars — the fast loop stays fast.
 * The fixture-corpus differential against `javap` lives in
 * [AsmClassReaderDifferentialTest] (tier 2).
 */
class AsmClassReaderTest {

    // -- kinds, supertypes, file facts ----------------------------------------

    @Test
    fun `a plain class maps kind access super and source file`() {
        val bytes = buildClass("com/example/Foo") { source("Foo.java") }
        val info = ok(bytes)
        info.kind shouldBe TypeKind.CLASS
        info.name.fqn shouldBe "com.example.Foo"
        info.superclass?.fqn shouldBe "java.lang.Object"
        info.access.has(AccessFlag.PUBLIC) shouldBe true
        info.sourceFileName shouldBe "Foo.java"
    }

    @Test
    fun `an interface normalises its object superclass to null`() {
        val bytes = buildClass(
            "com/example/Has",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            interfaces = arrayOf("java/io/Serializable"),
        )
        val info = ok(bytes)
        info.kind shouldBe TypeKind.INTERFACE
        info.superclass shouldBe null
        info.interfaces.map { it.fqn } shouldBe listOf("java.io.Serializable")
    }

    @Test
    fun `enum annotation and record kinds come from the flag bits`() {
        ok(buildClass("com/example/E", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ENUM)).kind shouldBe
            TypeKind.ENUM
        ok(
            buildClass(
                "com/example/A",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or
                    Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION,
            ),
        ).kind shouldBe TypeKind.ANNOTATION
        ok(buildClass("com/example/R", access = Opcodes.ACC_PUBLIC or 0x10000)).kind shouldBe
            TypeKind.RECORD
    }

    @Test
    fun `a valid class signature is kept and a malformed one degrades to null`() {
        val generic = buildClass(
            "com/example/Box",
            signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
        )
        ok(generic).genericSignature?.signature shouldBe "<T:Ljava/lang/Object;>Ljava/lang/Object;"
        val broken = buildClass("com/example/Broken", signature = "!!!not-a-signature!!!")
        val info = ok(broken)
        info.genericSignature shouldBe null
        info.name.fqn shouldBe "com.example.Broken"
    }

    @Test
    fun `a superclass-only signature keeps its type arguments`() {
        // `class StringList extends ArrayList<String>` with no interfaces: the lone
        // superclass must parse as a ClassSignature (T-009 generic substitution), not
        // degrade to null the way the generic entry point's field-first reading would.
        val bytes = buildClass(
            "com/example/StringList",
            signature = "Lcom/example/ArrayList<Ljava/lang/String;>;",
            superName = "com/example/ArrayList",
        )
        val stringList = ok(bytes)
        val classSignature = stringList.genericSignature
            ?: error("expected a class signature, got null")
        classSignature.superclass.simpleName shouldBe "ArrayList"
        classSignature.superclass.typeArguments.size shouldBe 1
        classSignature.signature shouldBe "Lcom/example/ArrayList<Ljava/lang/String;>;"
    }

    @Test
    fun `the inner-classes table names the outer class`() {
        val bytes = buildClass("com/example/Outer\$Inner") {
            inner("com/example/Outer\$Inner", "com/example/Outer", "Inner", Opcodes.ACC_PUBLIC)
        }
        ok(bytes).outerClass?.fqn shouldBe "com.example.Outer"
    }

    // -- fields -----------------------------------------------------------------

    @Test
    fun `fields carry descriptors signatures constants and deprecation`() {
        val bytes = buildClass("com/example/HasFields") {
            field(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "serialVersionUID", "J", null, 1L)
            field(Opcodes.ACC_PUBLIC or Opcodes.ACC_DEPRECATED, "old", "Ljava/lang/String;", null, null) {
                annotate("Ljava/lang/Deprecated;", true)
            }
            field(Opcodes.ACC_PUBLIC, "names", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;", null)
        }
        val info = ok(bytes)
        val constant = info.fields.single { it.name == "serialVersionUID" }
        constant.type.fqn shouldBe "long"
        constant.constantValue shouldBe "1"
        val old = info.fields.single { it.name == "old" }
        old.deprecated shouldBe true
        old.annotations.map { it.type.fqn } shouldBe listOf("java.lang.Deprecated")
        val names = info.fields.single { it.name == "names" }
        names.genericSignature?.signature shouldBe "Ljava/util/List<Ljava/lang/String;>;"
    }

    // -- methods ----------------------------------------------------------------

    @Test
    fun `methods carry descriptors throws signatures defaults and deprecation`() {
        val bytes = buildClass("com/example/HasMethods") {
            method(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
            method(
                Opcodes.ACC_PUBLIC,
                "read",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                arrayOf("java/io/IOException"),
            )
            method(Opcodes.ACC_PUBLIC or Opcodes.ACC_DEPRECATED, "old", "()V", null, null)
            method(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "names", "()[Ljava/lang/String;", null, null) {
                annotationDefault("{}")
            }
        }
        val info = ok(bytes)
        info.methods.single { it.name == "run" }.descriptor.descriptor shouldBe "()V"
        val read = info.methods.single { it.name == "read" }
        read.throwsTypes.map { it.fqn } shouldBe listOf("java.io.IOException")
        info.methods.single { it.name == "old" }.deprecated shouldBe true
        info.methods.single { it.name == "names" }.annotationDefault shouldBe "\"{}\""
    }

    @Test
    fun `parameter names come from method parameters when present`() {
        val bytes = buildClass("com/example/Params") {
            method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_VARARGS, "join", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;", null, null) {
                parameter("separator", 0)
                parameter("parts", 0)
            }
        }
        ok(bytes).methods.single { it.name == "join" }.parameterNames shouldBe
            listOf("separator", "parts")
    }

    @Test
    fun `parameter names fall back to the local variable table`() {
        val bytes = buildClass("com/example/Locals") {
            // Instance method: slot 0 is `this`, the String sits at slot 1.
            method(Opcodes.ACC_PUBLIC, "greet", "(Ljava/lang/String;)V", null, null) {
                code(Opcodes.ALOAD to 1, Opcodes.RETURN to null)
                locals(Triple("greeting", "Ljava/lang/String;", 1))
            }
            // (long, int): long takes slots 1-2, int sits at slot 3.
            method(Opcodes.ACC_PUBLIC, "wide", "(JI)V", null, null) {
                code(Opcodes.RETURN to null)
                locals(Triple("a", "J", 1), Triple("b", "I", 3))
            }
        }
        val info = ok(bytes)
        info.methods.single { it.name == "greet" }.parameterNames shouldBe listOf("greeting")
        info.methods.single { it.name == "wide" }.parameterNames shouldBe listOf("a", "b")
    }

    @Test
    fun `parameter names are unknown without debug info`() {
        val bytes = buildClass("com/example/NoDebug") {
            method(Opcodes.ACC_PUBLIC, "add", "(II)I", null, null) {
                code(Opcodes.ICONST_0 to null, Opcodes.IRETURN to null)
            }
        }
        ok(bytes).methods.single { it.name == "add" }.parameterNames shouldBe listOf(null, null)
    }

    @Test
    fun `annotation values of every shape render deterministically`() {
        val bytes = buildClass("com/example/Annotated") {
            method(Opcodes.ACC_PUBLIC, "tagged", "()V", null, null) {
                val writer = annotation("Lcom/example/All;", true)
                writer.visit("s", "x")
                writer.visit("n", 3)
                writer.visit("c", Type.getType("Ljava/lang/String;"))
                writer.visitEnum("e", "Lcom/example/E;", "A")
                val array = writer.visitArray("arr")
                array.visit(null, 1)
                array.visit(null, 2)
                array.visitEnd()
                val nested = writer.visitAnnotation("nested", "Lcom/example/N;")
                nested.visit("k", 1)
                nested.visitEnd()
                writer.visitEnd()
            }
        }
        val values = ok(bytes).methods.single { it.name == "tagged" }.annotations.single().values
        values shouldBe linkedMapOf(
            "s" to "\"x\"",
            "n" to "3",
            "c" to "java.lang.String.class",
            "e" to "com.example.E.A",
            "arr" to "{1, 2}",
            "nested" to "@com.example.N(k=1)",
        )
    }

    // -- failure modes ------------------------------------------------------------

    @Test
    fun `empty bad-magic and truncated bytes are corrupt`() {
        corrupt(byteArrayOf(), "shorter than the class header")
        corrupt(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "CAFEBABE")
        val valid = buildClass("com/example/Foo") { }
        corrupt(valid.copyOfRange(0, valid.size / 2), "unparseable")
    }

    @Test
    fun `a major version newer than the running jdk is unsupported not corrupt`() {
        val valid = buildClass("com/example/Future") { }
        val future = valid.copyOf()
        val major = AsmClassReader.runtimeMajorVersion() + 1
        future[6] = ((major ushr 8) and 0xFF).toByte()
        future[7] = (major and 0xFF).toByte()
        val result = AsmClassReader.read(future, "Future.class")
        val unsupported = result.shouldBeInstanceOf<ClassReadResult.UnsupportedVersion>()
        val warning = unsupported.warning
        warning.code shouldBe WarningCode.UNSUPPORTED_CLASS_VERSION
        (warning.message.contains("Future.class")) shouldBe true
    }

    @Test
    fun `a JFR-style dollar-dollar name is unnameable not corrupt`() {
        // T-065: `Exception$JB$$Assertion` shapes carry an empty `$`-separated
        // segment the model rejects (TypeName invariant) — honest degradation
        // with a dedicated code, never CORRUPT_CLASS. Bytes are built by ASM
        // exactly like every other reader test (no disk, no jars).
        val bytes = buildClass("com/example/Outer\$JB\$\$Assertion") { }
        val result = AsmClassReader.read(bytes, "probe.class")
        val corrupt = result.shouldBeInstanceOf<ClassReadResult.Corrupt>()
        val warning = corrupt.warning
        warning.code shouldBe WarningCode.UNNAMEABLE_CLASS
        (warning.message.contains("unnameable class name")) shouldBe true
        (warning.message.contains("probe.class")) shouldBe true
    }

    @Test
    fun `a corrupt descriptor still reports corrupt not unnameable`() {
        // The T-065 matcher must stay narrow: only the empty-segment shape
        // routes to UNNAMEABLE_CLASS. A bad member descriptor is genuine
        // corruption — pinning this keeps real breakage out of the new bucket.
        val bytes = buildClass("com/example/BadDesc") {
            method(Opcodes.ACC_PUBLIC, "broken", "()V", null, null)
        }.copyOf()
        // Corrupt the descriptor indirectly: truncate mid-constant-pool so the
        // mapping throw cannot be an empty-segment message.
        val cut = bytes.copyOfRange(0, bytes.size / 2)
        val result = AsmClassReader.read(cut, "probe.class")
        val corrupt = result.shouldBeInstanceOf<ClassReadResult.Corrupt>()
        corrupt.warning.code shouldBe WarningCode.CORRUPT_CLASS
    }

    @Test
    fun `reading twice yields identical bytes of model`() {
        val bytes = buildClass("com/example/Stable") {
            field(Opcodes.ACC_PUBLIC, "x", "I", null, null)
            method(Opcodes.ACC_PUBLIC, "get", "()I", null, null) {
                parameter("ignored", 0)
            }
        }
        ok(bytes) shouldBe ok(bytes)
    }

    // -- machinery ---------------------------------------------------------------

    private fun ok(bytes: ByteArray): dev.jdx.core.model.ClassInfo {
        val result = AsmClassReader.read(bytes, "probe")
        return result.shouldBeInstanceOf<ClassReadResult.Ok>().info
    }

    private fun corrupt(bytes: ByteArray, messagePart: String) {
        val result = AsmClassReader.read(bytes, "probe.class")
        val corrupt = result.shouldBeInstanceOf<ClassReadResult.Corrupt>()
        val warning = corrupt.warning
        warning.code shouldBe WarningCode.CORRUPT_CLASS
        (warning.message.contains(messagePart)) shouldBe true
        (warning.message.contains("probe.class")) shouldBe true
    }
}
