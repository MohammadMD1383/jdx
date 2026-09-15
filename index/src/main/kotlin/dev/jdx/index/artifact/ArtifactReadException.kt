package dev.jdx.index.artifact

/**
 * Thrown when an artifact cannot be opened or an entry cannot be read: missing file, corrupt
 * zip, traversal entry, decompression-cap breach, class missing from the artifact.
 *
 * Errors are values elsewhere in `jdx`, but artifact IO fundamentally throws; the service
 * layer maps this type to exit code 5 (`artifact read error`, D-015). It never carries a
 * stack trace to the user — the message alone must name the artifact and the problem.
 */
public class ArtifactReadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
