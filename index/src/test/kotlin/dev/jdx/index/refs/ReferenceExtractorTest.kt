package dev.jdx.index.refs

import dev.jdx.core.model.ReferenceEdge
import dev.jdx.core.model.ReferenceKind
import dev.jdx.core.model.sortedEdges
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Tier-1 tests for [ReferenceExtractor] (T-029, TESTING.md §3/§4).
 *
 * No disk, no jars: every input is class bytes built in memory with ASM, so
 * this runs in the fast loop. Real-jar coverage (fixture corpus, truncated
 * entries) belongs to the tier-2 indexer suite.
 */
class ReferenceExtractorTest {

    /** `com.example.Client#run` touching `com.example.Service` every supported way. */
    private fun clientBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Client", null, "java/lang/Object", null)
        val body = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
        body.visitCode()
        body.visitTypeInsn(Opcodes.NEW, "com/example/Service")
        body.visitInsn(Opcodes.DUP)
        body.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/example/Service", "<init>", "()V", false)
        body.visitVarInsn(Opcodes.ASTORE, 1)
        body.visitVarInsn(Opcodes.ALOAD, 1)
        body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Service", "execute", "()V", false)
        body.visitVarInsn(Opcodes.ALOAD, 1)
        body.visitFieldInsn(Opcodes.GETFIELD, "com/example/Service", "count", "I")
        body.visitInsn(Opcodes.POP)
        body.visitVarInsn(Opcodes.ALOAD, 1)
        body.visitInsn(Opcodes.ICONST_1)
        body.visitFieldInsn(Opcodes.PUTFIELD, "com/example/Service", "count", "I")
        body.visitLdcInsn(Type.getType("Lcom/example/Service;"))
        body.visitInsn(Opcodes.POP)
        body.visitVarInsn(Opcodes.ALOAD, 1)
        body.visitTypeInsn(Opcodes.CHECKCAST, "com/example/Service")
        body.visitInsn(Opcodes.POP)
        body.visitVarInsn(Opcodes.ALOAD, 1)
        body.visitTypeInsn(Opcodes.INSTANCEOF, "com/example/Service")
        body.visitInsn(Opcodes.POP)
        body.visitInsn(Opcodes.RETURN)
        body.visitMaxs(0, 0)
        body.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A method spinning up a `Runnable` lambda via `LambdaMetafactory`. */
    private fun lambdaBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Client", null, "java/lang/Object", null)
        val body = writer.visitMethod(Opcodes.ACC_PUBLIC, "spawn", "()V", null, null)
        body.visitCode()
        body.visitInvokeDynamicInsn(
            "run",
            "()Ljava/lang/Runnable;",
            Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                    "Ljava/lang/invoke/CallSite;",
                false,
            ),
            Type.getType("()V"),
            Handle(Opcodes.H_INVOKESTATIC, "com/example/Client", "lambda\$run", "()V", false),
            Type.getType("()V"),
        )
        body.visitInsn(Opcodes.POP)
        body.visitInsn(Opcodes.RETURN)
        body.visitMaxs(0, 0)
        body.visitEnd()
        val impl = writer.visitMethod(Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC, "lambda\$run", "()V", null, null)
        impl.visitCode()
        impl.visitInsn(Opcodes.RETURN)
        impl.visitMaxs(0, 0)
        impl.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun edge(
        kind: ReferenceKind,
        toOwner: String,
        toMember: String?,
        toDescriptor: String?,
    ): ReferenceEdge = ReferenceEdge(
        fromClass = "com.example.Client",
        fromMember = "run",
        fromDescriptor = "()V",
        toOwner = toOwner,
        toMember = toMember,
        toDescriptor = toDescriptor,
        kind = kind,
    )

    @Test
    fun `a method call is a METHOD_CALL edge`() {
        val edges = ReferenceExtractor.extract(clientBytes())
        edges shouldBe edges.sortedEdges()
        assert(edges.contains(edge(ReferenceKind.METHOD_CALL, "com.example.Service", "execute", "()V")))
        assert(edges.contains(edge(ReferenceKind.METHOD_CALL, "com.example.Service", "<init>", "()V")))
    }

    @Test
    fun `field reads and writes map to FIELD_READ and FIELD_WRITE`() {
        val edges = ReferenceExtractor.extract(clientBytes())
        assert(edges.contains(edge(ReferenceKind.FIELD_READ, "com.example.Service", "count", "I")))
        assert(edges.contains(edge(ReferenceKind.FIELD_WRITE, "com.example.Service", "count", "I")))
    }

    @Test
    fun `type instructions and class constants are TYPE_REFERENCE edges without members`() {
        val edges = ReferenceExtractor.extract(clientBytes())
        val typeEdges = edges.filter { it.kind == ReferenceKind.TYPE_REFERENCE }
        // NEW + ldc + checkcast + instanceof collapse to one deduped edge.
        typeEdges shouldBe listOf(edge(ReferenceKind.TYPE_REFERENCE, "com.example.Service", null, null))
    }

    @Test
    fun `repeated calls in one method deduplicate to a single edge`() {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Loop", null, "java/lang/Object", null)
        val body = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
        body.visitCode()
        repeat(10) {
            body.visitVarInsn(Opcodes.ALOAD, 0)
            body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Service", "ping", "()V", false)
        }
        body.visitInsn(Opcodes.RETURN)
        body.visitMaxs(0, 0)
        body.visitEnd()
        writer.visitEnd()
        val edges = ReferenceExtractor.extract(writer.toByteArray())
        edges.filter { it.kind == ReferenceKind.METHOD_CALL } shouldBe listOf(
            ReferenceEdge(
                fromClass = "com.example.Loop",
                fromMember = "run",
                fromDescriptor = "()V",
                toOwner = "com.example.Service",
                toMember = "ping",
                toDescriptor = "()V",
                kind = ReferenceKind.METHOD_CALL,
            ),
        )
    }

    @Test
    fun `invokedynamic records the bootstrap and the lambda implementation`() {
        val edges = ReferenceExtractor.extract(lambdaBytes())
        assert(
            edges.contains(
                ReferenceEdge(
                    fromClass = "com.example.Client",
                    fromMember = "spawn",
                    fromDescriptor = "()V",
                    toOwner = "java.lang.invoke.LambdaMetafactory",
                    toMember = "metafactory",
                    toDescriptor = "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                        "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
                        "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                        "Ljava/lang/invoke/CallSite;",
                    kind = ReferenceKind.METHOD_CALL,
                ),
            ),
        )
        assert(
            edges.contains(
                ReferenceEdge(
                    fromClass = "com.example.Client",
                    fromMember = "spawn",
                    fromDescriptor = "()V",
                    toOwner = "com.example.Client",
                    toMember = "lambda\$run",
                    toDescriptor = "()V",
                    kind = ReferenceKind.METHOD_CALL,
                ),
            ),
        )
        // MethodType constants name no class — they must not become garbage owners.
        assert(edges.none { it.toOwner.startsWith("(") })
    }

    @Test
    fun `a method with no instructions yields no edges`() {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "com/example/Empty", null, "java/lang/Object", null)
        val body = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "run", "()V", null, null)
        body.visitEnd()
        writer.visitEnd()
        ReferenceExtractor.extract(writer.toByteArray()) shouldBe emptyList()
    }

    @Test
    fun `truncated bytes never throw`() {
        val full = clientBytes()
        for (percent in 10..100 step 10) {
            val cut = full.copyOf((full.size * percent) / 100)
            // The promise is only "never throws" — the cut may or may not parse.
            ReferenceExtractor.extract(cut)
        }
    }

    @Test
    fun `corrupt bytes yield an empty list, not a throw`() {
        ReferenceExtractor.extract(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0x01, 0x02)) shouldBe emptyList()
        ReferenceExtractor.extract(ByteArray(0)) shouldBe emptyList()
    }

    @Test
    fun `extraction is deterministic`() {
        val bytes = clientBytes()
        ReferenceExtractor.extract(bytes) shouldBe ReferenceExtractor.extract(bytes)
    }

    @Test
    fun `extraction never throws on hostile input`() = runBlocking<Unit> {
        checkAll(1_000, Arb.byteArray(Arb.int(0..256), Arb.byte())) { bytes ->
            ReferenceExtractor.extract(bytes)
        }
    }
}
