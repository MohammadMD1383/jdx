package dev.jdx.index.refs

import dev.jdx.core.model.ReferenceEdge
import dev.jdx.core.model.ReferenceKind
import dev.jdx.core.model.sortedEdges
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Extracts reference edges from class-file bytes (T-029, PROPOSAL.md §10.4 step 3).
 *
 * A lightweight pass over method bodies — method calls, field reads/writes and
 * type mentions — with no control-flow analysis. The result feeds the M4 graph
 * queries (`usages`, `callers`/`calls`, `samples`); hierarchy edges
 * (superclass/interfaces) already live in the class table and are not repeated here.
 *
 * Like [dev.jdx.index.asm.AsmClassReader], this never loads the class (D-017)
 * and never throws: truncated or hostile bytes yield an empty list, and one
 * malformed instruction skips its edge while its neighbours survive.
 *
 * Edges are deduplicated (one row per distinct edge per method — a loop calling
 * `foo()` ten times is one edge) and returned in [sortedEdges] order, so
 * indexing the same bytes twice yields identical rows (D-007).
 */
public object ReferenceExtractor {

    /**
     * Extracts every reference edge from [classBytes]. Never throws: anything
     * ASM cannot parse degrades to fewer (or zero) edges, never an exception.
     */
    public fun extract(classBytes: ByteArray): List<ReferenceEdge> {
        val fromClass = try {
            ClassReader(classBytes).className.replace('/', '.')
        } catch (_: Exception) {
            return emptyList()
        }
        val node = try {
            val parsed = ClassNode()
            // Frames are control flow we never read; debug info is irrelevant
            // to edges, so skipping both is safe here (unlike AsmClassReader,
            // which needs debug info for parameter names).
            ClassReader(classBytes).accept(parsed, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
            parsed
        } catch (_: Exception) {
            return emptyList()
        }
        val edges = LinkedHashSet<ReferenceEdge>()
        for (method in node.methods ?: emptyList()) {
            val fromMember = method.name ?: continue
            val fromDescriptor = method.desc ?: continue
            val instructions = method.instructions ?: continue
            val cursor = instructions.iterator()
            while (cursor.hasNext()) {
                when (val instruction = cursor.next()) {
                    is MethodInsnNode -> addCallEdge(edges, fromClass, fromMember, fromDescriptor, instruction)
                    is FieldInsnNode -> addFieldEdge(edges, fromClass, fromMember, fromDescriptor, instruction)
                    is TypeInsnNode -> addTypeEdge(edges, fromClass, fromMember, fromDescriptor, instruction.desc)
                    is LdcInsnNode -> addLdcEdge(edges, fromClass, fromMember, fromDescriptor, instruction.cst)
                    is InvokeDynamicInsnNode -> addDynamicEdges(edges, fromClass, fromMember, fromDescriptor, instruction)
                    else -> Unit
                }
            }
        }
        return edges.toList().sortedEdges()
    }

    private fun addCallEdge(
        edges: MutableSet<ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        instruction: MethodInsnNode,
    ) {
        val owner = internalToBinary(instruction.owner) ?: return
        val descriptor = instruction.desc ?: return
        val name = instruction.name ?: return
        edges.add(
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = name,
                toDescriptor = descriptor,
                kind = ReferenceKind.METHOD_CALL,
            ),
        )
    }

    private fun addFieldEdge(
        edges: MutableSet<ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        instruction: FieldInsnNode,
    ) {
        val owner = internalToBinary(instruction.owner) ?: return
        val name = instruction.name ?: return
        val descriptor = instruction.desc ?: return
        val kind = when (instruction.opcode) {
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> ReferenceKind.FIELD_READ
            Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> ReferenceKind.FIELD_WRITE
            else -> return
        }
        edges.add(
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = name,
                toDescriptor = descriptor,
                kind = kind,
            ),
        )
    }

    private fun addTypeEdge(
        edges: MutableSet<ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        rawDesc: String?,
    ) {
        val owner = descriptorToBinary(rawDesc) ?: return
        edges.add(
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = null,
                toDescriptor = null,
                kind = ReferenceKind.TYPE_REFERENCE,
            ),
        )
    }

    private fun addLdcEdge(
        edges: MutableSet<ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        constant: Any?,
    ) {
        if (constant !is Type) return
        // Only class constants are type references; method-handle constants
        // are covered by the invokedynamic path that actually uses them.
        if (constant.sort != Type.OBJECT && constant.sort != Type.ARRAY) return
        val owner = try {
            elementBinary(constant.className)
        } catch (_: Exception) {
            return
        } ?: return
        edges.add(
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = null,
                toDescriptor = null,
                kind = ReferenceKind.TYPE_REFERENCE,
            ),
        )
    }

    /**
     * Records an `invokedynamic` site's resolvable targets: the bootstrap
     * method itself plus every method/field/type handle in its static
     * arguments. For lambdas this is what links the call site to the
     * synthetic `lambda$…` implementation method — without it, lambda bodies
     * would be invisible to `callers` (T-033).
     */
    private fun addDynamicEdges(
        edges: MutableSet<ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        instruction: InvokeDynamicInsnNode,
    ) {
        val bootstrap = instruction.bsm
        if (bootstrap != null) {
            addHandleTarget(edges, fromClass, fromMember, fromDescriptor, bootstrap)
        }
        for (argument in instruction.bsmArgs ?: emptyArray()) {
            when (argument) {
                is Handle -> addHandleTarget(edges, fromClass, fromMember, fromDescriptor, argument)
                // Only class constants are type references; MethodTypes and
                // MethodHandles name no class (see addLdcEdge).
                is Type -> if (argument.sort == Type.OBJECT || argument.sort == Type.ARRAY) {
                    addTypeEdge(edges, fromClass, fromMember, fromDescriptor, argument.descriptor)
                }
                else -> Unit
            }
        }
    }

    private fun addHandleTarget(
        edges: MutableSet<ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        handle: Handle,
    ) {
        val owner = internalToBinary(handle.owner) ?: return
        val name = handle.name ?: return
        val descriptor = handle.desc ?: return
        val kind = when (handle.tag) {
            Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC -> ReferenceKind.FIELD_READ
            Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> ReferenceKind.FIELD_WRITE
            else -> ReferenceKind.METHOD_CALL
        }
        edges.add(
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = name,
                toDescriptor = descriptor,
                kind = kind,
            ),
        )
    }

    /** `java/lang/String` → `java.lang.String`; `null`/blank → `null` (skip, never throw). */
    private fun internalToBinary(internalName: String?): String? {
        if (internalName.isNullOrBlank()) return null
        return internalName.replace('/', '.')
    }

    /**
     * Maps a `TypeInsnNode`/`invokedynamic` type operand to the referenced
     * class's binary name. Operands arrive either as internal names (`NEW`)
     * or descriptors (`[Ljava/lang/String;`); arrays collapse to their
     * element type so `new String[4]` references `java.lang.String`.
     */
    private fun descriptorToBinary(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Method descriptors never name a class on their own (the dynamic path
        // guards these out, but belt-and-braces: never emit a garbage owner).
        if (raw.startsWith("(")) return null
        return try {
            if (raw.startsWith("[") || raw.startsWith("L")) {
                elementBinary(Type.getType(raw).className)
            } else {
                raw.replace('/', '.')
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `java.lang.String[]` → `java.lang.String` (arrays collapse to their
     * element); plain binary names pass through. Primitives have no holder
     * class to reference, so they yield `null` (skip).
     */
    private fun elementBinary(className: String): String? {
        // Multi-dimensional arrays nest the suffix (`int[][]`); strip all layers.
        var element = className
        while (element.endsWith("[]")) element = element.removeSuffix("[]")
        if (element.isBlank()) return null
        // Primitive element types (`int[]` → `int`) name no class.
        if (element.indexOf('.') < 0 && element[0].isLowerCase()) return null
        return element
    }
}
