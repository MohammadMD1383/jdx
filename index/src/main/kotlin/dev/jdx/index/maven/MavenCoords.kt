package dev.jdx.index.maven

import dev.jdx.core.model.MavenCoordinate
import java.nio.file.Path

/**
 * Maven `group:artifact:version` coordinates (PROPOSAL.md §13, T-019).
 *
 * Coordinates name dependency jars three ways: on the command line (`--coord g:a:v`),
 * stored in a workspace (`ws create --coord`), or as a prefix scoping one reference
 * (`g:a:v/com.example.Type`). Every entry point here is total — malformed input reads
 * as `null`, never as a throw — so a mistyped coordinate degrades a query (exit 3)
 * instead of aborting it (TESTING.md §7).
 */
public object MavenCoords {

    /** Default Maven Central base URL (trailing slash significant). */
    public const val CENTRAL_BASE_URL: String = "https://repo.maven.apache.org/maven2/"

    /**
     * Parses `group:artifact:version`. Parts are trimmed; each must be non-empty and
     * free of path/URL/glob-hostile characters (`/`, `\`, whitespace, `*?[]{}()#;'"<>|`
     * and `:` beyond the two separators). `..` segments are rejected so a coordinate
     * can never escape its repository directory. Returns `null` on any violation.
     */
    public fun parse(text: String): MavenCoordinate? {
        return try {
            val parts = text.split(":")
            if (parts.size != 3) return null
            val (group, artifact, version) = parts.map { it.trim() }
            if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) return null
            if (!isSafePart(group) || !isSafePart(artifact) || !isSafePart(version)) return null
            MavenCoordinate(group, artifact, version)
        } catch (e: Exception) {
            null
        }
    }

    /** Human-readable reason [parse] rejected [text] (callers report exit 3 with this). */
    public fun invalidReason(text: String): String {
        val parts = text.split(":")
        if (parts.size != 3) {
            return "invalid Maven coordinate '$text': expected group:artifact:version " +
                "(e.g. com.google.code.gson:gson:2.14.0)"
        }
        for (part in parts.map { it.trim() }) {
            if (part.isEmpty()) {
                return "invalid Maven coordinate '$text': expected group:artifact:version " +
                    "(e.g. com.google.code.gson:gson:2.14.0)"
            }
            if (!isSafePart(part)) {
                return "invalid Maven coordinate '$text': '$part' contains a character " +
                    "coordinates must not carry (whitespace, '/', glob or quote characters)"
            }
        }
        return "invalid Maven coordinate '$text': expected group:artifact:version"
    }

    /**
     * Rejects characters that would let a coordinate escape its meaning: path
     * separators, glob wildcards (resolved jars flow into `--jars`-style specs,
     * where `*` would silently become a glob), whitespace, quotes, and `..`.
     * Everything else (`+`, `$`, `~`, …) is legal in real Maven versions and
     * URL-safe in a path segment, so it stays accepted.
     */
    private fun isSafePart(part: String): Boolean {
        if (part == "." || part == "..") return false
        for (char in part) {
            if (char.isWhitespace()) return false
            if (char in "/\\:*?[]{}()#;'\"<>|") return false
        }
        return true
    }

    /** The canonical `group:artifact:version` text for [coordinate]. */
    public fun format(coordinate: MavenCoordinate): String = coordinate.coordinate

    /** `<group-dots-to-slashes>/<artifact>/<version>` — the shared Maven directory layout. */
    public fun repositoryPath(coordinate: MavenCoordinate): String =
        coordinate.group.replace('.', '/') +
            "/${coordinate.artifact}/${coordinate.version}"

    /** `<artifact>-<version>.jar` — the binary file name in `~/.m2` and `<cache-dir>/m2`. */
    public fun binaryFileName(coordinate: MavenCoordinate): String =
        "${coordinate.artifact}-${coordinate.version}.jar"

    /** `<artifact>-<version>-sources.jar` — fetched alongside the binary (PROPOSAL.md §13). */
    public fun sourcesFileName(coordinate: MavenCoordinate): String =
        "${coordinate.artifact}-${coordinate.version}-sources.jar"

    /** The version directory holding [coordinate] under an `~/.m2`-shaped [repoRoot]. */
    public fun m2VersionDir(repoRoot: Path, coordinate: MavenCoordinate): Path =
        repoRoot.resolve(repositoryPath(coordinate))

    /** The binary jar path under an `~/.m2`-shaped [repoRoot] (absent unless downloaded). */
    public fun m2BinaryPath(repoRoot: Path, coordinate: MavenCoordinate): Path =
        m2VersionDir(repoRoot, coordinate).resolve(binaryFileName(coordinate))

    /** The sources jar path under an `~/.m2`-shaped [repoRoot]. */
    public fun m2SourcesPath(repoRoot: Path, coordinate: MavenCoordinate): Path =
        m2VersionDir(repoRoot, coordinate).resolve(sourcesFileName(coordinate))

    /**
     * The download URL for [fileName] of [coordinate] under [repoBaseUrl]
     * (default: Maven Central). [repoBaseUrl] may omit the trailing slash.
     */
    public fun downloadUrl(
        coordinate: MavenCoordinate,
        fileName: String,
        repoBaseUrl: String = CENTRAL_BASE_URL,
    ): String = repoBaseUrl.trimEnd('/') + "/" + repositoryPath(coordinate) + "/" + fileName

    /**
     * Returns true when [url] names a usable Maven repository base URL (T-069):
     * an `http(s)` URL with a host, no whitespace, and no query or fragment
     * (a base is a path prefix — `downloadUrl` joins it with `/`-separated
     * segments, so a `?` or `#` would silently corrupt every fetch URL).
     * The trailing slash is optional. Never throws.
     */
    public fun isValidRepoUrl(url: String): Boolean = invalidRepoReason(url) == null

    /** Human-readable reason [isValidRepoUrl] rejected [url], or `null` when valid. */
    public fun invalidRepoReason(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "invalid --repo '$url': expected an http(s) repository base URL"
        if (trimmed != url || url.any { it.isWhitespace() }) {
            return "invalid --repo '$url': repository URLs must not contain whitespace"
        }
        val uri = try {
            java.net.URI(trimmed)
        } catch (e: Exception) {
            return "invalid --repo '$url': not a valid URL (${e.message ?: e.javaClass.simpleName})"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "invalid --repo '$url': scheme must be http or https"
        }
        if (uri.host.isNullOrEmpty()) {
            return "invalid --repo '$url': URL must have a host"
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return "invalid --repo '$url': repository base URLs must not carry a query or fragment"
        }
        return null
    }
}
