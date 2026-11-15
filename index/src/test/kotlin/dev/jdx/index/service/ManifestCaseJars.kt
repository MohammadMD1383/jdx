package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import java.nio.file.Path

/**
 * Crafted manifest corpus (issue #25 manifest slice): controlled
 * `META-INF/MANIFEST.MF` files for `jdx manifest` hit/miss/filter/limit tests.
 */
internal object ManifestCaseJars {

    fun buildManifestCaseJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("manifest-case.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to """
                    Manifest-Version: 1.0
                    Implementation-Title: CaseLib
                    Implementation-Version: 1.2.3
                    Main-Class: com.example.Main
                    Multi-Release: true

                """.trimIndent().replace("\n", "\r\n").toByteArray(),
                // Versioned copy must be ignored in this slice.
                "META-INF/versions/11/META-INF/MANIFEST.MF" to """
                    Manifest-Version: 1.0
                    Versioned-Only: yes

                """.trimIndent().replace("\n", "\r\n").toByteArray(),
                "com/example/Main.class" to byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                ),
            ),
        )

    fun buildOtherManifestJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("other-manifest.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to """
                    Manifest-Version: 1.0
                    Bundle-SymbolicName: com.other.bundle

                """.trimIndent().replace("\n", "\r\n").toByteArray(),
            ),
        )

    fun buildNoManifestJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("no-manifest.jar"),
            mapOf(
                "com/example/Empty.class" to byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                ),
            ),
        )
}
