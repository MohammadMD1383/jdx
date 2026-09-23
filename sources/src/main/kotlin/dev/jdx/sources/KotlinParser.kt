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

    /**
     * Parses one `.kt` text into declarations with real PSI ranges (T-039).
     * The [KotlinFile] offsets index [text] itself, so callers slice
     * ground-truth bytes. Never throws:
     *
     * - [KotlinParse.Unavailable] when this parser is unavailable or the
     *   compiler environment failed to initialise — the caller degrades to
     *   decompile/javap, never a failure;
     * - [KotlinParse.Failed] when the text cannot be walked (a reflection
     *   break, not caller error — PSI itself is error-tolerant and parses
     *   partial trees);
     * - [KotlinParse.Parsed] otherwise, including for texts with syntax
     *   errors (whatever parsed is served; whatever did not is absent).
     */
    public fun parseKotlin(text: String, fileName: String): KotlinParse
}

/**
 * Outcome of [KotlinSourceParser.parseKotlin]: a value on every path, never
 * a throw.
 */
public sealed interface KotlinParse {
    /** The file parsed (possibly partially); offsets index the given text. */
    public data class Parsed(public val file: KotlinFile) : KotlinParse

    /** PSI is unusable here — degrade to decompile/javap, never fail. */
    public data class Unavailable(public val detail: String) : KotlinParse

    /** The text could not be walked; [message] names the cause. */
    public data class Failed(public val message: String) : KotlinParse
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
                "(${status.jar}) — run `jdx kotlin install` to fetch it; " +
                "Kotlin sources unavailable (D-008)",
        )
    }
    status as KotlinToolchainStatus.Installed
    var loader: URLClassLoader? = null
    return try {
        loader = URLClassLoader(
            kotlinLoaderUrls(status.jar),
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

/**
 * The isolated loader's classpath: the sidecar plus every `.jar` sibling in
 * its directory, sorted (T-039). `kotlin-compiler-embeddable` does not bundle
 * the Kotlin stdlib (or its script/reflect/daemon runtime), so a lone
 * compiler jar cannot initialise — the fetch task installs the set together,
 * and the loader reads whatever set is present. Never throws: any listing
 * failure falls back to the sidecar alone (whose environment build then
 * degrades honestly instead of the open call failing).
 */
internal fun kotlinLoaderUrls(sidecar: Path): Array<java.net.URL> {
    val first = try {
        sidecar.toUri().toURL()
    } catch (_: Exception) {
        return emptyArray()
    }
    val dir = try {
        sidecar.toAbsolutePath().parent
    } catch (_: Exception) {
        return arrayOf(first)
    } ?: return arrayOf(first)
    val siblings = try {
        java.nio.file.Files.list(dir).use { stream ->
            stream.filter { path ->
                path.fileName.toString().endsWith(".jar") &&
                    java.nio.file.Files.isRegularFile(path) &&
                    path.fileName.toString() != sidecar.fileName.toString()
            }.sorted().map { it.toUri().toURL() }.toList()
        }
    } catch (_: Exception) {
        emptyList()
    }
    return (listOf(first) + siblings).toTypedArray()
}

private class AvailableKotlinParser(
    private val loader: URLClassLoader,
    status: KotlinToolchainStatus.Installed,
) : KotlinSourceParser {
    override val available: Boolean = true
    override val detail: String =
        "kotlin-compiler-embeddable $KOTLIN_COMPILER_VERSION (${status.jar})"

    private val jarLabel: String = status.jar.toString()
    private val lock: Any = Any()
    private var envState: KotlinEnvState? = null

    override fun parseKotlin(text: String, fileName: String): KotlinParse {
        val state = try {
            synchronized(lock) {
                val current = envState
                if (current != null) {
                    current
                } else {
                    buildKotlinEnv().also { envState = it }
                }
            }
        } catch (e: Exception) {
            return KotlinParse.Failed(
                "Kotlin PSI parse failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        return when (state) {
            is KotlinEnvState.Broken -> KotlinParse.Unavailable(state.detail)
            is KotlinEnvState.Ready -> try {
                val file = buildKotlinFile(state, text)
                if (file != null) {
                    KotlinParse.Parsed(file)
                } else {
                    KotlinParse.Failed("Kotlin PSI parse failed: no declarations for $fileName")
                }
            } catch (e: Exception) {
                KotlinParse.Failed(
                    "Kotlin PSI parse failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    override fun close() {
        val disposable = synchronized(lock) {
            (envState as? KotlinEnvState.Ready)?.disposable
        }
        if (disposable != null) {
            try {
                val disposer = Class.forName(
                    "org.jetbrains.kotlin.com.intellij.openapi.util.Disposer",
                    false,
                    loader,
                )
                disposer.getMethod("dispose", Class.forName(
                    "org.jetbrains.kotlin.com.intellij.openapi.Disposable",
                    false,
                    loader,
                )).invoke(null, disposable)
            } catch (_: Exception) {
                // Release-only path: nothing to degrade to, never throw.
            }
        }
        try {
            loader.close()
        } catch (_: Exception) {
            // Release-only path: nothing to degrade to, never throw.
        }
    }

    private fun buildKotlinEnv(): KotlinEnvState {
        return try {
            fun load(name: String): Class<*> = Class.forName(name, false, loader)
            ensureIdeaHome()
            val disposableClass = load("org.jetbrains.kotlin.com.intellij.openapi.Disposable")
            val disposable = load("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer")
                .getMethod("newDisposable").invoke(null)
                ?: return KotlinEnvState.Broken(
                    kotlinEnvDetail("the compiler disposable is null"),
                )
            val configClass = load("org.jetbrains.kotlin.config.CompilerConfiguration")
            val config = configClass.getDeclaredConstructor().newInstance()
            // The 2.x project bootstrap queries compiler extensions off the
            // configuration (`ExtensionPointUtilsKt.getCompilerExtensions`),
            // so the CLI's extension storage must be registered first — an
            // empty one parses plain sources exactly like the CLI default.
            val storageClass =
                load("org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar\$ExtensionStorage")
            val storage = storageClass.getDeclaredConstructor().newInstance()
            load("org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt")
                .getMethod("setExtensionsStorage", configClass, storageClass)
                .invoke(null, config, storage)
            val filesClass = load("org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles")
            val jvmFiles = filesClass.getField("JVM_CONFIG_FILES").get(null)
            val envClass = load("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
            // The application environment is one per JVM (shared across
            // parsers); the project environment below is per parser.
            envClass.getField("Companion").get(null).let { companion ->
                companion.javaClass.getMethod(
                    "getOrCreateApplicationEnvironmentForProduction",
                    disposableClass,
                    configClass,
                ).invoke(companion, disposable, config)
            }
            val env = envClass.getMethod(
                "createForProduction",
                disposableClass,
                configClass,
                filesClass,
            ).invoke(null, disposable, config, jvmFiles)
                ?: return KotlinEnvState.Broken(kotlinEnvDetail("the compiler environment is null"))
            val project = envClass.getMethod("getProject").invoke(env)
                ?: return KotlinEnvState.Broken(kotlinEnvDetail("the compiler project is null"))
            val factoryClass = load("org.jetbrains.kotlin.psi.KtPsiFactory")
            val factory = factoryClass.getConstructor(
                load("org.jetbrains.kotlin.com.intellij.openapi.project.Project"),
                java.lang.Boolean.TYPE,
            ).newInstance(project, false)
            KotlinEnvState.Ready(disposable, factory, KotlinPsiClasses(loader))
        } catch (e: Exception) {
            KotlinEnvState.Broken(
                kotlinEnvDetail(rootCauseLabel(e)),
            )
        }
    }

    /** Deepest cause label: reflection wraps the real miss in layers of `null` messages. */
    private fun rootCauseLabel(e: Throwable): String {
        var current = e
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < 8) {
            current = current.cause!!
            depth++
        }
        val message = current.message?.take(160)
        return if (message != null) "${current.javaClass.simpleName}: $message" else current.javaClass.simpleName
    }

    private fun kotlinEnvDetail(cause: String): String =
        "kotlin-compiler-embeddable $KOTLIN_COMPILER_VERSION present but failed to initialise " +
            "($jarLabel: $cause) — Kotlin sources unavailable (D-008)"

    /**
     * Points the IntelliJ platform's `PathManager` at a home directory
     * (T-039): without `idea.home.path` (or a `product-info.json` the
     * sidecar does not ship) the application environment fails with "Could
     * not find installation home path". Set once per JVM and only when
     * absent — a host that already defines it keeps its value. Best effort:
     * a failure here surfaces as the env failure, never a throw.
     */
    private fun ensureIdeaHome() {
        try {
            if (System.getProperty("idea.home.path") != null) return
            val home = try {
                java.nio.file.Path.of(jarLabel).toAbsolutePath().parent
                    .resolve("idea-home")
                    .also { java.nio.file.Files.createDirectories(it) }
            } catch (_: Exception) {
                return
            }
            System.setProperty("idea.home.path", home.toString())
        } catch (_: Exception) {
            // Best effort only; the env build reports the real failure.
        }
    }
}

/** The lazily built compiler environment behind one available parser. */
private sealed interface KotlinEnvState {
    /** The PSI factory plus everything its teardown needs. */
    public data class Ready(
        val disposable: Any,
        val factory: Any,
        val classes: KotlinPsiClasses,
    ) : KotlinEnvState

    /** Initialisation failed once; every later parse degrades with [detail]. */
    public data class Broken(val detail: String) : KotlinEnvState
}

/**
 * The compiler PSI classes a parse walk touches, loaded by name so the
 * module keeps no compile dependency (D-008 §1, D-054 §4). A `null` entry
 * disables its branch — a newer compiler renaming a class degrades that
 * declaration kind, never the whole file.
 */
private class KotlinPsiClasses(private val loader: ClassLoader) {
    private fun load(name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: Exception) {
        null
    }

    val namedFunction: Class<*>? = load("org.jetbrains.kotlin.psi.KtNamedFunction")
    val property: Class<*>? = load("org.jetbrains.kotlin.psi.KtProperty")
    val klass: Class<*>? = load("org.jetbrains.kotlin.psi.KtClass")
    val obj: Class<*>? = load("org.jetbrains.kotlin.psi.KtObjectDeclaration")
    val secondary: Class<*>? = load("org.jetbrains.kotlin.psi.KtSecondaryConstructor")
    val primary: Class<*>? = load("org.jetbrains.kotlin.psi.KtPrimaryConstructor")
    val enumEntry: Class<*>? = load("org.jetbrains.kotlin.psi.KtEnumEntry")
}

/** Reflective no-arg call, or `null` when absent (stale-tree tolerant). */
private fun Any.psiCall(name: String): Any? = try {
    this.javaClass.getMethod(name).invoke(this)
} catch (_: Exception) {
    null
}

private fun buildKotlinFile(state: KotlinEnvState.Ready, text: String): KotlinFile? {
    val ktFile = try {
        state.factory.javaClass.getMethod("createFile", String::class.java).invoke(state.factory, text)
    } catch (_: Exception) {
        return null
    } ?: return null
    val declarations = ktFile.psiCall("getDeclarations") as? List<*> ?: return null
    val packageName = (ktFile.psiCall("getPackageFqName")?.psiCall("asString") as? String) ?: ""
    return KotlinFile(
        packageName = packageName,
        declarations = declarations.mapNotNull { buildKotlinDecl(state.classes, it) },
    )
}

private fun buildKotlinDecl(classes: KotlinPsiClasses, node: Any?): KotlinDecl? {
    if (node == null) return null
    return try {
        buildKotlinDeclOrThrow(classes, node)
    } catch (_: Exception) {
        // One hostile subtree degrades to absent, never kills the file.
        null
    }
}

private fun buildKotlinDeclOrThrow(classes: KotlinPsiClasses, node: Any): KotlinDecl? {
    // `KtEnumEntry` extends `KtClass`: the entry check runs first or every
    // enum constant would read as a class.
    return when {
        classes.primary?.isInstance(node) == true -> buildKotlinCtor(node, primary = true)
        classes.secondary?.isInstance(node) == true -> buildKotlinCtor(node, primary = false)
        classes.namedFunction?.isInstance(node) == true -> buildKotlinFunction(node)
        classes.property?.isInstance(node) == true -> buildKotlinProperty(node)
        classes.enumEntry?.isInstance(node) == true -> buildKotlinEnumEntry(node)
        classes.klass?.isInstance(node) == true -> buildKotlinClass(classes, node)
        classes.obj?.isInstance(node) == true -> buildKotlinObject(classes, node)
        else -> null
    }
}

private fun kotlinRangeOf(node: Any): Pair<Int, Int>? {
    val range = node.psiCall("getTextRange") ?: return null
    val start = range.psiCall("getStartOffset") as? Int ?: return null
    val end = range.psiCall("getEndOffset") as? Int ?: return null
    if (start < 0 || end < start) return null
    return start to end
}

private fun kotlinNameOf(node: Any): String? {
    // Anonymous objects and destructuring declarations have no name: their
    // members are unreachable by binary name, so the subtree is dropped.
    val name = node.psiCall("getName") as? String ?: return null
    return name.ifEmpty { null }
}

private data class KotlinDoc(val text: String?, val start: Int?, val end: Int?)

private fun kotlinDocOf(node: Any): KotlinDoc {
    val doc = node.psiCall("getDocComment") ?: return KotlinDoc(null, null, null)
    val raw = doc.psiCall("getText") as? String ?: return KotlinDoc(null, null, null)
    val range = kotlinRangeOf(doc)
    return KotlinDoc(kdocInnerOf(raw), range?.first, range?.second)
}

private fun kotlinModifiersOf(node: Any): String {
    val list = node.psiCall("getModifierList") ?: return ""
    return list.psiCall("getText") as? String ?: ""
}

private fun kotlinParamsOf(node: Any): List<KotlinParam> {
    val params = node.psiCall("getValueParameters") as? List<*> ?: return emptyList()
    return params.mapNotNull { param ->
        if (param == null) return@mapNotNull null
        try {
            val typeRef = param.psiCall("getTypeReference")
            KotlinParam(
                name = (param.psiCall("getName") as? String) ?: "",
                typeText = typeRef?.psiCall("getText") as? String,
                hasDefault = (param.psiCall("hasDefaultValue") as? Boolean) == true,
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun kotlinTypeTextOf(node: Any): String? =
    (node.psiCall("getTypeReference")?.psiCall("getText") as? String)?.takeIf { it.isNotBlank() }

private fun buildKotlinFunction(node: Any): KotlinDecl? {
    val (start, end) = kotlinRangeOf(node) ?: return null
    val name = kotlinNameOf(node) ?: return null
    val doc = kotlinDocOf(node)
    return KotlinDecl(
        kind = KotlinDeclKind.FUNCTION,
        name = name,
        params = kotlinParamsOf(node),
        returnType = kotlinTypeTextOf(node),
        receiverType = (node.psiCall("getReceiverTypeReference")?.psiCall("getText") as? String)
            ?.takeIf { it.isNotBlank() },
        isSuspend = containsWord(kotlinModifiersOf(node), "suspend"),
        startOffset = start,
        endOffset = end,
        docText = doc.text,
        docStartOffset = doc.start,
        docEndOffset = doc.end,
    )
}

private fun buildKotlinProperty(node: Any): KotlinDecl? {
    val (start, end) = kotlinRangeOf(node) ?: return null
    val name = kotlinNameOf(node) ?: return null
    val doc = kotlinDocOf(node)
    return KotlinDecl(
        kind = KotlinDeclKind.PROPERTY,
        name = name,
        returnType = kotlinTypeTextOf(node),
        startOffset = start,
        endOffset = end,
        docText = doc.text,
        docStartOffset = doc.start,
        docEndOffset = doc.end,
    )
}

private fun buildKotlinEnumEntry(node: Any): KotlinDecl? {
    val (start, end) = kotlinRangeOf(node) ?: return null
    val name = kotlinNameOf(node) ?: return null
    val doc = kotlinDocOf(node)
    return KotlinDecl(
        kind = KotlinDeclKind.ENUM_ENTRY,
        name = name,
        startOffset = start,
        endOffset = end,
        docText = doc.text,
        docStartOffset = doc.start,
        docEndOffset = doc.end,
    )
}

private fun buildKotlinCtor(node: Any, primary: Boolean): KotlinDecl? {
    val (start, end) = kotlinRangeOf(node) ?: return null
    val doc = kotlinDocOf(node)
    return KotlinDecl(
        kind = KotlinDeclKind.CONSTRUCTOR,
        name = "<init>",
        params = kotlinParamsOf(node),
        startOffset = start,
        endOffset = end,
        docText = doc.text,
        docStartOffset = doc.start,
        docEndOffset = doc.end,
    )
}

private fun buildKotlinClass(classes: KotlinPsiClasses, node: Any): KotlinDecl? {
    val (start, end) = kotlinRangeOf(node) ?: return null
    val name = kotlinNameOf(node) ?: return null
    val doc = kotlinDocOf(node)
    val primaryNode = node.psiCall("getPrimaryConstructor")
    val explicit = (node.psiCall("hasExplicitPrimaryConstructor") as? Boolean) == true
    return KotlinDecl(
        kind = KotlinDeclKind.CLASS,
        name = name,
        startOffset = start,
        endOffset = end,
        docText = doc.text,
        docStartOffset = doc.start,
        docEndOffset = doc.end,
        children = kotlinChildrenOf(classes, node),
        primaryCtor = if (explicit) {
            primaryNode?.let { buildKotlinDecl(classes, it) }
        } else {
            null
        },
    )
}

private fun buildKotlinObject(classes: KotlinPsiClasses, node: Any): KotlinDecl? {
    val (start, end) = kotlinRangeOf(node) ?: return null
    val name = kotlinNameOf(node) ?: return null
    val doc = kotlinDocOf(node)
    val companion = (node.psiCall("isCompanion") as? Boolean)
        ?: containsWord(kotlinModifiersOf(node), "companion")
    return KotlinDecl(
        kind = KotlinDeclKind.OBJECT,
        name = name,
        startOffset = start,
        endOffset = end,
        docText = doc.text,
        docStartOffset = doc.start,
        docEndOffset = doc.end,
        children = kotlinChildrenOf(classes, node),
        isCompanion = companion,
    )
}

private fun kotlinChildrenOf(classes: KotlinPsiClasses, node: Any): List<KotlinDecl> {
    val declarations = node.psiCall("getDeclarations") as? List<*> ?: return emptyList()
    return declarations.mapNotNull { child ->
        // The explicit primary constructor is carried as `primaryCtor`, not
        // as a child — otherwise `<init>` would match twice.
        if (child != null && classes.primary?.isInstance(child) == true) return@mapNotNull null
        buildKotlinDecl(classes, child)
    }
}

private class UnavailableKotlinParser(override val detail: String) : KotlinSourceParser {
    override val available: Boolean = false
    override fun parseKotlin(text: String, fileName: String): KotlinParse =
        KotlinParse.Unavailable(detail)

    override fun close(): Unit = Unit
}
