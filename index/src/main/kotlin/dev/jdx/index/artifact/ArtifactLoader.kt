package dev.jdx.index.artifact

import java.nio.file.Files
import java.nio.file.Path

/**
 * The one dispatch point for opening artifacts (T-007).
 *
 * ```kotlin
 * ArtifactLoader.open(path).use { root ->
 *     root.classEntryPaths()      // sorted, normalised, MR-resolved
 *     root.openClass("com/foo/Bar.class").use { /* ASM reads, never loads (D-017) */ }
 * }
 * ```
 *
 * Dispatch: a directory opens as [DirArtifact], anything else as [JarArtifact].
 * The JDK root has its own entry point ([openJdk]) since it is not a filesystem path.
 */
public object ArtifactLoader {

    /**
     * Opens [path]: directories as [DirArtifact], files as [JarArtifact].
     *
     * @param explicitSources rule-4 `--sources <path>` override, honoured for jars only.
     * @throws ArtifactReadException when the path names nothing openable (D-015 exit 5
     *   at the service layer).
     */
    public fun open(path: Path, explicitSources: Path? = null): ArtifactRoot {
        if (Files.isDirectory(path)) return DirArtifact.open(path)
        if (Files.isRegularFile(path)) return JarArtifact.open(path, explicitSources)
        throw ArtifactReadException("artifact read error: no such artifact: $path")
    }

    /**
     * Opens a jar explicitly, exposing its [SourcesPair][SourcesPairing].
     *
     * @throws ArtifactReadException when [jar] is not a readable jar.
     */
    public fun openJar(jar: Path, explicitSources: Path? = null): JarArtifact =
        JarArtifact.open(jar, explicitSources)

    /**
     * Opens a class directory explicitly.
     *
     * @throws ArtifactReadException when [dir] is not a directory.
     */
    public fun openDir(dir: Path): DirArtifact = DirArtifact.open(dir)

    /**
     * Opens the running JDK via `jrt:/` (defaulting to this process's `java.home`).
     * Never throws for a missing `src.zip` — that is routine, reported as
     * [JrtArtifact.jdkSources] `null`. [envJavaHome] defaults to `$JAVA_HOME` so a
     * full JDK beside a bare runtime still pairs its sources (T-012).
     */
    public fun openJdk(
        javaHome: Path = Path.of(System.getProperty("java.home")),
        envJavaHome: Path? = JdkLayout.envJavaHome(),
    ): JrtArtifact = JrtArtifact.open(javaHome, envJavaHome)
}
