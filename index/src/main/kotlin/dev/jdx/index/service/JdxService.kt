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
import dev.jdx.core.ref.SymbolRefPrinter
import dev.jdx.core.render.ClassCard
import dev.jdx.core.render.DEFAULT_MEMBER_LIMIT
import dev.jdx.core.render.DEFAULT_SEARCH_LIMIT
import dev.jdx.core.render.DEFAULT_TREE_DEPTH
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.LsListing
import dev.jdx.core.render.LsTypeEntry
import dev.jdx.core.render.MemberListing
import dev.jdx.core.render.MemberListingOptions
import dev.jdx.core.render.MemberSort
import dev.jdx.core.render.OBJECT_BINARY_NAME
import dev.jdx.core.render.PackageEntry
import dev.jdx.core.render.SearchHit
import dev.jdx.core.render.SearchListing
import dev.jdx.core.render.SignatureLines
import dev.jdx.core.render.TreeListing
import dev.jdx.core.render.buildArtifactTree
import dev.jdx.core.render.buildLsListing
import dev.jdx.core.render.buildSearchListing
import dev.jdx.core.render.buildTreeListing
import dev.jdx.core.render.buildClassCard
import dev.jdx.core.render.buildMemberListing
import dev.jdx.core.render.countNodes
import dev.jdx.core.render.searchKindWord
import dev.jdx.core.resolve.MemberResolutionOptions
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.core.resolve.ResolvedMembers
import dev.jdx.core.search.SymbolSearch
import dev.jdx.index.artifact.ArtifactKind
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactReadException
import dev.jdx.index.artifact.ArtifactRoot
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.index.maven.MavenResolveFn
import dev.jdx.index.maven.MavenResolver
import dev.jdx.index.maven.productionMavenResolve
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
        /**
         * Whether a `g:a:v/` ref prefix may hit the network (T-019). False by
         * default: without `--fetch` a prefix resolves from the local caches
         * only (D-006).
         */
        public val allowFetch: Boolean = false,
        /**
         * Resolves one `g:a:v` prefix text to jars. Defaults to the production
         * [MavenResolver][dev.jdx.index.maven.MavenResolver]; tests inject a
         * fake over fabricated repositories so no test touches the network.
         */
        public val mavenResolve: MavenResolveFn = ::productionMavenResolve,
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
        /**
         * Row order (T-062). Presentation only: filtering hides rows, sorting
         * orders the survivors. Kept here (rather than as a new `members()`
         * parameter) so the thin-adapter [MemberQuery][dev.jdx.cli.commands.MemberQuery]
         * arity stays stable across front-ends (D-004).
         */
        public val sort: MemberSort = MemberSort.KIND,
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

        /** A symbol search (`search`, `resolve`) — exit 0. */
        public data class SearchList(public val listing: SearchListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A package listing (`ls`) — exit 0. */
        public data class LsList(public val listing: LsListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A package forest (`tree`) — exit 0. */
        public data class TreeList(public val listing: TreeListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
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
        val typeName = typeRef.type as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: ${mode.flag} takes a class, got '$rawRef'")
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && typeRef.coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while supertypes still resolve from the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        val coordinate = typeRef.coordinate
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            execute(typeName, rawRef, scopedRoots, mode, filters, maxMembers, declaredOnly, includeSynthetic, candidateScope)
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
        candidateScope: Set<String>? = null,
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

            // A coordinate prefix restricts candidates to the scoped artifact;
            // everything else (supertype reads, did-you-mean breadth) stays full.
            val candidates = matchCandidates(typeName, candidateScope ?: allBinaries)
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
                    sort = filters.sort,
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

    /**
     * Edit distance, behind did-you-mean (PROPOSAL.md §16). Delegates to
     * [SymbolSearch] so the CLI and the search fallback share one implementation.
     */
    internal fun levenshtein(a: String, b: String): Int = SymbolSearch.levenshtein(a, b)

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

    // -- search / resolve / ls / tree (T-017) ------------------------------------

    /** `--kind` values for `search` (PROPOSAL.md §7.2), plus `type` and `all`. */
    public enum class SearchKindFilter(public val flag: String) {
        ALL("all"),
        TYPE("type"),
        CLASS("class"),
        INTERFACE("interface"),
        ENUM("enum"),
        RECORD("record"),
        ANNOTATION("annotation"),
        OBJECT("object"),
        COMPANION("companion"),
        METHOD("method"),
        FIELD("field"),
        PACKAGE("package"),
        MODULE("module"),
    }

    /**
     * Options for `search`: which symbols match and how much to show. `inArtifact`
     * is the `--in` glob over artifact labels (jar file names, class-dir names,
     * JDK module names); `inPackage` is `--package` over dotted package names.
     * Both accept globs, else match as case-insensitive substrings (D-031).
     */
    public data class SearchOptions(
        public val kind: SearchKindFilter = SearchKindFilter.ALL,
        public val regex: Boolean = false,
        public val fuzzy: Boolean = false,
        public val inArtifact: String? = null,
        public val inPackage: String? = null,
        public val limit: Int = DEFAULT_SEARCH_LIMIT,
    )

    /**
     * Answers `search <pattern>`: symbol search across the workspace's roots.
     * Name matching is [SymbolSearch] (glob, regex, camel-hump, fuzzy fallback);
     * orchestration here only enumerates entry names and parses the classes the
     * pattern actually touches — a live-roots search, no persistent index yet
     * (index-backed search will land with the M4 graph work).
     */
    public fun search(rawPattern: String, roots: RootsSpec, options: SearchOptions = SearchOptions()): ServiceOutcome {
        if (options.limit < 0) {
            return failure(3, rawPattern, "usage error: --limit must be >= 0, got ${options.limit}")
        }
        if (options.regex && !SymbolSearch.isValidRegex(rawPattern)) {
            return failure(3, rawPattern, "usage error: invalid --regex pattern '$rawPattern'")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                rawPattern,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeSearch(rawPattern, roots, options)
        } catch (e: ArtifactReadException) {
            failure(5, rawPattern, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawPattern, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Answers `resolve <name>`: exact-match disambiguation for an unqualified or
     * partial name. Every candidate lists kind and artifact; several candidates
     * are success (exit 0) here — that is the point of the command — while none
     * is exit 1 with did-you-mean suggestions.
     */
    public fun resolve(rawName: String, roots: RootsSpec, limit: Int = DEFAULT_SEARCH_LIMIT): ServiceOutcome {
        if (limit < 0) {
            return failure(3, rawName, "usage error: --limit must be >= 0, got $limit")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                rawName,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeResolve(rawName, roots, limit)
        } catch (e: ArtifactReadException) {
            failure(5, rawName, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawName, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Answers `ls [package-glob]`: packages matching the glob with type counts —
     * or, when the glob names one exact package, the types inside it.
     */
    public fun ls(packageGlob: String?, roots: RootsSpec, limit: Int = DEFAULT_SEARCH_LIMIT): ServiceOutcome {
        val query = packageGlob ?: "*"
        if (limit < 0) {
            return failure(3, query, "usage error: --limit must be >= 0, got $limit")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                query,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeLs(query, packageGlob, roots, limit)
        } catch (e: ArtifactReadException) {
            failure(5, query, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, query, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Answers `tree [artifact-glob]`: the package forest of each matching
     * artifact, nested to [depth] (0 shows top-level segments only).
     */
    public fun tree(
        artifactGlob: String?,
        roots: RootsSpec,
        depth: Int = DEFAULT_TREE_DEPTH,
        withCounts: Boolean = false,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): ServiceOutcome {
        val query = artifactGlob ?: "*"
        if (limit < 0) {
            return failure(3, query, "usage error: --limit must be >= 0, got $limit")
        }
        if (depth < 0) {
            return failure(3, query, "usage error: --depth must be >= 0, got $depth")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                query,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeTree(query, roots, depth, withCounts, limit)
        } catch (e: ArtifactReadException) {
            failure(5, query, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, query, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    // -- search execution -------------------------------------------------------

    private data class SearchEntry(
        val binary: String,
        val packageName: String,
        val artifact: String,
        val rootIndex: Int,
    )

    private class SearchWorkspace(
        val entries: List<SearchEntry>,
        val workspace: Workspace,
        val warnings: MutableList<Warning>,
        val opened: List<OpenRoot>,
    )

    private fun openSearchWorkspace(roots: RootsSpec): SearchWorkspace {
        val opened = openRoots(roots)
        try {
            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            // Per-provider rows: shadowing duplicates list one hit per artifact
            // (the honest answer for search — D-031), so nothing is keyed away.
            // `rootIndex` disambiguates same-named artifacts in `tree` (D-031).
            val entries = opened.flatMapIndexed { rootIndex, open ->
                open.root.classEntryPaths().map { path ->
                    val binary = entryToBinary(path)
                    SearchEntry(
                        binary = binary,
                        packageName = binary.substringBeforeLast('.', ""),
                        artifact = rootLabel(open, binary),
                        rootIndex = rootIndex,
                    )
                }
            }
            val providers = mutableMapOf<String, MutableList<Int>>()
            opened.forEachIndexed { index, open ->
                for (binary in open.root.classEntryPaths().map(::entryToBinary)) {
                    providers.getOrPut(binary) { mutableListOf() }.add(index)
                }
            }
            return SearchWorkspace(entries, Workspace(opened, providers, warnings), warnings, opened)
        } catch (e: Exception) {
            opened.forEach { runCatching { it.root.close() } }
            throw e
        }
    }

    private fun SearchWorkspace.close(): Unit = opened.forEach { it.root.close() }

    private fun executeSearch(rawPattern: String, roots: RootsSpec, options: SearchOptions): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            val scope = search.entries.filter { entry ->
                matchesArtifactFilter(options.inArtifact, entry.artifact) &&
                    matchesPackageFilter(options.inPackage, entry.packageName)
            }
            val hits = mutableListOf<SearchHit>()
            val wantTypes = options.kind == SearchKindFilter.ALL ||
                options.kind == SearchKindFilter.TYPE || isTypeKind(options.kind)
            val wantMembers = options.kind == SearchKindFilter.METHOD || options.kind == SearchKindFilter.FIELD
            val wantPackages = options.kind == SearchKindFilter.ALL || options.kind == SearchKindFilter.PACKAGE
            val wantModules = options.kind == SearchKindFilter.ALL || options.kind == SearchKindFilter.MODULE
            if (wantTypes) hits.addAll(matchTypes(search, scope, rawPattern, options))
            if (wantMembers) hits.addAll(matchMembers(search, scope, rawPattern, options))
            if (wantPackages) hits.addAll(matchPackages(scope, rawPattern, options))
            if (wantModules) hits.addAll(matchModules(search, rawPattern, options))
            // The `--fuzzy` fallback runs only when the precise modes find nothing.
            if (hits.isEmpty() && options.fuzzy && !options.regex) {
                hits.addAll(fuzzyMatchTypes(search, scope, rawPattern, options))
            }
            if (hits.isEmpty()) {
                val suggestions = suggestSimilar(
                    rawPattern.substringAfterLast('.').substringAfterLast('$'),
                    scope.map { it.binary }.toSet(),
                )
                return ServiceOutcome.Failure(ErrorResult.notFound(rawPattern, suggestions))
            }
            val sorted = hits.sortedWith(compareBy({ it.ref }, { it.artifact }))
            val warnings = search.warnings.sortedBy { it.code }
            val listing = buildSearchListing(rawPattern, sorted, options.limit, warnings)
            return ServiceOutcome.SearchList(listing)
        } finally {
            search.close()
        }
    }

    private fun isTypeKind(kind: SearchKindFilter): Boolean = when (kind) {
        SearchKindFilter.CLASS, SearchKindFilter.INTERFACE, SearchKindFilter.ENUM,
        SearchKindFilter.RECORD, SearchKindFilter.ANNOTATION,
        SearchKindFilter.OBJECT, SearchKindFilter.COMPANION -> true
        else -> false
    }

    private fun matchTypes(
        search: SearchWorkspace,
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        // Name-match first (entry names only, no parsing), then load the winners
        // for their kind words — one ASM parse per *matched* class, never a scan.
        val nameMatched = scope.filter { matchesTypeName(pattern, it.binary, options.regex) }.distinctBy { it.binary }
        if (nameMatched.isEmpty()) return emptyList()
        val kinds = nameMatched.associate { entry ->
            entry.binary to search.workspace.load(entry.binary)?.kind
        }
        return scope
            .filter { matchesTypeName(pattern, it.binary, options.regex) }
            .filter { entry ->
                when (options.kind) {
                    SearchKindFilter.ALL, SearchKindFilter.TYPE -> true
                    else -> {
                        val kind = kinds[entry.binary] ?: return@filter false
                        searchKindWord(kind).equals(options.kind.flag, ignoreCase = true)
                    }
                }
            }
            .map { entry ->
                val kind = kinds[entry.binary]
                SearchHit(
                    kind = if (kind == null) "class" else searchKindWord(kind),
                    ref = entry.binary,
                    artifact = entry.artifact,
                    packageName = entry.packageName,
                )
            }
    }

    private fun fuzzyMatchTypes(
        search: SearchWorkspace,
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        val nameMatched = scope.filter {
            SymbolSearch.fuzzyMatches(pattern, it.binary.substringAfterLast('.').substringAfterLast('$'))
        }.distinctBy { it.binary }
        if (nameMatched.isEmpty()) return emptyList()
        val kinds = nameMatched.associate { entry ->
            entry.binary to search.workspace.load(entry.binary)?.kind
        }
        return scope
            .filter {
                SymbolSearch.fuzzyMatches(pattern, it.binary.substringAfterLast('.').substringAfterLast('$'))
            }
            .filter { entry ->
                when (options.kind) {
                    SearchKindFilter.ALL, SearchKindFilter.TYPE -> true
                    else -> {
                        val kind = kinds[entry.binary] ?: return@filter false
                        searchKindWord(kind).equals(options.kind.flag, ignoreCase = true)
                    }
                }
            }
            .map { entry ->
                val kind = kinds[entry.binary]
                SearchHit(
                    kind = if (kind == null) "class" else searchKindWord(kind),
                    ref = entry.binary,
                    artifact = entry.artifact,
                    packageName = entry.packageName,
                )
            }
    }

    private fun matchMembers(
        search: SearchWorkspace,
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        // Member search parses every in-scope class (the slow path — index-backed
        // member search will lift this with the M4 graph work). Bridge/synthetic
        // members stay hidden, mirroring `members` (D-031).
        val wantMethods = options.kind == SearchKindFilter.METHOD
        val hits = mutableListOf<SearchHit>()
        for (entry in scope.distinctBy { it.binary }) {
            val info = search.workspace.load(entry.binary) ?: continue
            val methodSiblings = info.methods.groupingBy {
                it.name to it.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
            }.eachCount()
            if (wantMethods) {
                for (method in info.methods) {
                    if (method.name == "<clinit>") continue
                    if (method.access.has(AccessFlag.SYNTHETIC) || method.access.has(AccessFlag.BRIDGE)) continue
                    if (!matchesMemberName(pattern, method.name, options.regex)) continue
                    val baseRef = SymbolRefPrinter.print(
                        MemberSymbolRef(
                            declaringType = info.name,
                            name = method.name,
                            parameterTypes = method.descriptor.parameters,
                        ),
                    )
                    val key = method.name to
                        method.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
                    val ref = if ((methodSiblings[key] ?: 0) > 1 && method.name != "<init>") {
                        baseRef + ":" + SignatureLines.renderTypeName(method.descriptor.returnType)
                    } else {
                        baseRef
                    }
                    providersOf(search, entry.binary, entry).forEach { artifact ->
                        hits.add(
                            SearchHit(
                                kind = if (method.name == "<init>") "constructor" else "method",
                                ref = ref,
                                artifact = artifact,
                                packageName = entry.packageName,
                            ),
                        )
                    }
                }
            } else {
                for (field in info.fields) {
                    if (field.access.has(AccessFlag.SYNTHETIC)) continue
                    if (!matchesMemberName(pattern, field.name, options.regex)) continue
                    val ref = SymbolRefPrinter.print(MemberSymbolRef(info.name, field.name))
                    providersOf(search, entry.binary, entry).forEach { artifact ->
                        hits.add(
                            SearchHit(
                                kind = "field",
                                ref = ref,
                                artifact = artifact,
                                packageName = entry.packageName,
                            ),
                        )
                    }
                }
            }
        }
        return hits
    }

    private fun providersOf(search: SearchWorkspace, binary: String, entry: SearchEntry): List<String> {
        // Every provider of the binary lists its own artifact label (D-031).
        return search.entries.filter { it.binary == binary }.map { it.artifact }.distinct().ifEmpty {
            listOf(entry.artifact)
        }
    }

    private fun matchPackages(
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        val pairs = scope.map { it.packageName to it.artifact }.distinct()
            .filter { (packageName, _) -> matchesPackageName(pattern, packageName, options.regex) }
        return pairs.map { (packageName, artifact) ->
            SearchHit(kind = "package", ref = packageName, artifact = artifact, packageName = packageName)
        }
    }

    private fun matchModules(
        search: SearchWorkspace,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        val modules = search.opened.flatMap { open ->
            val jrtModuleFor = open.jrtModuleFor ?: return@flatMap emptyList()
            open.root.classEntryPaths().mapNotNull { jrtModuleFor(it) }
        }.distinct()
        return modules.filter { matchesMemberName(pattern, it, options.regex) }.map { module ->
            SearchHit(kind = "module", ref = module, artifact = module, packageName = "")
        }
    }

    // -- name matching over workspace names (pure, unit-tested) ------------------

    /**
     * Matches [pattern] against a binary type name. Regex tries the binary, dotted
     * and simple forms; a dotted glob tries binary and dotted forms; a bare glob
     * tries the simple name and the binary. A dotted plain word names a location:
     * it matches exactly or as a `.`-boundary suffix (`dev.jdx.fixtures.Generics`
     * finds that type but not `Generics$Recursive` — the TESTING.md metamorphic
     * law). A bare plain word is a case-insensitive substring or a camel-hump on
     * the simple name (`JsonAdapter` also finds `JsonAdapterAnnotation...`, D-031).
     */
    internal fun matchesTypeName(pattern: String, binary: String, regex: Boolean): Boolean {
        val dotted = binary.replace('$', '.')
        val simple = binary.substringAfterLast('.').substringAfterLast('$')
        if (regex) {
            return SymbolSearch.matchesRegex(pattern, binary) ||
                SymbolSearch.matchesRegex(pattern, dotted) ||
                SymbolSearch.matchesRegex(pattern, simple)
        }
        if (SymbolSearch.isGlobPattern(pattern)) {
            return if ('.' in pattern) {
                SymbolSearch.matchesGlob(pattern, binary) || SymbolSearch.matchesGlob(pattern, dotted)
            } else {
                SymbolSearch.matchesGlob(pattern, simple) || SymbolSearch.matchesGlob(pattern, binary)
            }
        }
        if ('.' in pattern) {
            return binary == pattern || dotted == pattern ||
                (binary.endsWith(pattern) && binary[binary.length - pattern.length - 1] == '.') ||
                (dotted.endsWith(pattern) && dotted[dotted.length - pattern.length - 1] == '.')
        }
        return SymbolSearch.matchesSubstring(pattern, simple) ||
            SymbolSearch.matchesSubstring(pattern, binary) ||
            SymbolSearch.camelHumpMatches(pattern, simple)
    }

    /** Matches [pattern] against a member or module name (no package structure). */
    internal fun matchesMemberName(pattern: String, name: String, regex: Boolean): Boolean {
        if (regex) return SymbolSearch.matchesRegex(pattern, name)
        if (SymbolSearch.isGlobPattern(pattern)) return SymbolSearch.matchesGlob(pattern, name)
        return SymbolSearch.matchesSubstring(pattern, name) || SymbolSearch.camelHumpMatches(pattern, name)
    }

    /** Matches [pattern] against a dotted package name. */
    internal fun matchesPackageName(pattern: String, packageName: String, regex: Boolean): Boolean {
        if (regex) return SymbolSearch.matchesRegex(pattern, packageName)
        if (SymbolSearch.isGlobPattern(pattern)) return SymbolSearch.matchesGlob(pattern, packageName)
        return SymbolSearch.matchesSubstring(pattern, packageName) ||
            SymbolSearch.camelHumpMatches(pattern, packageName.substringAfterLast('.'))
    }

    /** `--in` matches artifact labels: glob when wild, substring otherwise. */
    internal fun matchesArtifactFilter(filter: String?, artifact: String): Boolean {
        if (filter == null) return true
        return if (SymbolSearch.isGlobPattern(filter)) SymbolSearch.matchesGlob(filter, artifact)
        else artifact.contains(filter, ignoreCase = true)
    }

    /** `--package` matches dotted package names: glob when wild, substring otherwise. */
    internal fun matchesPackageFilter(filter: String?, packageName: String): Boolean {
        if (filter == null) return true
        return if (SymbolSearch.isGlobPattern(filter)) SymbolSearch.matchesGlob(filter, packageName)
        else packageName.contains(filter, ignoreCase = true)
    }

    // -- resolve / ls / tree execution ------------------------------------------

    private fun executeResolve(rawName: String, roots: RootsSpec, limit: Int): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            // A `#`/`::` suffix names a member; anything else is a type or package.
            val hashIndex = rawName.indexOf('#').takeIf { it >= 0 }
                ?: rawName.indexOf("::").takeIf { it >= 0 }
            val typePart = if (hashIndex == null) rawName else rawName.substring(0, hashIndex)
            val memberPart = if (hashIndex == null) {
                null
            } else if (rawName[hashIndex] == '#') {
                rawName.substring(hashIndex + 1)
            } else {
                rawName.substring(hashIndex + 2)
            }
            val scope = search.entries
            val typeBinaries = scope.map { it.binary }.distinct().filter { binary ->
                binary == typePart ||
                    binary.replace('$', '.') == typePart ||
                    binary.substringAfterLast('.') == typePart ||
                    binary.substringAfterLast('.').substringAfterLast('$') == typePart
            }
            val hits = mutableListOf<SearchHit>()
            if (memberPart == null) {
                for (binary in typeBinaries) {
                    val kind = search.workspace.load(binary)?.kind
                    if (kind == null) continue
                    for (artifact in scope.filter { it.binary == binary }.map { it.artifact }.distinct()) {
                        val packageName = binary.substringBeforeLast('.', "")
                        hits.add(SearchHit(searchKindWord(kind), binary, artifact, packageName))
                    }
                }
                if (hits.isEmpty()) {
                    // An exact package name resolves to its providers (D-031).
                    for (artifact in scope.filter { it.packageName == typePart }.map { it.artifact }.distinct()) {
                        hits.add(SearchHit("package", typePart, artifact, typePart))
                    }
                }
            } else {
                for (binary in typeBinaries) {
                    val info = search.workspace.load(binary) ?: continue
                    val packageName = binary.substringBeforeLast('.', "")
                    val artifacts = scope.filter { it.binary == binary }.map { it.artifact }.distinct()
                    for (method in info.methods.filter { it.name == memberPart }) {
                        if (method.name == "<clinit>") continue
                        val ref = SymbolRefPrinter.print(
                            MemberSymbolRef(
                                declaringType = info.name,
                                name = method.name,
                                parameterTypes = method.descriptor.parameters,
                            ),
                        )
                        for (artifact in artifacts) {
                            hits.add(
                                SearchHit(
                                    if (method.name == "<init>") "constructor" else "method",
                                    ref,
                                    artifact,
                                    packageName,
                                ),
                            )
                        }
                    }
                    for (field in info.fields.filter { it.name == memberPart }) {
                        val ref = SymbolRefPrinter.print(MemberSymbolRef(info.name, field.name))
                        for (artifact in artifacts) {
                            hits.add(SearchHit("field", ref, artifact, packageName))
                        }
                    }
                }
            }
            if (hits.isEmpty()) {
                val suggestions = suggestSimilar(
                    typePart.substringAfterLast('.').substringAfterLast('$'),
                    scope.map { it.binary }.toSet(),
                )
                return ServiceOutcome.Failure(ErrorResult.notFound(rawName, suggestions))
            }
            val sorted = hits.sortedWith(compareBy({ it.ref }, { it.artifact }))
            val listing = buildSearchListing(rawName, sorted, limit, search.warnings.sortedBy { it.code })
            return ServiceOutcome.SearchList(listing)
        } finally {
            search.close()
        }
    }

    private fun executeLs(query: String, packageGlob: String?, roots: RootsSpec, limit: Int): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            if (packageGlob != null && !SymbolSearch.isGlobPattern(packageGlob)) {
                // Exact package: the types directly inside it (D-031).
                val inPackage = search.entries.filter { it.packageName == packageGlob }.distinct()
                if (inPackage.isEmpty()) {
                    return ServiceOutcome.Failure(ErrorResult.notFound(query))
                }
                val kinds = inPackage.distinctBy { it.binary }.associate { entry ->
                    entry.binary to search.workspace.load(entry.binary)?.kind
                }
                val types = inPackage.map { entry ->
                    val kind = kinds[entry.binary]
                    LsTypeEntry(
                        kind = if (kind == null) "class" else searchKindWord(kind),
                        ref = entry.binary,
                        artifact = entry.artifact,
                    )
                }.sortedWith(compareBy({ it.ref }, { it.artifact }))
                val packages = listOf(PackageEntry(packageGlob, inPackage.distinctBy { it.binary }.size))
                val listing = buildLsListing(query, packages, types, limit, search.warnings.sortedBy { it.code })
                return ServiceOutcome.LsList(listing)
            }
            val binariesByPackage = search.entries.distinctBy { it.binary to it.artifact }
                .groupBy({ it.packageName }, { it.binary })
            val packages = binariesByPackage
                .filter { (packageName, _) ->
                    packageGlob == null || SymbolSearch.matchesGlob(packageGlob, packageName)
                }
                .map { (packageName, binaries) -> PackageEntry(packageName, binaries.distinct().size) }
                .sortedBy { it.name }
            if (packages.isEmpty()) {
                return ServiceOutcome.Failure(ErrorResult.notFound(query))
            }
            val listing = buildLsListing(query, packages, emptyList(), limit, search.warnings.sortedBy { it.code })
            return ServiceOutcome.LsList(listing)
        } finally {
            search.close()
        }
    }

    private fun executeTree(
        query: String,
        roots: RootsSpec,
        depth: Int,
        withCounts: Boolean,
        limit: Int,
    ): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            // JRT entries group by module (the artifact an agent recognises);
            // jars and dirs group by display label. A label served by several
            // roots gains a `(2)`-style suffix — deterministic by root order.
            val jrtModules = search.opened.flatMap { open ->
                val moduleFor = open.jrtModuleFor ?: return@flatMap emptyList<String>()
                open.root.classEntryPaths().mapNotNull { moduleFor(it) }
            }.toSet()
            val rootsByLabel = search.entries.groupBy({ it.artifact }, { it.rootIndex })
                .mapValues { it.value.toSet() }
            fun groupLabel(entry: SearchEntry): String {
                if (entry.artifact in jrtModules) return entry.artifact
                val rootIndexes = rootsByLabel.getValue(entry.artifact).sorted()
                if (rootIndexes.size == 1) return entry.artifact
                val ordinal = rootIndexes.indexOf(entry.rootIndex) + 1
                return if (ordinal <= 1) entry.artifact else "${entry.artifact} ($ordinal)"
            }
            val labels = mutableMapOf<String, MutableSet<String>>()
            val packagesOf = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
            for (entry in search.entries) {
                val label = groupLabel(entry)
                labels.getOrPut(label) { mutableSetOf() }.add(entry.binary)
                packagesOf.getOrPut(label) { mutableMapOf() }
                    .getOrPut(entry.packageName) { mutableSetOf() }.add(entry.binary)
            }
            val matched = labels.keys.filter { label -> matchesArtifactFilter(query, label) }.sorted()
            if (matched.isEmpty()) {
                return ServiceOutcome.Failure(ErrorResult.notFound(query))
            }
            val trees = matched.map { label ->
                val packageNames = packagesOf.getValue(label).keys.filter { it.isNotEmpty() }.sorted()
                val direct = packagesOf.getValue(label)
                val subtree = mutableMapOf<String, Int>()
                for (packageName in packageNames) {
                    val segments = packageName.split('.')
                    for (end in 1..segments.size) {
                        val path = segments.take(end).joinToString(".")
                        if (path == packageName) {
                            subtree[path] = (subtree[path] ?: 0) + direct.getValue(packageName).size
                        } else {
                            subtree.putIfAbsent(path, 0)
                        }
                    }
                }
                // Roll up descendant counts after the direct pass.
                val ordered = subtree.keys.sortedByDescending { it.length }
                for (path in ordered) {
                    val parent = path.substringBeforeLast('.', "")
                    if (parent.isNotEmpty() && subtree.containsKey(parent)) {
                        subtree[parent] = subtree.getValue(parent) + subtree.getValue(path)
                    }
                }
                buildArtifactTree(label, packageNames, typeCountOf = { subtree.getValue(it) }, depth = depth)
            }
            val nodeTotal = trees.sumOf { countNodes(it.roots) }
            val listing = buildTreeListing(
                query,
                trees,
                withCounts,
                nodeTotal,
                limit,
                search.warnings.sortedBy { it.code },
            )
            return ServiceOutcome.TreeList(listing)
        } finally {
            search.close()
        }
    }

    // -- paths ------------------------------------------------------------------

    private fun entryToBinary(entry: String): String =
        entry.removeSuffix(".class").replace('/', '.')

    private fun entryForBinary(binary: String): String =
        binary.replace('.', '/') + ".class"
}
