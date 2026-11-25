package dev.jdx.testsupport.fixtures

import java.io.File

/**
 * The multi-release fixture jar (issue #29), built by `:testfixtures:mrJar`
 * into `build/mr-fixtures/`.
 *
 * Same two traps as [DiffFixtureJars]: not `build/libs`, and not a
 * `testfixtures-` name, so the main corpus's `require(jars.size == 1)` stays
 * true. The archive is reproducible (the module sets
 * `isPreserveFileTimestamps = false` for every archive).
 */
object MrFixtureJars {

    /** System property carrying the directory into the test JVM, when wired. */
    const val DIR_PROPERTY: String = "jdx.mrFixturesDir"

    /** The compiled multi-release jar. */
    fun jar(dir: File = dir()): File {
        val jars = dir.listFiles { file ->
            file.isFile && file.extension == "jar" && file.name.startsWith("mr-fixtures-")
        }?.toList().orEmpty()
        require(jars.size == 1) {
            "expected exactly one mr-fixtures jar in $dir, found: $jars (build :testfixtures:mrJar)"
        }
        return jars.single()
    }

    /** The directory, or an error naming how to produce it. */
    fun dir(): File =
        dirOrNull() ?: error(
            "multi-release fixture jar not found: set -D$DIR_PROPERTY=<dir> or build " +
                ":testfixtures:mrJar first",
        )

    /**
     * The directory from [DIR_PROPERTY], else by walking up from the working
     * directory looking for `testfixtures/build/mr-fixtures`. Same resolution
     * as [DiffFixtureJars.dirOrNull].
     */
    fun dirOrNull(): File? {
        System.getProperty(DIR_PROPERTY)?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/mr-fixtures")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
