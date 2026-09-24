package dev.jdx.core.ref

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.ModuleSymbolRef
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.SymbolRef
import dev.jdx.core.model.TypeSymbolRef

/**
 * Prints a [SymbolRef] in its canonical form (PROPOSAL.md §6, D-014):
 *  - types print their binary name — nesting joined with `$` (AGENTS.md)
 *  - member parameters/return types print dotted packages with `$`-joined nesting
 *    (`java.util.Map$Entry`, `java.lang.String[]`) — the dotted fqn is **not** used
 *    because a default-package nested class (`c.λ`) would re-parse as a different
 *    type (package `c`, class `λ`); `$`-joining keeps print → parse the identity
 *  - members print `Type#name(param, param):return`, `, `-separated; `()` for an
 *    explicitly zero-parameter member
 *  - a Maven coordinate prints as `group:artifact:version/` before the type
 *  - package globs print verbatim; module refs print their name
 *
 * Deterministic: identical refs print byte-identical strings. Faithful to what the
 * ref *stores* — qualifying a simple name against a workspace is resolution's job,
 * not the printer's. Only parser-producible shapes are guaranteed to round-trip
 * (e.g. a `TypeSymbolRef` holding an array type prints its descriptor, which does
 * not re-parse; the parser never produces such a value).
 */
public object SymbolRefPrinter {

    public fun print(ref: SymbolRef): String = when (ref) {
        is TypeSymbolRef -> coordinatePrefix(ref.coordinate) + ref.type.binaryName
        is MemberSymbolRef -> buildString {
            append(coordinatePrefix(ref.coordinate))
            append(ref.declaringType.binaryName)
            append('#')
            append(ref.name)
            ref.parameterTypes?.let { params ->
                append('(')
                append(params.joinToString(", ") { paramText(it) })
                append(')')
            }
            ref.returnType?.let { append(':').append(paramText(it)) }
        }
        is PackageSymbolRef -> ref.packageName
        is ModuleSymbolRef -> ref.moduleName
    }

    /** Source-form text for a parameter/return type: `$`-joined nesting, `[]` per array dimension. */
    private fun paramText(type: dev.jdx.core.model.TypeName): String = when (type) {
        is dev.jdx.core.model.TypeName.ArrayType -> paramText(type.elementType) + "[]".repeat(type.dimensions)
        else -> type.binaryName
    }

    private fun coordinatePrefix(ref: dev.jdx.core.model.MavenCoordinate?): String =
        if (ref == null) "" else ref.coordinate + "/"
}
