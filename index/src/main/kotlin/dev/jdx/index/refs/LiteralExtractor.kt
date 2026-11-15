package dev.jdx.index.refs

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode

/**
 * Extracts string constants from class-file bytes (issue #86).
 *
 * Two shapes feed `jdx literals`:
 * - **`ldc`** — a method-body `LDC`/`LDC_W` of a `String` (resource keys, log
 *   messages, annotation string markers).
 * - **`const`** — a field's `ConstantValue` when the field is a `String`
 *   (`static final String KEY = "…"`).
 *
 * Like [ReferenceExtractor], this never loads the class and never throws:
 * truncated or hostile bytes yield an empty list. Hits are first-wins
 * deduplicated per `(member, kind, value)` so a loop that reloads the same
 * string once per iteration is one row; the kept [LiteralOccurrence.line] is
 * the earliest `LineNumberTable` entry that covers that load.
 *
 * Class/Type LDC constants are ignored here — those are type references for
 * `usages`, not string search.
 */
public object LiteralExtractor {

    /** One string constant found in a class file. */
    public data class LiteralOccurrence(
        public val fromMember: String,
        public val fromDescriptor: String,
        public val kind: String,
        public val value: String,
        public val line: Int? = null,
        public val sourceFile: String? = null,
    )

    /**
     * Extracts every non-empty string constant from [classBytes]. Never
     * throws: anything ASM cannot parse degrades to fewer (or zero) hits.
     */
    public fun extract(classBytes: ByteArray): List<LiteralOccurrence> {
        val node = try {
            val parsed = ClassNode()
            ClassReader(classBytes).accept(parsed, ClassReader.SKIP_FRAMES)
            parsed
        } catch (_: Exception) {
            return emptyList()
        }
        val sourceFile = node.sourceFile
        // First-wins on (member, kind, value); later repeats keep the earliest line.
        val hits = LinkedHashMap<HitIdentity, LiteralOccurrence>()
        for (field in node.fields ?: emptyList()) {
            val value = field.value as? String ?: continue
            if (value.isEmpty()) continue
            // ConstantValue on non-String fields is numeric/enum — skip.
            if (field.desc != "Ljava/lang/String;") continue
            val name = field.name ?: continue
            val descriptor = field.desc ?: continue
            putFirst(
                hits,
                LiteralOccurrence(
                    fromMember = name,
                    fromDescriptor = descriptor,
                    kind = "const",
                    value = value,
                    sourceFile = sourceFile,
                ),
            )
        }
        for (method in node.methods ?: emptyList()) {
            val fromMember = method.name ?: continue
            val fromDescriptor = method.desc ?: continue
            val instructions = method.instructions ?: continue
            var currentLine: Int? = null
            val cursor = instructions.iterator()
            while (cursor.hasNext()) {
                when (val instruction = cursor.next()) {
                    is LineNumberNode -> currentLine = instruction.line
                    is LdcInsnNode -> {
                        val value = instruction.cst as? String ?: continue
                        if (value.isEmpty()) continue
                        putFirst(
                            hits,
                            LiteralOccurrence(
                                fromMember = fromMember,
                                fromDescriptor = fromDescriptor,
                                kind = "ldc",
                                value = value,
                                line = currentLine,
                                sourceFile = sourceFile,
                            ),
                        )
                    }
                    else -> Unit
                }
            }
        }
        // Deterministic order: kind, member, descriptor, value.
        return hits.values.sortedWith(
            compareBy(
                { it.kind },
                { it.fromMember },
                { it.fromDescriptor },
                { it.value },
            ),
        )
    }

    private data class HitIdentity(
        val fromMember: String,
        val fromDescriptor: String,
        val kind: String,
        val value: String,
    )

    private fun putFirst(
        hits: MutableMap<HitIdentity, LiteralOccurrence>,
        hit: LiteralOccurrence,
    ) {
        val key = HitIdentity(hit.fromMember, hit.fromDescriptor, hit.kind, hit.value)
        if (key !in hits) hits[key] = hit
    }
}
