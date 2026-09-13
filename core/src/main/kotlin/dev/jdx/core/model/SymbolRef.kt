package dev.jdx.core.model

/**
 * A reference to a symbol, as parsed from user input (T-003) or emitted in output.
 * A reference may be *under-specified* (no parameter types, no coordinate) — that is
 * how ambiguity is represented; resolution against a workspace is a later, separate
 * concern that must not leak into the reference model.
 */
public sealed interface SymbolRef

/** A Maven coordinate scoping a reference to one artifact, `group:artifact:version`. */
public data class MavenCoordinate(
    public val group: String,
    public val artifact: String,
    public val version: String,
) {
    public val coordinate: String get() = "$group:$artifact:$version"
}

/** A reference to a type, optionally scoped to a [MavenCoordinate]. */
public data class TypeSymbolRef(
    public val type: TypeName,
    public val coordinate: MavenCoordinate? = null,
) : SymbolRef

/**
 * A reference to a field, method or constructor. [parameterTypes] is `null` when the
 * reference carries no parameter list at all — the under-specified "all overloads"
 * form that resolution later disambiguates (D-016); an **empty list** means an
 * explicitly zero-parameter member. [returnType] (rarely needed) disambiguates
 * covariant/bridge overloads. Constructors and static initialisers are referenced by
 * the names `<init>` / `<clinit>`. Fields never carry a parameter list.
 */
public data class MemberSymbolRef(
    public val declaringType: TypeName,
    public val name: String,
    public val parameterTypes: List<TypeName>? = null,
    public val returnType: TypeName? = null,
    public val coordinate: MavenCoordinate? = null,
) : SymbolRef

/** A reference to a package, e.g. `com.google.gson` (supports globs in `search`/`ls`). */
public data class PackageSymbolRef(public val packageName: String) : SymbolRef

/** A reference to a JPMS module, e.g. `java.base`. */
public data class ModuleSymbolRef(public val moduleName: String) : SymbolRef
