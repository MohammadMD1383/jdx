package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import java.nio.file.Path

/**
 * Crafted services corpus (issue #25): controlled `META-INF/services` files
 * for `jdx services` hit/miss/filter/limit tests.
 */
internal object ServicesCaseJars {

    fun buildServicesCaseJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("services-case.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to ArtifactTestJars.manifestBytes(multiRelease = false),
                "META-INF/services/com.example.Spi" to """
                    # providers for Spi
                    com.example.Alpha
                    com.example.Beta
                """.trimIndent().toByteArray(),
                "META-INF/services/com.example.Other" to "com.example.Gamma\n".toByteArray(),
                // Versioned copy must be ignored in this slice.
                "META-INF/versions/11/META-INF/services/com.example.Spi" to
                    "com.example.VersionedOnly\n".toByteArray(),
                "com/example/Alpha.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
            ),
        )

    fun buildOtherServicesJar(dir: Path): Path =
        ArtifactTestJars.craftJar(
            dir.resolve("other-services.jar"),
            mapOf(
                "META-INF/services/java.sql.Driver" to "com.other.Driver\n".toByteArray(),
            ),
        )
}
