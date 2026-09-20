package dev.jdx.decompile

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pinned Vineflower version. Must match `vineflower` in
 * `gradle/libs.versions.toml` — it is part of every cache key, so a version
 * bump automatically invalidates stale reconstructions. Pinned by
 * `VineflowerVersionTest` against the engine's own version string.
 */
public const val VINEFLOWER_VERSION: String = "1.12.0"

/**
 * The default readable-Java engine (PROPOSAL.md §11.2, T-026): decompiles one
 * class at a time, serves the text from an on-disk cache when possible, and
 * never throws.
 *
 * Loading: Vineflower classes are touched only on a cache *miss* — the common
 * path (sources paired, or cache hit) never pays for them. On the first miss
 * the runner is created inside a child-first [URLClassLoader] over the
 * Vineflower jar, so the engine's static state cannot collide with anything
 * else in the process; if that isolation fails for any reason the runner
 * falls back to the process classloader rather than failing the query.
 *
 * Threading: decompilations are serialised on one lock. Vineflower keeps
 * global static context (`DecompilerContext`) that is not safe under
 * concurrent use, and agent queries arrive sequentially anyway — throughput
 * here is a daemon-era (M6) concern, not a v1 one.
 *
 * Timeouts: one hung class must never hang an agent's tool call (PROPOSAL.md
 * §17). The decompile runs on a daemon worker; on expiry the worker is
 * abandoned (Vineflower ignores interrupts, so `cancel` cannot reclaim it)
 * and the query degrades to [DecompileResult.Failed].
 *
 * @param cache on-disk text cache; [DecompileCache.system] in production, a
 *   temp dir in tests.
 * @param timeout wall-clock budget per cache miss.
 */
public data class VineflowerDecompiler(
    public val cache: DecompileCache = DecompileCache.system(),
    public val timeout: Duration = 30.seconds,
) : DecompilerEngine {

    override val id: DecompilerId = DecompilerId.VINEFLOWER

    override fun decompileClass(
        classBytes: ByteArray,
        binaryName: String,
        classpath: List<Path>,
    ): DecompileResult {
        val entryPath = stagedEntryPath(binaryName)
            ?: return DecompileResult.Failed("invalid class name for decompilation: '$binaryName'")
        if (classBytes.isEmpty()) {
            return DecompileResult.Failed("cannot decompile $binaryName: empty class bytes")
        }
        cache.read(classBytes, id.flag, VINEFLOWER_VERSION)?.let { cached ->
            return DecompileResult.Decompiled(cached, VINEFLOWER_VERSION)
        }
        return when (val run = runWithTimeout(binaryName, classBytes, entryPath, classpath)) {
            is RunOutcome.Ok -> {
                if (run.text.isBlank()) {
                    DecompileResult.Failed("vineflower produced no output for $binaryName")
                } else {
                    cache.write(classBytes, id.flag, VINEFLOWER_VERSION, run.text)
                    DecompileResult.Decompiled(run.text, VINEFLOWER_VERSION)
                }
            }
            is RunOutcome.Failed -> DecompileResult.Failed(run.message)
        }
    }

    private sealed interface RunOutcome {
        data class Ok(val text: String) : RunOutcome
        data class Failed(val message: String) : RunOutcome
    }

    private fun runWithTimeout(
        binaryName: String,
        classBytes: ByteArray,
        entryPath: String,
        classpath: List<Path>,
    ): RunOutcome {
        val future = decompilePool.submit<Map<String, String>> {
            synchronized(runnerLock) {
                val staged = Files.createTempDirectory("jdx-vineflower-")
                try {
                    stageClassFile(staged, entryPath, classBytes)
                    val libraries = classpath.filter { Files.exists(it) }
                    runner().decompile(staged, libraries)
                } finally {
                    deleteStagedDir(staged)
                }
            }
        }
        return try {
            val classes = future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            // The staged dir holds exactly one class file, so exactly one
            // output is expected; anything else is an engine invariant break.
            val text = classes.values.singleOrNull()
            if (text == null) {
                RunOutcome.Failed(
                    "vineflower returned ${classes.size} classes for $binaryName (expected 1)",
                )
            } else {
                RunOutcome.Ok(text)
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            RunOutcome.Failed(
                "vineflower timed out after ${timeout.inWholeSeconds}s decompiling $binaryName",
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            RunOutcome.Failed("vineflower decompilation of $binaryName was interrupted")
        } catch (e: Exception) {
            val cause = (e.cause ?: e).message ?: (e.cause ?: e).javaClass.simpleName
            RunOutcome.Failed("vineflower failed decompiling $binaryName: $cause")
        }
    }

    private fun runner(): VineflowerRunner {
        cachedRunner?.let { return it }
        synchronized(runnerLock) {
            cachedRunner?.let { return it }
            val runner = loadIsolatedRunner() ?: VineflowerRunnerImpl()
            cachedRunner = runner
            return runner
        }
    }

    /**
     * Builds the runner inside a child-first loader over the Vineflower jar,
     * or `null` when isolation is unavailable (the caller falls back to the
     * process classloader). Internal for tests: a non-null return plus a green
     * decompile proves the isolated path is the one taken.
     */
    internal fun loadIsolatedRunner(): VineflowerRunner? {
        return runCatching {
            val parent = VineflowerDecompiler::class.java.classLoader ?: return null
            val jarUrl: URL = Class.forName("org.jetbrains.java.decompiler.api.Decompiler")
                .protectionDomain?.codeSource?.location ?: return null
            val loader = ChildFirstClassLoader(arrayOf(jarUrl), parent)
            val impl = Class.forName("dev.jdx.decompile.VineflowerRunnerImpl", true, loader)
                .getDeclaredConstructor().newInstance()
            impl as? VineflowerRunner
        }.getOrNull()
    }

    private companion object {
        private val runnerLock = Any()

        @Volatile
        private var cachedRunner: VineflowerRunner? = null

        private val decompilePool = Executors.newCachedThreadPool { task ->
            Thread(task, "jdx-vineflower").apply { isDaemon = true }
        }
    }
}

/**
 * One decompilation inside whatever classloader owns the Vineflower classes.
 * Implemented once ([VineflowerRunnerImpl]) and instantiated either in the
 * isolated loader or, as a fallback, in-process — the interface (loaded by
 * the caller's loader) is the only shared type.
 */
internal interface VineflowerRunner {
    /**
     * Decompiles every class under [stagedDir] (normally exactly one).
     * [libraries] are existing jar/dir files for type-context; returns each
     * decompiled class by `/`-joined qualified name.
     */
    fun decompile(stagedDir: Path, libraries: List<Path>): Map<String, String>
}

/**
 * A [ClassLoader] that prefers its own URLs for Vineflower's own packages and
 * delegates everything else to the parent: the engine's static state lives in
 * this loader, while our interfaces, the stdlib and the JDK stay shared.
 * Resources keep the default parent-first order — only classes are isolated,
 * which is exactly the state that must not be shared.
 */
internal class ChildFirstClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    private val isolatedPrefixes: List<String> = listOf(
        "org.jetbrains.java.decompiler.",
        "net.fabricmc.",
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            if (isolatedPrefixes.any { name.startsWith(it) }) {
                try {
                    return findClass(name).also { if (resolve) resolveClass(it) }
                } catch (e: ClassNotFoundException) {
                    // Fall through to the parent below.
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}
