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
import org.objectweb.asm.tree.LineNumberNode
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
 * indexing the same bytes twice yields identical rows (D-007). Dedup identity
 * excludes [ReferenceEdge.line]/[ReferenceEdge.sourceFile]: the kept line is
 * the first `LineNumberTable` entry that covers the edge in bytecode order
 * (issue #8). Classes compiled with `-g:none` yield `line == null` — degrade,
 * don't fail (AGENTS.md §2.8).
 *
 * `ATHROW` is intentionally not extracted: attributing the thrown type needs
 * data-flow the extractor deliberately skips, so `usages --kind throw` is a
 * `throws`-declaration view (see `JdxService.collectMetadataUsages`); site
 * attribution is deferred to future `jdx flow` work.
 */
public object ReferenceExtractor {

    /**
     * Extracts every reference edge from [classBytes]. Never throws: anything
     * ASM cannot parse degrades to fewer (or zero) edges, never an exception.
     * `ATHROW` sites are intentionally not extracted (no data-flow to
     * attribute the thrown type).
     */
    public fun extract(classBytes: ByteArray): List<ReferenceEdge> {
        val fromClass = try {
            ClassReader(classBytes).className.replace('/', '.')
        } catch (_: Exception) {
            return emptyList()
        }
        val node = try {
            val parsed = ClassNode()
            // Frames are control flow we never read. Debug info *is* read:
            // LineNumberTable feeds [ReferenceEdge.line] (issue #8). Parameter
            // names are AsmClassReader's concern — we only need line labels.
            ClassReader(classBytes).accept(parsed, ClassReader.SKIP_FRAMES)
            parsed
        } catch (_: Exception) {
            return emptyList()
        }
        val sourceFile = node.sourceFile
        // First-wins on logical identity (no line/sourceFile): later repeats
        // of the same call/read/ref in one method keep the earliest line.
        val edges = LinkedHashMap<EdgeIdentity, ReferenceEdge>()
        for (method in node.methods ?: emptyList()) {
            val fromMember = method.name ?: continue
            val fromDescriptor = method.desc ?: continue
            val instructions = method.instructions ?: continue
            var currentLine: Int? = null
            val cursor = instructions.iterator()
            while (cursor.hasNext()) {
                when (val instruction = cursor.next()) {
                    is LineNumberNode -> currentLine = instruction.line
                    is MethodInsnNode -> addCallEdge(
                        edges, fromClass, fromMember, fromDescriptor, instruction, currentLine, sourceFile,
                    )
                    is FieldInsnNode -> addFieldEdge(
                        edges, fromClass, fromMember, fromDescriptor, instruction, currentLine, sourceFile,
                    )
                    is TypeInsnNode -> addTypeEdge(
                        edges, fromClass, fromMember, fromDescriptor, instruction.desc, currentLine, sourceFile,
                    )
                    is LdcInsnNode -> addLdcEdge(
                        edges, fromClass, fromMember, fromDescriptor, instruction.cst, currentLine, sourceFile,
                    )
                    is InvokeDynamicInsnNode -> addDynamicEdges(
                        edges, fromClass, fromMember, fromDescriptor, instruction, currentLine, sourceFile,
                    )
                    else -> Unit
                }
            }
        }
        return edges.values.toList().sortedEdges()
    }

    /**
     * Logical edge identity for first-wins dedup: everything the store keys
     * an edge on, excluding the optional line/file payload.
     */
    private data class EdgeIdentity(
        val fromClass: String,
        val fromMember: String,
        val fromDescriptor: String,
        val toOwner: String,
        val toMember: String?,
        val toDescriptor: String?,
        val kind: ReferenceKind,
    )

    private fun putFirst(
        edges: MutableMap<EdgeIdentity, ReferenceEdge>,
        edge: ReferenceEdge,
    ) {
        val key = EdgeIdentity(
            fromClass = edge.fromClass,
            fromMember = edge.fromMember,
            fromDescriptor = edge.fromDescriptor,
            toOwner = edge.toOwner,
            toMember = edge.toMember,
            toDescriptor = edge.toDescriptor,
            kind = edge.kind,
        )
        if (key !in edges) edges[key] = edge
    }

    private fun addCallEdge(
        edges: MutableMap<EdgeIdentity, ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        instruction: MethodInsnNode,
        line: Int?,
        sourceFile: String?,
    ) {
        val owner = internalToBinary(instruction.owner) ?: return
        val descriptor = instruction.desc ?: return
        val name = instruction.name ?: return
        putFirst(
            edges,
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = name,
                toDescriptor = descriptor,
                kind = ReferenceKind.METHOD_CALL,
                line = line,
                sourceFile = sourceFile,
            ),
        )
    }

    private fun addFieldEdge(
        edges: MutableMap<EdgeIdentity, ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        instruction: FieldInsnNode,
        line: Int?,
        sourceFile: String?,
    ) {
        val owner = internalToBinary(instruction.owner) ?: return
        val name = instruction.name ?: return
        val descriptor = instruction.desc ?: return
        val kind = when (instruction.opcode) {
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> ReferenceKind.FIELD_READ
            Opcodes.PUTFIELD, Opcodes.PUTSTATIC -> ReferenceKind.FIELD_WRITE
            else -> return
        }
        putFirst(
            edges,
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = name,
                toDescriptor = descriptor,
                kind = kind,
                line = line,
                sourceFile = sourceFile,
            ),
        )
    }

    private fun addTypeEdge(
        edges: MutableMap<EdgeIdentity, ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        rawDesc: String?,
        line: Int?,
        sourceFile: String?,
    ) {
        val owner = descriptorToBinary(rawDesc) ?: return
        putFirst(
            edges,
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = null,
                toDescriptor = null,
                kind = ReferenceKind.TYPE_REFERENCE,
                line = line,
                sourceFile = sourceFile,
            ),
        )
    }

    private fun addLdcEdge(
        edges: MutableMap<EdgeIdentity, ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        constant: Any?,
        line: Int?,
        sourceFile: String?,
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
        putFirst(
            edges,
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = null,
                toDescriptor = null,
                kind = ReferenceKind.TYPE_REFERENCE,
                line = line,
                sourceFile = sourceFile,
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
        edges: MutableMap<EdgeIdentity, ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        instruction: InvokeDynamicInsnNode,
        line: Int?,
        sourceFile: String?,
    ) {
        val bootstrap = instruction.bsm
        if (bootstrap != null) {
            addHandleTarget(edges, fromClass, fromMember, fromDescriptor, bootstrap, line, sourceFile)
        }
        for (argument in instruction.bsmArgs ?: emptyArray()) {
            when (argument) {
                is Handle -> addHandleTarget(
                    edges, fromClass, fromMember, fromDescriptor, argument, line, sourceFile,
                )
                // Only class constants are type references; MethodTypes and
                // MethodHandles name no class (see addLdcEdge).
                is Type -> if (argument.sort == Type.OBJECT || argument.sort == Type.ARRAY) {
                    addTypeEdge(
                        edges, fromClass, fromMember, fromDescriptor, argument.descriptor, line, sourceFile,
                    )
                }
                else -> Unit
            }
        }
    }

    private fun addHandleTarget(
        edges: MutableMap<EdgeIdentity, ReferenceEdge>,
        fromClass: String,
        fromMember: String,
        fromDescriptor: String,
        handle: Handle,
        line: Int?,
        sourceFile: String?,
    ) {
        val owner = internalToBinary(handle.owner) ?: return
        val name = handle.name ?: return
        val descriptor = handle.desc ?: return
        val kind = when (handle.tag) {
            Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC -> ReferenceKind.FIELD_READ
            Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> ReferenceKind.FIELD_WRITE
            else -> ReferenceKind.METHOD_CALL
        }
        putFirst(
            edges,
            ReferenceEdge(
                fromClass = fromClass,
                fromMember = fromMember,
                fromDescriptor = fromDescriptor,
                toOwner = owner,
                toMember = name,
                toDescriptor = descriptor,
                kind = kind,
                line = line,
                sourceFile = sourceFile,
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
