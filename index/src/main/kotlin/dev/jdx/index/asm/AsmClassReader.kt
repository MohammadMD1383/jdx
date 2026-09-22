package dev.jdx.index.asm

import dev.jdx.core.model.Access
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.FieldSignature
import dev.jdx.core.model.GenericSignature
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.index.kotlin.KotlinMetadataKind
import dev.jdx.index.kotlin.KotlinMembers
import dev.jdx.index.kotlin.KotlinMetadataReader
import java.io.InputStream
import kotlin.metadata.ClassKind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/**
 * Reads class-file bytes with ASM into `core`'s [ClassInfo]/`MemberInfo` (T-008).
 *
 * This is the bytecode half of the truth model (D-009): which members exist, their erased
 * descriptors, generic signatures, supertypes and annotations. It never loads the class into
 * this JVM (D-017) — ASM parses bytes; reflection would run static initialisers.
 *
 * Errors are values ([ClassReadResult]), never exceptions: a corrupt entry yields
 * [WarningCode.CORRUPT_CLASS], a too-new entry yields
 * [WarningCode.UNSUPPORTED_CLASS_VERSION], a name the model cannot represent
 * (an empty `$`-separated segment — JFR `Exception$JB$$Assertion` and friends,
 * T-065) yields [WarningCode.UNNAMEABLE_CLASS], and in every case the rest of
 * the artifact survives — the caller keeps reading the jar.
 */
public sealed interface ClassReadResult {
    /** The class parsed cleanly. [warnings] is empty today; kept for forward growth. */
    public data class Ok(
        public val info: ClassInfo,
        public val warnings: List<Warning> = emptyList(),
    ) : ClassReadResult

    /** The class file's major version is newer than the running JDK understands. */
    public data class UnsupportedVersion(public val warning: Warning) : ClassReadResult

    /** The bytes are not a parseable class file — skipped, the artifact survives. */
    public data class Corrupt(public val warning: Warning) : ClassReadResult
}

/**
 * The single entry point for bytecode reading. Construct nothing — [read] is a pure
 * function of the bytes (plus [sourceHint], which only feeds warning messages).
 */
public object AsmClassReader {

    /** JVMS §4.1/4.7 flag bits ASM exposes but [dev.jdx.core.model.AccessFlag] does not name. */
    private const val ACC_RECORD: Int = 0x10000
    private const val ACC_DEPRECATED: Int = 0x20000

    /**
     * Reads one class file. Never throws: every failure mode is a [ClassReadResult]
     * carrying a warning that names [sourceHint] and the problem.
     *
     * A name the model cannot represent (an empty `$`-separated segment — JFR
     * `Exception$JB$$Assertion` and friends, T-065) is **not** corruption: the
     * bytes are fine, so [ClassReadResult.Corrupt] carries [WarningCode.UNNAMEABLE_CLASS]
     * (message `unnameable class name ...`) while every other unparseable shape
     * carries [WarningCode.CORRUPT_CLASS].
     */
    public fun read(bytes: ByteArray, sourceHint: String = "class"): ClassReadResult {
        if (bytes.size < 8) {
            return corrupt(sourceHint, null, "only ${bytes.size} bytes, shorter than the class header")
        }
        if (!(bytes[0] == 0xCA.toByte() && bytes[1] == 0xFE.toByte() &&
                bytes[2] == 0xBA.toByte() && bytes[3] == 0xBE.toByte())
        ) {
            return corrupt(sourceHint, null, "missing the CAFEBABE magic")
        }
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val runtimeMajor = runtimeMajorVersion()
        if (major > runtimeMajor) {
            return ClassReadResult.UnsupportedVersion(
                Warning(
                    WarningCode.UNSUPPORTED_CLASS_VERSION,
                    "class file major version $major is newer than the running JDK " +
                        "(${Runtime.version().feature()}, major $runtimeMajor): $sourceHint",
                ),
            )
        }
        return try {
            val node = ClassNode()
            // SKIP_FRAMES drops StackMapTable frames (indexing needs no control flow),
            // but keeps debug info — parameter names live there, so never SKIP_DEBUG.
            ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
            ClassReadResult.Ok(mapClass(node))
        } catch (unsupported: IllegalArgumentException) {
            // ASM lags the running JDK: it rejects majors it has no Opcodes for even when
            // the pre-check above passed. Same graceful path, never a throw.
            if ((unsupported.message ?: "").contains("Unsupported class file major version")) {
                ClassReadResult.UnsupportedVersion(
                    Warning(
                        WarningCode.UNSUPPORTED_CLASS_VERSION,
                        "ASM cannot parse class file major version $major " +
                            "(${(unsupported.message ?: "").trim()}): $sourceHint",
                    ),
                )
            } else {
                unnameableOrCorrupt(sourceHint, null, unsupported)
            }
        } catch (failure: Exception) {
            unnameableOrCorrupt(sourceHint, null, failure)
        }
    }

    /** Reads one class from an open stream. The caller owns (and closes) the stream. */
    public fun read(stream: InputStream, sourceHint: String = "class"): ClassReadResult =
        read(stream.readAllBytes(), sourceHint)

    /** The class-file major version the running JDK understands: `feature + 44`. */
    public fun runtimeMajorVersion(): Int = Runtime.version().feature() + 44

    private fun corrupt(hint: String, subject: String?, reason: String): ClassReadResult.Corrupt =
        ClassReadResult.Corrupt(
            Warning(WarningCode.CORRUPT_CLASS, "unparseable class file ($reason): $hint", subject),
        )

    private fun unnameable(hint: String, subject: String?, reason: String): ClassReadResult.Corrupt =
        ClassReadResult.Corrupt(
            Warning(WarningCode.UNNAMEABLE_CLASS, "unnameable class name ($reason): $hint", subject),
        )

    /**
     * Routes a mapping failure to [WarningCode.UNNAMEABLE_CLASS] when the model
     * rejected an empty `$`-separated segment (T-065), else [WarningCode.CORRUPT_CLASS].
     * Only the empty-segment message shape qualifies: corrupt descriptors,
     * bad annotations and every other mapping throw stay corrupt so their
     * count cannot smuggle real breakage into the unnameable bucket.
     */
    private fun unnameableOrCorrupt(hint: String, subject: String?, failure: Throwable): ClassReadResult.Corrupt {
        val message = failure.message ?: "malformed class file (${failure.javaClass.simpleName})"
        return if (isEmptySegmentFailure(message)) unnameable(hint, subject, message)
        else corrupt(hint, subject, message)
    }

    private fun isEmptySegmentFailure(message: String): Boolean =
        message.contains("empty name segment")

    // -- class mapping ---------------------------------------------------------

    private fun mapClass(node: ClassNode): ClassInfo {
        val internalName = node.name ?: throw IllegalArgumentException("class file has no name")
        val access = node.access
        val annotations = readAnnotations(node.visibleAnnotations, node.invisibleAnnotations)
        val deprecated = isDeprecated(access, annotations)
        // One `@Metadata` decode per class: the flag, the kind refinement and
        // the T-077 member views share it. Absent or corrupt metadata is `null`
        // — the class stays a JVM-view class (D-009).
        val kotlinMetadata = KotlinMetadataReader.read(node.visibleAnnotations, node.invisibleAnnotations)
        val kind = refineKind(mapKind(access), kotlinMetadata)
        val superclass = mapSuperclass(kind, internalName, node.superName)
        return ClassInfo(
            name = mapInternalName(internalName),
            kind = kind,
            access = Access(access),
            superclass = superclass,
            interfaces = (node.interfaces ?: emptyList()).map { mapTypeName(it) },
            // The class-file Signature attribute is a ClassSignature by construction
            // (JVMS §4.7.9.1) — parsed with parseClass, never the generic entry point,
            // which would read a lone superclass as a field (T-009: StringList's
            // `ArrayList<String>` edge). Malformed degrades to null, never throws.
            genericSignature = node.signature?.let { GenericSignature.parseClass(it) },
            fields = (node.fields ?: emptyList()).map { mapField(it) },
            methods = (node.methods ?: emptyList()).map { mapMethod(it) },
            annotations = annotations,
            outerClass = mapOuterClass(node),
            sourceFileName = node.sourceFile,
            deprecated = deprecated,
            isKotlin = kotlinMetadata != null,
            kotlinMethodViews = kotlinViewsOf(kotlinMetadata),
        )
    }

    /**
     * Builds the T-077 Kotlin member views from the decoded carrier. Never
     * throws: a hostile-yet-parseable metadata shape degrades to the JVM
     * projection (no views) rather than corrupting the class — the reader's
     * contract is values, never exceptions.
     */
    private fun kotlinViewsOf(
        metadata: dev.jdx.index.kotlin.KotlinMetadata?,
    ): Map<String, dev.jdx.core.model.KotlinMethodView> {
        val kmClass = metadata?.kmClass ?: return emptyMap()
        return try {
            KotlinMembers.viewsFor(kmClass)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mapKind(access: Int): TypeKind = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> TypeKind.ANNOTATION
        access and Opcodes.ACC_ENUM != 0 -> TypeKind.ENUM
        access and ACC_RECORD != 0 -> TypeKind.RECORD
        access and Opcodes.ACC_INTERFACE != 0 -> TypeKind.INTERFACE
        else -> TypeKind.CLASS
    }

    /**
     * Refines the JVM kind with the Kotlin class kind (T-035): `object` (incl. `data
     * object`) and `companion object` become [TypeKind.OBJECT]/[TypeKind.COMPANION],
     * the kinds `ClassInfo` reserves for them. Every other Kotlin kind keeps its JVM
     * kind — a Kotlin interface is still an interface — and file facades (which carry
     * `FILE_FACADE`, not `CLASS`, metadata) stay `CLASS`, so Java output is untouched.
     */
    private fun refineKind(
        jvmKind: TypeKind,
        metadata: dev.jdx.index.kotlin.KotlinMetadata?,
    ): TypeKind {
        if (metadata?.metadataKind != KotlinMetadataKind.CLASS) return jvmKind
        return when (metadata.classKind) {
            ClassKind.OBJECT -> TypeKind.OBJECT
            ClassKind.COMPANION_OBJECT -> TypeKind.COMPANION
            else -> jvmKind
        }
    }

    private fun mapSuperclass(kind: TypeKind, internalName: String, superName: String?): TypeName? {
        // Producers normalise interfaces and java.lang.Object to null (see ClassInfo KDoc).
        if (kind == TypeKind.INTERFACE || internalName == "java/lang/Object") return null
        return superName?.let { mapTypeName(it) }
    }

    private fun mapOuterClass(node: ClassNode): TypeName.ClassType? {
        // The InnerClasses entry for this class names its enclosing class; anonymous and
        // local classes instead (or additionally) carry ClassNode.outerClass.
        val fromTable = (node.innerClasses ?: emptyList())
            .firstOrNull { it.name == node.name }
            ?.outerName
        val internal = fromTable ?: node.outerClass
        return internal?.let { mapInternalName(it) }
    }

    // -- member mapping --------------------------------------------------------

    private fun mapField(node: FieldNode): FieldInfo {
        val descriptor = JvmDescriptor.parse(node.desc)
        if (descriptor !is JvmDescriptor.Field) {
            throw IllegalArgumentException("malformed field descriptor '${node.desc}' on '${node.name}'")
        }
        val annotations = readAnnotations(node.visibleAnnotations, node.invisibleAnnotations)
        return FieldInfo(
            name = node.name,
            type = descriptor.type,
            access = Access(node.access),
            genericSignature = node.signature?.let { GenericSignature.parse(it) as? FieldSignature },
            annotations = annotations,
            deprecated = isDeprecated(node.access, annotations),
            constantValue = node.value?.let { renderValue(it) },
        )
    }

    private fun mapMethod(node: MethodNode): MethodInfo {
        val descriptor = JvmDescriptor.parse(node.desc)
        if (descriptor !is JvmDescriptor.Method) {
            throw IllegalArgumentException("malformed method descriptor '${node.desc}' on '${node.name}'")
        }
        val annotations = readAnnotations(node.visibleAnnotations, node.invisibleAnnotations)
        return MethodInfo(
            name = node.name,
            descriptor = descriptor,
            access = Access(node.access),
            genericSignature = node.signature?.let { GenericSignature.parse(it) as? MethodSignature },
            parameterNames = mapParameterNames(node, descriptor),
            throwsTypes = (node.exceptions ?: emptyList()).map { mapTypeName(it) },
            annotations = annotations,
            deprecated = isDeprecated(node.access, annotations),
            annotationDefault = node.annotationDefault?.let { renderValue(it) },
        )
    }

    /**
     * Parameter names from `MethodParameters` first, `LocalVariableTable` second, `null`
     * (unknown — the renderer synthesises `argN`) last (PROPOSAL.md §11.1). Sizes are
     * checked, not assumed: a mismatch falls through to the next source instead of
     * misaligning names.
     */
    private fun mapParameterNames(node: MethodNode, descriptor: JvmDescriptor.Method): List<String?> {
        val count = descriptor.parameters.size
        if (count == 0) return emptyList()
        val declared = node.parameters
        if (declared != null && declared.size == count) return declared.map { it.name }
        val locals = node.localVariables ?: return List(count) { null }
        // Slot 0 is `this` for instance methods; long/double occupy two slots.
        var slot = if (node.access and Opcodes.ACC_STATIC != 0) 0 else 1
        return descriptor.parameters.map { parameter ->
            val name = locals.firstOrNull { it.index == slot }?.name
            slot += if (parameter is TypeName.PrimitiveType &&
                (parameter.primitive == dev.jdx.core.model.JvmPrimitive.LONG ||
                    parameter.primitive == dev.jdx.core.model.JvmPrimitive.DOUBLE)
            ) {
                2
            } else {
                1
            }
            name
        }
    }

    // -- annotations -----------------------------------------------------------

    private fun readAnnotations(
        visible: List<AnnotationNode>?,
        invisible: List<AnnotationNode>?,
    ): List<AnnotationInfo> =
        ((visible ?: emptyList()) + (invisible ?: emptyList())).map { mapAnnotation(it) }

    private fun mapAnnotation(node: AnnotationNode): AnnotationInfo {
        val values = linkedMapOf<String, String>()
        val flat = node.values ?: emptyList()
        var index = 0
        while (index + 1 < flat.size) {
            val key = flat[index] as? String
                ?: throw IllegalArgumentException("malformed annotation values in ${node.desc}")
            values[key] = renderValue(flat[index + 1])
            index += 2
        }
        return AnnotationInfo(mapAnnotationType(node.desc), values)
    }

    private fun mapAnnotationType(descriptor: String): TypeName =
        typeNameFromBinaryName(Type.getType(descriptor).className)

    private fun isDeprecated(access: Int, annotations: List<AnnotationInfo>): Boolean =
        access and ACC_DEPRECATED != 0 ||
            annotations.any { it.type.fqn == "java.lang.Deprecated" }

    /**
     * Renders one annotation/constant value deterministically. Shapes follow ASM's
     * `AnnotationNode` encoding: primitives and strings as-is, `Type` as `Fqn.class`,
     * enums as the two-element `String` array ASM stores them in, nested annotations as
     * `@Fqn(k=v, …)`, arrays as `{a, b}`. Unknown shapes are corrupt input, never guessed.
     */
    private fun renderValue(value: Any?): String = when (value) {
        is String -> "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
        is Char -> "'" + value.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'") + "'"
        is Byte, is Short, is Int, is Long, is Boolean -> value.toString()
        is Float, is Double -> value.toString()
        is Type -> value.className + ".class"
        is AnnotationNode -> {
            val inner = linkedMapOf<String, String>()
            val flat = value.values ?: emptyList()
            var index = 0
            while (index + 1 < flat.size) {
                val key = flat[index] as? String
                    ?: throw IllegalArgumentException("malformed nested annotation values")
                inner[key] = renderValue(flat[index + 1])
                index += 2
            }
            val pairs = inner.entries.joinToString(", ") { (key, rendered) -> "$key=$rendered" }
            "@" + mapAnnotationType(value.desc).fqn + if (pairs.isEmpty()) "" else "($pairs)"
        }
        is List<*> -> value.joinToString(", ", "{", "}") { renderValue(it) }
        is Array<*> -> {
            // ASM stores an enum use as String[descriptor, constant-name] — the only
            // array shape AnnotationNode ever produces (real arrays arrive as List).
            val parts = value.toList()
            if (parts.size == 2 && parts.all { it is String }) {
                Type.getType(parts[0] as String).className + "." + (parts[1] as String)
            } else {
                throw IllegalArgumentException("malformed annotation array value")
            }
        }
        else -> throw IllegalArgumentException(
            "unsupported annotation value of ${value?.javaClass?.name ?: "null"}",
        )
    }

    // -- names -----------------------------------------------------------------

    private fun mapTypeName(internalName: String): TypeName =
        typeNameFromBinaryName(internalName.replace('/', '.'))

    private fun mapInternalName(internalName: String): TypeName.ClassType {
        val mapped = mapTypeName(internalName)
        return mapped as? TypeName.ClassType
            ?: throw IllegalArgumentException("not a class name: $internalName")
    }
}
