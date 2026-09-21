package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import java.nio.file.Path
import org.objectweb.asm.Opcodes

/**
 * Crafted hierarchy corpus (T-032, tier 2): ASM-built classes with a fully
 * controlled supertype graph — every `extends`/`implements` edge is placed by
 * hand, so the expected up/down sets are exact, not sampled.
 *
 * - `h.Base` is a plain class (extends `java.lang.Object`).
 * - `h.Iface` is an interface.
 * - `h.Middle extends h.Base implements h.Iface`.
 * - `h.Leaf extends h.Middle` (transitive child of `Base` via
 *   `extends h.Middle`, of `Iface` via `extends h.Middle` + `implements`).
 * - `h.Impl implements h.Iface` (direct implementor).
 * - `h.SubIface` is an interface extending `h.Iface` (superinterfaces of
 *   interfaces render as `extends`, never `implements`).
 * - `h.Other` is unrelated (empty down set; its `Object` chain is outside a
 *   `--no-jdk` workspace).
 * - `h.Orphan extends h.Missing` (absent supertype — the
 *   `UNRESOLVED_SUPERTYPE` path; `Object` itself is exempt).
 * - `h2.Leaf` is an empty namesake: short `Leaf` queries are ambiguous
 *   (exit 2).
 */
internal fun buildHierarchyCaseJar(dir: Path): Path {
    val public = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER
    val ifaceAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE
    return ArtifactTestJars.craftJar(
        dir.resolve("hierarchy-case.jar"),
        mapOf(
            "h/Base.class" to buildClass("h/Base", access = public),
            "h/Iface.class" to buildClass("h/Iface", access = ifaceAccess),
            "h/Middle.class" to buildClass(
                "h/Middle",
                access = public,
                superName = "h/Base",
                interfaces = arrayOf("h/Iface"),
            ),
            "h/Leaf.class" to buildClass("h/Leaf", access = public, superName = "h/Middle"),
            "h/Impl.class" to buildClass(
                "h/Impl",
                access = public,
                interfaces = arrayOf("h/Iface"),
            ),
            "h/SubIface.class" to buildClass(
                "h/SubIface",
                access = ifaceAccess,
                interfaces = arrayOf("h/Iface"),
            ),
            "h/Other.class" to buildClass("h/Other", access = public),
            "h/Orphan.class" to buildClass("h/Orphan", access = public, superName = "h/Missing"),
            "h2/Leaf.class" to buildClass("h2/Leaf", access = public),
        ),
    )
}
