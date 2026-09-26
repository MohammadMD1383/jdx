package dev.jdx.testsupport.fixtures

import java.io.File
import java.nio.file.Path

/**
 * Locates the Kotlin sidecar set in the local Gradle module cache (tier-2
 * suites that need the real `kotlin-compiler-embeddable`, never the build).
 *
 * The compiler is deliberately not a build dependency (D-008: side-loaded,
 * never on any classpath), so tests borrow it from the cache the Kotlin
 * Gradle plugin already resolved. The cache root is ambient and differs by
 * host/CI: `GRADLE_USER_HOME` (exported by `gradle/actions/setup-gradle`)
 * wins, then `~/.gradle` — a single hard-coded root misses on runners whose
 * Gradle home is relocated, which reads as a wholesale suite skip.
 * `-Djdx.gradleCaches` overrides both (IDE runs, debugging).
 *
 * Single copy of a table previously triplicated across suites: the file names
 * pin `KOTLIN_COMPILER_VERSION` (`sources`) and the `KOTLIN_SIDECAR_ARTIFACTS`
 * table (`index`) — change all three together.
 */
public object SidecarJars {

    private val wanted: Map<String, String> = mapOf(
        "kotlin-compiler-embeddable-2.4.20.jar" to "org.jetbrains.kotlin/kotlin-compiler-embeddable",
        "kotlin-stdlib-2.4.20.jar" to "org.jetbrains.kotlin/kotlin-stdlib",
        "kotlin-script-runtime-2.4.20.jar" to "org.jetbrains.kotlin/kotlin-script-runtime",
        "kotlin-reflect-1.6.10.jar" to "org.jetbrains.kotlin/kotlin-reflect",
        "kotlin-daemon-embeddable-2.4.20.jar" to "org.jetbrains.kotlin/kotlin-daemon-embeddable",
        "kotlinx-coroutines-core-jvm-1.8.0.jar" to "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm",
        "annotations-13.0.jar" to "org.jetbrains/annotations",
    )

    /** File name of the compiler jar suites gate on (absent → whole-suite skip). */
    public const val COMPILER_JAR: String = "kotlin-compiler-embeddable-2.4.20.jar"

    /** Candidate `modules-2/files-2.1` roots, most explicit first, existing dirs only. */
    internal fun cacheRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        // The override may name several roots (`pathSeparator`-separated).
        System.getProperty("jdx.gradleCaches")
            ?.split(File.pathSeparator)
            ?.forEach { roots.add(File(it)) }
        System.getenv("GRADLE_USER_HOME")?.let { roots.add(File(it, "caches/modules-2/files-2.1")) }
        System.getProperty("user.home")?.let { roots.add(File(it, ".gradle/caches/modules-2/files-2.1")) }
        return roots.filter { it.isDirectory }
    }

    /**
     * Jar name → cached path for every wanted jar found. A jar resolves from
     * the first root (in [cacheRoots] order) whose group dir holds that file
     * name; missing jars are simply absent from the map. Callers gate on
     * [COMPILER_JAR] and skip when the compiler set is absent.
     */
    public fun cachedRuntimeJars(): Map<String, Path> {
        val roots = cacheRoots()
        if (roots.isEmpty()) return emptyMap()
        return wanted.mapNotNull { (jarName, groupPath) ->
            val found = roots.firstNotNullOfOrNull { root ->
                val dir = File(root, groupPath)
                if (dir.isDirectory) {
                    dir.walkTopDown().firstOrNull { it.isFile && it.name == jarName }?.toPath()
                } else {
                    null
                }
            }
            if (found != null) jarName to found else null
        }.toMap()
    }
}
