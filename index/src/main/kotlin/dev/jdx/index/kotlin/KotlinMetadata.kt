package dev.jdx.index.kotlin

import kotlin.metadata.ClassKind
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata as buildMetadataAnnotation
import kotlin.metadata.kind
import org.objectweb.asm.tree.AnnotationNode

/**
 * Which `@Metadata` family a class file belongs to (T-035, PROPOSAL.md §12.1).
 *
 * Only [CLASS] carries a [KotlinMetadata.classKind]; every other family still marks
 * the class file as Kotlin (file facades are Kotlin declarations whose JVM projection
 * is a static utility class).
 */
public enum class KotlinMetadataKind {
    CLASS,
    FILE_FACADE,
    MULTI_FILE_FACADE,
    MULTI_FILE_PART,
    SYNTHETIC,
    UNKNOWN,
}

/**
 * The decoded Kotlin `@Metadata` of one class file (T-035).
 *
 * Produced by [KotlinMetadataReader] from ASM annotation nodes; consumed by
 * `AsmClassReader` (the `isKotlin` flag and the `OBJECT`/`COMPANION` kind
 * refinement) and, from T-036, by the Kotlin member mapping.
 */
public data class KotlinMetadata(
    public val metadataKind: KotlinMetadataKind,
    public val classKind: ClassKind?,
)

/**
 * Reads Kotlin `@Metadata` from ASM annotation nodes with `kotlin-metadata-jvm`
 * (T-035).
 *
 * The single entry point is [read]: absent metadata yields `null`, corrupt metadata
 * yields `null` — never a throw. A class file the Kotlin compiler did not write, or
 * one whose metadata a newer compiler wrote in a shape this decoder cannot parse,
 * is still a readable JVM class; callers degrade to the JVM view (PROPOSAL.md §16).
 */
public object KotlinMetadataReader {

    private const val METADATA_DESC = "Lkotlin/Metadata;"

    /**
     * Finds `kotlin.Metadata` in [visible] or [invisible] annotations and decodes it.
     * Returns `null` when no such annotation exists or when it cannot be decoded.
     */
    public fun read(
        visible: List<AnnotationNode>?,
        invisible: List<AnnotationNode>?,
    ): KotlinMetadata? {
        val node = ((visible ?: emptyList()) + (invisible ?: emptyList()))
            .firstOrNull { it.desc == METADATA_DESC } ?: return null
        return try {
            decode(valuesMap(node))
        } catch (_: Exception) {
            null
        }
    }

    private fun valuesMap(node: AnnotationNode): Map<String, Any?> {
        val flat = node.values ?: return emptyMap()
        val mapped = linkedMapOf<String, Any?>()
        var index = 0
        while (index + 1 < flat.size) {
            val key = flat[index] as? String ?: return emptyMap()
            mapped[key] = flat[index + 1]
            index += 2
        }
        return mapped
    }

    private fun decode(values: Map<String, Any?>): KotlinMetadata? {
        // `k` (the metadata kind id) is mandatory; without it this is not metadata.
        // `mv`/`d1`/`d2` are always written by the Kotlin compiler — a missing or
        // mistyped one is corrupt metadata, which degrades to null upstream.
        val kind = values["k"] as? Int ?: return null
        val metadataVersion = intArray(values["mv"]) ?: return null
        val data1 = stringArray(values["d1"]) ?: return null
        val data2 = stringArray(values["d2"]) ?: return null
        // `xs`/`pn`/`xi` have annotation defaults; absent means default.
        val annotation = buildMetadataAnnotation(
            kind = kind,
            metadataVersion = metadataVersion,
            data1 = data1,
            data2 = data2,
            extraString = values["xs"] as? String ?: "",
            packageName = values["pn"] as? String ?: "",
            extraInt = values["xi"] as? Int ?: 0,
        )
        return when (val metadata = KotlinClassMetadata.readLenient(annotation)) {
            is KotlinClassMetadata.Class -> KotlinMetadata(KotlinMetadataKind.CLASS, metadata.kmClass.kind)
            is KotlinClassMetadata.FileFacade -> KotlinMetadata(KotlinMetadataKind.FILE_FACADE, null)
            is KotlinClassMetadata.MultiFileClassFacade ->
                KotlinMetadata(KotlinMetadataKind.MULTI_FILE_FACADE, null)
            is KotlinClassMetadata.MultiFileClassPart ->
                KotlinMetadata(KotlinMetadataKind.MULTI_FILE_PART, null)
            is KotlinClassMetadata.SyntheticClass -> KotlinMetadata(KotlinMetadataKind.SYNTHETIC, null)
            is KotlinClassMetadata.Unknown -> KotlinMetadata(KotlinMetadataKind.UNKNOWN, null)
        }
    }

    // ASM stores annotation arrays as Lists of boxed values; a lone value or a
    // wrongly-typed entry is corrupt metadata, which degrades to null upstream.
    private fun intArray(value: Any?): IntArray? = when (value) {
        null -> null
        is List<*> -> value.map { (it as? Int) ?: return null }.toIntArray()
        else -> null
    }

    private fun stringArray(value: Any?): Array<String>? = when (value) {
        null -> null
        is List<*> -> value.map { (it as? String) ?: return null }.toTypedArray()
        else -> null
    }
}
