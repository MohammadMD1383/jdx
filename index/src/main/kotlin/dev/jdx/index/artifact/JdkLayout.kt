package dev.jdx.index.artifact

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the running JDK's home and its `src.zip` (T-012, PROPOSAL.md §13 step 5).
 *
 * Both [JrtArtifact] (the `jrt:/` root's `jdkSources`) and the `doctor` `jdk-sources`
 * row read through here, so the two can never disagree about where JDK sources live.
 * A missing `src.zip` is routine — some distributions omit it — and reads as `null`,
 * never an error.
 */
public object JdkLayout {

    /**
     * Finds `$home/lib/src.zip`, trying [javaHome] (`java.home`) first, then [envJavaHome]
     * (`$JAVA_HOME`) when it names a different directory.
     *
     * Two homes are needed because the JVM running `jdx` is not always the JDK the user
     * means: a JRE-bundled launcher, a Gradle daemon, or `JAVA_HOME` pointing at a full
     * JDK while `java.home` points at a bare runtime. The first home that ships
     * `lib/src.zip` wins; `java.home` wins ties, matching the launcher's own
     * `JAVA_HOME` → `java` on `PATH` → default order in spirit (D-026).
     *
     * Never throws: unreadable or missing homes simply do not match.
     */
    public fun findSrcZip(javaHome: Path, envJavaHome: Path? = null): Path? {
        val candidates = buildList {
            add(javaHome)
            if (envJavaHome != null && envJavaHome.toAbsolutePath().normalize() !=
                javaHome.toAbsolutePath().normalize()
            ) {
                add(envJavaHome)
            }
        }
        for (home in candidates) {
            val zip = home.resolve("lib/src.zip")
            try {
                if (Files.isRegularFile(zip)) return zip
            } catch (e: Exception) {
                // A home that cannot be stat'ed is treated as absent, not as a failure.
                continue
            }
        }
        return null
    }

    /**
     * Reads `$JAVA_HOME` as a path, or `null` when it is unset or blank.
     * Never throws. [getenv] is injectable so tests need no environment surgery.
     */
    public fun envJavaHome(getenv: (String) -> String? = System::getenv): Path? {
        val raw = try {
            getenv("JAVA_HOME")
        } catch (e: Exception) {
            return null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            Path.of(raw)
        } catch (e: Exception) {
            null
        }
    }
}
