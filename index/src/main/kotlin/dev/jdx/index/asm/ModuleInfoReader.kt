package dev.jdx.index.asm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.ModuleExportNode
import org.objectweb.asm.tree.ModuleOpenNode
import org.objectweb.asm.tree.ModuleProvideNode
import org.objectweb.asm.tree.ModuleRequireNode

/**
 * Parses a `module-info.class` into a [ParsedModule] (issue #25 module-info
 * slice). Never throws: corrupt / non-module bytes yield `null` so the service
 * can skip the entry and keep scanning (degrade, don't fail).
 *
 * Uses ASM's tree API ([ClassNode.module]) rather than a visitor override:
 * Kotlin's non-null vararg bridge NPEs when ClassReader passes a null
 * `String...` for an unqualified export/open.
 *
 * Package and type names are returned in dotted form; ASM's Module attribute
 * stores them as internal names (`com/foo/Bar`). Module names stay dotted.
 * Directive lists are sorted for determinism (D-007).
 */
public object ModuleInfoReader {

    /**
     * One parsed module descriptor. [flags] on requires are the printable
     * words (`transitive`, `static`, `mandated`, `synthetic`), sorted.
     */
    public data class ParsedModule(
        public val name: String,
        public val open: Boolean,
        public val version: String?,
        public val requires: List<Require>,
        public val exports: List<PackageTarget>,
        public val opens: List<PackageTarget>,
        public val uses: List<String>,
        public val provides: List<Provide>,
    )

    public data class Require(public val module: String, public val flags: List<String>)

    public data class PackageTarget(public val packageName: String, public val to: List<String>)

    public data class Provide(public val service: String, public val providers: List<String>)

    /**
     * Reads [bytes]. Returns `null` when the class file has no `Module`
     * attribute, the magic/header is wrong, or ASM refuses the bytes.
     */
    public fun read(bytes: ByteArray): ParsedModule? {
        if (bytes.size < 8) return null
        if (!(bytes[0] == 0xCA.toByte() && bytes[1] == 0xFE.toByte() &&
                bytes[2] == 0xBA.toByte() && bytes[3] == 0xBE.toByte())
        ) {
            return null
        }
        return try {
            val node = ClassNode()
            ClassReader(bytes).accept(
                node,
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            val module = node.module ?: return null
            ParsedModule(
                name = module.name,
                open = module.access and Opcodes.ACC_OPEN != 0,
                version = module.version,
                requires = (module.requires ?: emptyList()).map { requireOf(it) }
                    .sortedWith(compareBy({ it.module }, { it.flags.joinToString() })),
                exports = (module.exports ?: emptyList()).map { exportOf(it) }
                    .sortedWith(compareBy({ it.packageName }, { it.to.joinToString() })),
                opens = (module.opens ?: emptyList()).map { openOf(it) }
                    .sortedWith(compareBy({ it.packageName }, { it.to.joinToString() })),
                uses = (module.uses ?: emptyList()).map { internalToDotted(it) }.sorted(),
                provides = (module.provides ?: emptyList()).map { provideOf(it) }
                    .sortedWith(compareBy({ it.service }, { it.providers.joinToString() })),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun requireOf(node: ModuleRequireNode): Require =
        Require(module = node.module, flags = requireFlags(node.access))

    private fun exportOf(node: ModuleExportNode): PackageTarget =
        PackageTarget(
            packageName = internalToDotted(node.packaze),
            to = (node.modules ?: emptyList()).sorted(),
        )

    private fun openOf(node: ModuleOpenNode): PackageTarget =
        PackageTarget(
            packageName = internalToDotted(node.packaze),
            to = (node.modules ?: emptyList()).sorted(),
        )

    private fun provideOf(node: ModuleProvideNode): Provide =
        Provide(
            service = internalToDotted(node.service),
            providers = node.providers.map { internalToDotted(it) }.sorted(),
        )

    private fun requireFlags(access: Int): List<String> = buildList {
        if (access and Opcodes.ACC_TRANSITIVE != 0) add("transitive")
        if (access and Opcodes.ACC_STATIC_PHASE != 0) add("static")
        if (access and Opcodes.ACC_SYNTHETIC != 0) add("synthetic")
        if (access and Opcodes.ACC_MANDATED != 0) add("mandated")
    }.sorted()

    /** `com/foo/Bar` → `com.foo.Bar`; already-dotted names pass through. */
    internal fun internalToDotted(internal: String): String = internal.replace('/', '.')
}
