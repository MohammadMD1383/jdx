package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.craftModuleInfo
import org.objectweb.asm.Opcodes
import java.nio.file.Path

/**
 * Crafted modules corpus (issue #25 module-info slice): controlled
 * `module-info.class` files for `jdx modules` hit/miss/filter/limit tests.
 */
internal object ModulesCaseJars {

    fun buildModulesCaseJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("modules-case.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to ArtifactTestJars.manifestBytes(multiRelease = false),
                "module-info.class" to craftModuleInfo(
                    name = "com.example.mod",
                    open = true,
                    version = "1.0",
                    requires = listOf(
                        Triple("java.base", Opcodes.ACC_MANDATED, null),
                        Triple("java.logging", Opcodes.ACC_TRANSITIVE, null),
                    ),
                    exports = listOf(
                        "com/example/api" to emptyArray(),
                        "com/example/spi" to arrayOf("com.friend"),
                    ),
                    opens = listOf("com/example/internal" to arrayOf("com.friend")),
                    uses = listOf("com/example/Spi"),
                    provides = listOf(
                        "com/example/Spi" to arrayOf("com/example/Impl"),
                    ),
                ),
                "com/example/Impl.class" to byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                ),
            ),
        )

    fun buildOtherModulesJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("other-modules.jar"),
            mapOf(
                "module-info.class" to craftModuleInfo(
                    name = "com.other.lib",
                    requires = listOf(Triple("java.base", Opcodes.ACC_MANDATED, null)),
                    exports = listOf("com/other" to emptyArray()),
                ),
            ),
        )
}
