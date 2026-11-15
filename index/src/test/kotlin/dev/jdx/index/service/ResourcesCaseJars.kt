package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import java.nio.file.Path

/**
 * Crafted resource corpus (issue #25 resource-listing slice): controlled
 * non-class entries for `jdx resources` hit/miss/filter/limit tests,
 * including a versioned `META-INF/versions/` path that must appear as
 * stored (not collapsed).
 */
internal object ResourcesCaseJars {

    fun buildResourcesCaseJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("resources-case.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to """
                    Manifest-Version: 1.0
                    Implementation-Title: CaseLib

                """.trimIndent().replace("\n", "\r\n").toByteArray(),
                "META-INF/services/com.example.Spi" to "com.example.Impl\n".toByteArray(),
                "com/example/messages.properties" to "hello=world\n".toByteArray(),
                // Versioned resource must be listed with its full path.
                "META-INF/versions/11/com/example/messages.properties" to "hello=v11\n".toByteArray(),
                "com/example/Main.class" to byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                ),
            ),
        )

    fun buildOtherResourcesJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("other-resources.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to """
                    Manifest-Version: 1.0

                """.trimIndent().replace("\n", "\r\n").toByteArray(),
                "other/config.xml" to "<cfg/>\n".toByteArray(),
            ),
        )

    fun buildClassOnlyJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("class-only.jar"),
            mapOf(
                "com/example/Empty.class" to byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                ),
            ),
        )
}
