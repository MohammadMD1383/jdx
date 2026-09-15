package dev.jdx.index.asm

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Builds class-file bytes in memory for the tier-1 [AsmClassReader] tests: no disk, no
 * subprocesses. Thin wrappers over ASM's tree API — behaviours, not bytes, are the point.
 */
internal fun buildClass(
    internalName: String,
    access: Int = Opcodes.ACC_PUBLIC,
    signature: String? = null,
    superName: String = "java/lang/Object",
    interfaces: Array<String>? = null,
    block: ClassBuilder.() -> Unit = {},
): ByteArray {
    val node = ClassNode()
    node.visit(Opcodes.V17, access, internalName, signature, superName, interfaces)
    ClassBuilder(node).block()
    node.visitEnd()
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray()
}

internal class ClassBuilder(private val node: ClassNode) {
    fun source(name: String): Unit {
        node.visitSource(name, null)
    }

    fun inner(name: String, outerName: String, innerName: String, access: Int): Unit {
        node.visitInnerClass(name, outerName, innerName, access)
    }

    fun field(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
        block: FieldBuilder.() -> Unit = {},
    ): Unit {
        val field = FieldNode(access, name, descriptor, signature, value)
        FieldBuilder(field).block()
        node.fields.add(field)
    }

    fun method(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?,
        block: MethodBuilder.() -> Unit = {},
    ): Unit {
        val method = MethodNode(access, name, descriptor, signature, exceptions)
        MethodBuilder(method).block()
        node.methods.add(method)
    }
}

internal class FieldBuilder(private val node: FieldNode) {
    fun annotate(descriptor: String, visible: Boolean): AnnotationVisitor =
        node.visitAnnotation(descriptor, visible)
}

internal class MethodBuilder(private val node: MethodNode) {
    fun parameter(name: String, access: Int): Unit {
        node.visitParameter(name, access)
    }

    fun annotation(descriptor: String, visible: Boolean): AnnotationVisitor =
        node.visitAnnotation(descriptor, visible)

    fun annotationDefault(value: Any?): Unit {
        val default = node.visitAnnotationDefault()
        default.visit(null, value)
        default.visitEnd()
    }

    /** Emits raw instructions; `null` variable means a no-operand instruction. */
    fun code(vararg ops: Pair<Int, Int?>): Unit {
        ops.forEach { (opcode, variable) ->
            if (variable == null) node.instructions.add(InsnNode(opcode))
            else node.instructions.add(VarInsnNode(opcode, variable))
        }
    }

    /** Attaches a `LocalVariableTable` spanning the emitted code. */
    fun locals(vararg vars: Triple<String, String, Int>): Unit {
        val start = LabelNode(Label())
        val end = LabelNode(Label())
        node.instructions.insert(start)
        node.instructions.add(end)
        vars.forEach { (name, descriptor, index) ->
            node.localVariables.add(LocalVariableNode(name, descriptor, null, start, end, index))
        }
    }
}
