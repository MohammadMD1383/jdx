package dev.jdx.index.store

import dev.jdx.core.model.ClassInfo

/**
 * Drops fields the SQLite store never persists so ASM-read [ClassInfo]s can
 * be compared to [IndexStore.loadClass] results.
 *
 * Matches the D-050/D-051 kotlin-view exception already documented on the
 * indexer/store round-trip tests, plus issue #26 `AnnotationInfo.elements`
 * (structured [dev.jdx.core.model.AnnotationValue] AST): the store keeps the
 * rendered `values` map only; live ASM reads refill the AST.
 */
internal fun ClassInfo.forStoreCompare(): ClassInfo = copy(
    kotlinMethodViews = emptyMap(),
    kotlinProperties = emptyMap(),
    kotlinHiddenMethods = emptySet(),
    kotlinHiddenFields = emptySet(),
    annotations = annotations.map { it.copy(elements = emptyMap()) },
    fields = fields.map { field ->
        field.copy(annotations = field.annotations.map { it.copy(elements = emptyMap()) })
    },
    methods = methods.map { method ->
        method.copy(annotations = method.annotations.map { it.copy(elements = emptyMap()) })
    },
)
