package dev.jdx.sources

import java.io.Closeable
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * The D-008 isolation boundary for Kotlin sources (T-038).
 *
 * `kotlin-compiler-embeddable` is ~55 MB, takes ~1 s to initialise, and shades
 * Guava/IntelliJ-platform classes that collide with everything else — so it is
 * side-loaded at runtime into an isolated [URLClassLoader] (platform parent,
 * never the app loader), never a compile dependency, never in the fat jar.
 * **Nothing outside `:sources` may import Kotlin compiler classes**; callers
 * only see this interface, which T-039 extends with PSI queries.
 *
 * Obtained via [openKotlinParser]: absent/corrupt sidecars read as an
 * unavailable value with an install hint, never a throw (CLAUDE.md §2.8).
 */
public interface KotlinSourceParser : Closeable {
    /** True when the isolated compiler loader opened and presence-checked. */
    public val available: Boolean

    /** Human reason: version+path when available, the install hint otherwise. Single line. */
    public val detail: String
}

/**
 * Opens the Kotlin source parser for [userHome]. Never throws: every failure
 * (missing sidecar, directory at the path, unreadable jar, loader or
 * presence-check failure) becomes an unavailable parser naming the cause.
 *
 * The returned parser holds the isolated loader open; closing it releases the
 * jar handle. Callers that only need the status bit should prefer the cheaper
 * [probeKotlinToolchain].
 */
public fun openKotlinParser(userHome: Path): KotlinSourceParser {
    val status = probeKotlinToolchain(userHome)
    if (status is KotlinToolchainStatus.Missing) {
        return UnavailableKotlinParser(
            "kotlin-compiler-embeddable $KOTLIN_COMPILER_VERSION not installed " +
                "(${status.jar}) — Kotlin sources unavailable (D-008)",
        )
    }
    status as KotlinToolchainStatus.Installed
    var loader: URLClassLoader? = null
    return try {
        loader = URLClassLoader(
            arrayOf(status.jar.toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        )
        // Presence check, never initialisation: proves the jar is a real
        // compiler sidecar without paying the ~1 s PSI init (T-039 pays that
        // once per daemon lifetime, PROPOSAL.md §12.2).
        Class.forName(KOTLIN_PRESENCE_CLASS, false, loader)
        AvailableKotlinParser(loader, status)
    } catch (e: Exception) {
        try {
            loader?.close()
        } catch (ignored: Exception) {
            // Closing a half-opened loader must not mask the real cause.
        }
        UnavailableKotlinParser(
            "kotlin-compiler-embeddable $KOTLIN_COMPILER_VERSION present but unusable " +
                "(${status.jar}: ${e.message ?: e.javaClass.simpleName}) — " +
                "Kotlin sources unavailable (D-008)",
        )
    }
}

/** A compiler class stable across embeddable releases; loaded, never initialised. */
internal const val KOTLIN_PRESENCE_CLASS: String = "org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment"

private class AvailableKotlinParser(
    private val loader: URLClassLoader,
    status: KotlinToolchainStatus.Installed,
) : KotlinSourceParser {
    override val available: Boolean = true
    override val detail: String =
        "kotlin-compiler-embeddable $KOTLIN_COMPILER_VERSION (${status.jar})"

    override fun close() {
        try {
            loader.close()
        } catch (e: Exception) {
            // Release-only path: nothing to degrade to, never throw.
        }
    }
}

private class UnavailableKotlinParser(override val detail: String) : KotlinSourceParser {
    override val available: Boolean = false
    override fun close(): Unit = Unit
}
