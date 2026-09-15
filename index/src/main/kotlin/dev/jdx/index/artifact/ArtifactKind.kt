package dev.jdx.index.artifact

/**
 * The kinds of places compiled code can be read from (PROPOSAL.md §5.1).
 *
 * `BINARY_JAR` and `CLASS_DIR` hold bytecode; `JRT` is the running JDK itself, read through
 * the `jrt:/` filesystem and paired with `$JAVA_HOME/lib/src.zip` when it exists (T-012 wires
 * the workspace default; this type only exposes the pairing for one JDK root).
 * Sources jars and source dirs are not kinds here — they ride along as a jar's
 * [SourcesPair] or as project inputs to `usages`, never as standalone roots.
 */
public enum class ArtifactKind {
    /** A `.jar`/`.zip` file of compiled classes. */
    BINARY_JAR,

    /** A directory of compiled classes (`build/classes/java/main`, …). */
    CLASS_DIR,

    /** The running JDK, read via the `jrt:/` filesystem. */
    JRT,
}
