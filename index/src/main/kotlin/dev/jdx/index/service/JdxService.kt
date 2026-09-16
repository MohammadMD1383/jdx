package dev.jdx.index.service

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.ModuleSymbolRef
import dev.jdx.core.model.Origin
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSymbolRef
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.ref.SymbolRefParser
import dev.jdx.core.ref.SymbolRefParseResult
import dev.jdx.core.render.ClassCard
import dev.jdx.core.render.DEFAULT_MEMBER_LIMIT
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.MemberListing
import dev.jdx.core.render.MemberListingOptions
import dev.jdx.core.render.OBJECT_BINARY_NAME
import dev.jdx.core.render.buildClassCard
import dev.jdx.core.render.buildMemberListing
import dev.jdx.core.resolve.MemberResolutionOptions
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.core.resolve.ResolvedMembers
import dev.jdx.index.artifact.ArtifactKind
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactReadException
import dev.jdx.index.artifact.ArtifactRoot
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The behaviour behind `jdx show`, `jdx outline` and `jdx members` (T-011).
 *
 * This is the `JdxService` facade PROPOSAL.md §9.2 promises: every CLI/MCP/HTTP
 * adapter calls here, so the four front-ends cannot drift (D-004). There is no
 * persistent index yet (M2) — each query opens its roots, reads the classes it
 * needs with ASM (never loading them, D-017), resolves, renders and closes.
 *
 * Roots are classpath-ordered: `--jars` values in CLI order, then the JDK
 * (`--no-jdk` drops it). The first root providing a class wins; further
 * providers become a `DUPLICATE_FQN` warning, mirroring JVM shadowing.
 */
public object JdxService {

    /** Which roots a query reads: explicit jar/dir/glob specs plus the JDK unless dropped. */
    public data class RootsSpec(
        public val jarSpecs: List<String> = emptyList(),
        public val includeJdk: Boolean = true,
        /** Warnings contributed by root resolution itself (e.g. discovery fallback). */
        public val extraWarnings: List<Warning> = emptyList(),
    ) {
        public companion object {
            /** Converts a resolved workspace selection into the roots a query opens. */
            public fun fromResolved(
                resolved: dev.jdx.index.workspace.WorkspaceResolver.ResolvedRoots,
            ): RootsSpec = RootsSpec(
                jarSpecs = resolved.jarSpecs,
                includeJdk = resolved.includeJdk,
                extraWarnings = resolved.warnings,
            )
        }
    }

    /** `--kind` values for the members family (PROPOSAL.md §7.1). */
    public enum class KindFilter(public val flag: String) {
        ALL("all"),
        METHOD("method"),
        FIELD("field"),
        CTOR("ctor"),
    }

    /**
     * Filters applied to the resolved member set before rendering. `access = null`
     * means the proposal default (`public` + `protected`); `--access all` passes
     * the full set explicitly. `staticOnly = null` means both.
     * [fromRef] is the raw `--from` reference text, resolved against the same
     * workspace as the query itself.
     */
    public data class MemberFilters(
        public val kind: KindFilter = KindFilter.ALL,
        public val access: Set<Visibility>? = null,
        public val staticOnly: Boolean? = null,
        public val fromRef: String? = null,
        public val grep: Regex? = null,
    ) {
        public companion object {
            /** The §7.1 default: `public` + `protected` only. */
            public val DEFAULT_ACCESS: Set<Visibility> = setOf(Visibility.PUBLIC, Visibility.PROTECTED)
        }
    }

    /** One query's answer, carrying its own exit code and both renderings (D-007). */
    public sealed interface ServiceOutcome {
        /** The process exit code (D-015). */
        public val exitCode: Int

        /** Human-readable text; [color] is TTY-ness passed in by the adapter (D-028). */
        public fun renderText(color: Boolean = false): String

        /** The shared JSON envelope for [command]. */
        public fun toJson(command: String): String

        /** A member listing (`members`, `outline`) — exit 0. */
        public data class MemberList(public val listing: MemberListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A class card (`show`) — exit 0. */
        public data class Card(public val card: ClassCard) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = card.renderText(color)
            override fun toJson(command: String): String = card.toJson(command)
        }

        /** A machine-legible failure — exit 1..6, never a guess, never a trace. */
        public data class Failure(public val error: ErrorResult) : ServiceOutcome {
            override val exitCode: Int = error.exitCode
            override fun renderText(color: Boolean): String = error.renderText()
            override fun toJson(command: String): String = error.toJson(command)
        }
    }

    /**
     * Answers `show <type>`: the class card. [rawRef] must be a type reference;
     * anything else is a usage error (exit 3).
     */
    public fun show(rawRef: String, roots: RootsSpec): ServiceOutcome =
        query(rawRef, roots, QueryMode.SHOW, MemberFilters(), DEFAULT_MEMBER_LIMIT)

    /**
     * Answers `members <type>`: the inherited member listing honouring [filters].
     * `--inherited` is the default; pass [declaredOnly] for `--declared`.
     */
    public fun members(
        rawRef: String,
        roots: RootsSpec,
        filters: MemberFilters = MemberFilters(),
        declaredOnly: Boolean = false,
        includeSynthetic: Boolean = false,
        maxMembers: Int = DEFAULT_MEMBER_LIMIT,
    ): ServiceOutcome =
        query(rawRef, roots, QueryMode.MEMBERS, filters, maxMembers, declaredOnly, includeSynthetic)

    /**
     * Answers `outline <type>`: the members declared on the type itself —
     * `members --declared` under a different command name.
     */
    public fun outline(
        rawRef: String,
        roots: RootsSpec,
        filters: MemberFilters = MemberFilters(),
        includeSynthetic: Boolean = false,
        maxMembers: Int = DEFAULT_MEMBER_LIMIT,
    ): ServiceOutcome =
        query(rawRef, roots, QueryMode.OUTLINE, filters, maxMembers, declaredOnly = true, includeSynthetic)

    // -- query pipeline ---------------------------------------------------------

    private enum class QueryMode(val flag: String) {
        SHOW("show"),
        MEMBERS("members"),
        OUTLINE("outline"),
    }

    private fun query(
        rawRef: String,
        roots: RootsSpec,
        mode: QueryMode,
        filters: MemberFilters,
        maxMembers: Int,
        declaredOnly: Boolean = false,
        includeSynthetic: Boolean = false,
    ): ServiceOutcome {
        if (maxMembers < 0) {
            return failure(3, rawRef, "usage error: --limit must be >= 0, got $maxMembers")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is MemberSymbolRef) {
            return failure(
                3,
                rawRef,
                "usage error: ${mode.flag} takes a type, got member reference '$rawRef'",
            )
        }
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: ${mode.flag} takes a type, got '$rawRef'")
        }
        val typeRef = ref as TypeSymbolRef
        if (typeRef.coordinate != null) {
            return failure(
                3,
                rawRef,
                "usage error: Maven coordinates are not yet implemented (T-019): '$rawRef'",
            )
        }
        val typeName = typeRef.type as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: ${mode.flag} takes a class, got '$rawRef'")
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            execute(typeName, rawRef, roots, mode, filters, maxMembers, declaredOnly, includeSynthetic)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    private fun failure(code: Int, query: String, message: String): ServiceOutcome.Failure =
        ServiceOutcome.Failure(ErrorResult.generic(query, message = message, exitCode = code))

    /** An opened root plus its JDK-module lookup (non-null for `jrt:/` only). */
    private data class OpenRoot(
        val root: ArtifactRoot,
        val jrtModuleFor: ((String) -> String?)? = null,
    )

    private fun execute(
        typeName: TypeName.ClassType,
        rawRef: String,
        roots: RootsSpec,
        mode: QueryMode,
        filters: MemberFilters,
        maxMembers: Int,
        declaredOnly: Boolean,
        includeSynthetic: Boolean,
    ): ServiceOutcome {
        val opened = openRoots(roots)
        try {
            // Cheap entry index first (names only, no parsing): powers exact
            // lookup, short-name search, duplicates and did-you-mean alike.
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(typeName, allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(typeName.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            // Resolution-time warnings (e.g. project-discovery fallback) lead, so the
            // agent sees why the root set looks the way it does before artifact notes.
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                // The entry exists but its bytes cannot serve the query.
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            // `--from` names a second type; resolve it with the same machinery so
            // a mistyped supertype reports itself instead of matching nothing.
            val fromBinary = filters.fromRef?.let { from ->
                val parsedFrom = SymbolRefParser.parse(from)
                val fromType = (parsedFrom as? SymbolRefParseResult.Ok)?.ref as? TypeSymbolRef
                val fromClass = fromType?.type as? TypeName.ClassType
                if (fromClass == null) {
                    return failure(3, from, "usage error: invalid --from type '$from'")
                }
                val fromCandidates = matchCandidates(fromClass, allBinaries)
                if (fromCandidates.isEmpty()) {
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(from, suggestSimilar(fromClass.simpleName, allBinaries)),
                    )
                }
                if (fromCandidates.size > 1) {
                    return ServiceOutcome.Failure(ErrorResult.ambiguous(from, fromCandidates))
                }
                fromCandidates.single()
            }

            val provenance = listOf(
                Provenance(
                    artifact = rootLabel(opened[winner], binary),
                    origin = if (opened[winner].root.kind == ArtifactKind.JRT) Origin.JRT else Origin.BYTECODE,
                ),
            )
            val sortedWarnings = warnings.sortedBy { it.code }
            if (mode == QueryMode.SHOW) {
                return ServiceOutcome.Card(buildClassCard(target, provenance, sortedWarnings))
            }
            val resolved = MemberResolver.resolve(
                target,
                workspace::loadByName,
                MemberResolutionOptions(includeSynthetic = includeSynthetic),
            )
            val filtered = applyFilters(resolved, filters.copy(fromRef = null), fromBinary)
            val listing = buildMemberListing(
                target = target,
                resolved = filtered,
                provenance = provenance,
                options = MemberListingOptions(
                    declaredOnly = declaredOnly,
                    // An explicit `--from java.lang.Object` is the documented escape
                    // hatch out of the collapse (PROPOSAL.md §7.1) — collapsing
                    // after it would hide exactly what was asked for.
                    collapseObjectMembers = fromBinary != OBJECT_BINARY_NAME,
                    maxMembers = maxMembers,
                ),
            )
            // Resolver warnings (UNRESOLVED_SUPERTYPE) join the artifact ones.
            val merged = listing.copy(warnings = (sortedWarnings + listing.warnings).sortedBy { it.code })
            return ServiceOutcome.MemberList(merged)
        } finally {
            opened.forEach { it.root.close() }
        }
    }

    private fun rootLabel(open: OpenRoot, binary: String): String {
        // T-012 reports the JDK module as the artifact (e.g. `java.base`).
        val module = open.jrtModuleFor?.invoke(entryForBinary(binary))
        if (module != null) return module
        return open.root.displayName
    }

    // -- workspace: lazy per-class loading over open roots ----------------------

    /**
     * Reads classes on demand (one ASM parse per class, cached): a `members`
     * query touches the target plus its supertypes, never the whole artifact —
     * which is what keeps `jdx members java.util.HashMap` cheap with no index.
     * Parse failures read as missing (resolver reports the hole) and are warned
     * once each; the *target* failing is the caller's artifact error instead.
     */
    private class Workspace(
        private val opened: List<OpenRoot>,
        private val providers: Map<String, List<Int>>,
        private val warnings: MutableList<Warning>,
    ) {
        private val cache = mutableMapOf<String, ClassInfo?>()
        private val reported = mutableSetOf<String>()

        fun load(binary: String): ClassInfo? {
            val winner = providers[binary]?.firstOrNull() ?: return null
            return cache.getOrPut(binary) { readFrom(opened[winner], binary) }
        }

        fun loadByName(name: TypeName): ClassInfo? {
            val binary = (name as? TypeName.ClassType)?.binaryName ?: return null
            return load(binary)
        }

        private fun readFrom(open: OpenRoot, binary: String): ClassInfo? {
            val bytes = try {
                open.root.openClass(entryForBinary(binary)).use { it.readBytes() }
            } catch (e: ArtifactReadException) {
                if (reported.add(binary)) {
                    warnings.add(
                        Warning(
                            WarningCode.CORRUPT_CLASS,
                            "cannot read $binary from ${open.root.displayName}: ${e.message}",
                            binary,
                        ),
                    )
                }
                return null
            }
            val hint = "$binary in ${open.root.displayName}"
            return when (val read = AsmClassReader.read(bytes, hint)) {
                is ClassReadResult.Ok -> read.info
                is ClassReadResult.UnsupportedVersion -> {
                    if (reported.add(binary)) warnings.add(read.warning.copy(subject = binary))
                    null
                }
                is ClassReadResult.Corrupt -> {
                    if (reported.add(binary)) warnings.add(read.warning.copy(subject = binary))
                    null
                }
            }
        }
    }

    // -- roots ------------------------------------------------------------------

    private fun openRoots(spec: RootsSpec): List<OpenRoot> {
        val opened = mutableListOf<OpenRoot>()
        try {
            for (jarSpec in spec.jarSpecs) {
                for (path in expandJarSpec(jarSpec)) {
                    opened.add(OpenRoot(ArtifactLoader.open(path)))
                }
            }
            if (spec.includeJdk) {
                val jrt = ArtifactLoader.openJdk()
                opened.add(OpenRoot(jrt, jrtModuleFor = { entry -> jrt.moduleForClass(entry) }))
            }
            return opened
        } catch (e: Exception) {
            opened.forEach { runCatching { it.root.close() } }
            throw e
        }
    }

    /**
     * Expands one `--jars` value: a plain jar/dir path, or a glob (`*`, `?`,
     * `[`, `{`). A leading `~` expands to the home directory. An empty glob
     * match is an artifact error, not silence — the agent asked for something
     * that names nothing.
     */
    internal fun expandJarSpec(spec: String): List<Path> {
        val expanded = if (spec.startsWith("~/") || spec == "~") {
            System.getProperty("user.home") + spec.substring(1)
        } else {
            spec
        }
        if (!expanded.any { it == '*' || it == '?' || it == '[' || it == '{' }) {
            val path = Path.of(expanded)
            if (!path.isRegularFile() && !path.isDirectory()) {
                throw ArtifactReadException("artifact read error: no such artifact: $spec")
            }
            return listOf(path)
        }
        val absolute = Path.of(expanded).toAbsolutePath().normalize().toString()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$absolute")
        val firstGlob = absolute.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
        val slash = absolute.lastIndexOf('/', firstGlob)
        val walkRoot = if (slash <= 0) Path.of("/") else Path.of(absolute.substring(0, slash))
        if (!Files.isDirectory(walkRoot)) {
            throw ArtifactReadException("artifact read error: no artifacts match: $spec")
        }
        val hits = mutableListOf<Path>()
        Files.walk(walkRoot).use { walk ->
            walk.filter { candidate ->
                matcher.matches(candidate) &&
                    (candidate.isDirectory() || isJarFile(candidate))
            }.forEach { hits.add(it) }
        }
        if (hits.isEmpty()) throw ArtifactReadException("artifact read error: no artifacts match: $spec")
        return hits.sorted()
    }

    private fun isJarFile(path: Path): Boolean {
        if (!path.isRegularFile()) return false
        val name = path.fileName.toString()
        return name.endsWith(".jar") || name.endsWith(".zip")
    }

    // -- name matching (pure, unit-tested) --------------------------------------

    /**
     * Resolves a parsed type name against the workspace's binary names.
     * A packaged name is exact; a short name matches every class with the same
     * simple (or nested `$`-joined) name. Sorted, so ambiguity lists are stable.
     */
    internal fun matchCandidates(ref: TypeName.ClassType, binaries: Set<String>): List<String> {
        if (ref.packageName.isNotEmpty()) {
            return if (ref.binaryName in binaries) listOf(ref.binaryName) else emptyList()
        }
        val nested = ref.nestedNames.joinToString("$")
        return binaries.filter { binary ->
            binary.substringAfterLast('.') == nested
        }.sorted()
    }

    /**
     * Did-you-mean suggestions for a missed type: same simple name in another
     * package first, then Levenshtein-near simple names — capped, sorted.
     */
    internal fun suggestSimilar(missedSimple: String, binaries: Set<String>, cap: Int = 5): List<String> {
        val bySimple = binaries.groupBy { it.substringAfterLast('.').substringAfterLast('$') }
        val sameName = (bySimple[missedSimple] ?: emptyList()).sorted()
        if (sameName.size >= cap) return sameName.take(cap)
        val near = bySimple.keys
            .filter { it != missedSimple && levenshtein(it, missedSimple) <= 2 }
            .sorted()
            .flatMap { bySimple.getValue(it).sorted() }
        return (sameName + near).take(cap)
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    // -- member filtering (pure, unit-tested) -----------------------------------

    /**
     * Applies the §7.1 `members` filters to a resolved set: kind, access level
     * (default public+protected), static/instance, `--from` declaring type and
     * `--grep` name pattern. Linearisation and missing supertypes pass through —
     * filtering hides rows, never hierarchy edges. The caller resolves
     * `--from` text to [fromBinary] first; [MemberFilters.fromRef] is ignored
     * here (it is the CLI's unresolved input form).
     */
    internal fun applyFilters(
        resolved: ResolvedMembers,
        filters: MemberFilters,
        fromBinary: String? = null,
    ): ResolvedMembers {
        val access = filters.access ?: MemberFilters.DEFAULT_ACCESS
        val methods = resolved.methods.filter { method ->
            if (filters.kind == KindFilter.FIELD) return@filter false
            val isCtor = method.member.name == "<init>"
            if (filters.kind == KindFilter.CTOR && !isCtor) return@filter false
            if (filters.kind == KindFilter.METHOD && isCtor) return@filter false
            if (method.member.access.visibility !in access) return@filter false
            if (filters.staticOnly != null &&
                method.member.access.has(AccessFlag.STATIC) != filters.staticOnly
            ) {
                return@filter false
            }
            if (fromBinary != null && method.declaringType.binaryName != fromBinary) return@filter false
            if (filters.grep != null && !filters.grep.containsMatchIn(method.member.name)) return@filter false
            true
        }
        val fields = resolved.fields.filter { field ->
            if (filters.kind == KindFilter.METHOD || filters.kind == KindFilter.CTOR) return@filter false
            if (field.member.access.visibility !in access) return@filter false
            if (filters.staticOnly != null &&
                field.member.access.has(AccessFlag.STATIC) != filters.staticOnly
            ) {
                return@filter false
            }
            if (fromBinary != null && field.declaringType.binaryName != fromBinary) return@filter false
            if (filters.grep != null && !filters.grep.containsMatchIn(field.member.name)) return@filter false
            true
        }
        return resolved.copy(methods = methods, fields = fields)
    }

    // -- paths ------------------------------------------------------------------

    private fun entryToBinary(entry: String): String =
        entry.removeSuffix(".class").replace('/', '.')

    private fun entryForBinary(binary: String): String =
        binary.replace('.', '/') + ".class"
}
