package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.objectweb.asm.Opcodes

/**
 * Crafted literals corpus (issue #86): controlled string constants for
 * substring / regex / ignore-case / limit / artifact-filter assertions.
 *
 * - `lit.App#run()` loads `"notification.missing.plugin.update.sources.title"`
 *   and `"Hello World"` (lined).
 * - `lit.Keys#TITLE` is a `static final String` ConstantValue with the same
 *   notification key.
 * - `lit.Other#work()` loads `"other.marker"` only (for `--in` / `--exclude`).
 * - `messages/Bundle.properties` defines the same notification key (English
 *   value) plus a properties-only key for the resource slice.
 */
internal fun buildLiteralsCaseJar(dir: Path): Path {
    val bundle = """
        |# i18n bundle for the literals corpus
        |notification.missing.plugin.update.sources.title=Cannot update plugins
        |resource.only.key=Only in properties
        |
    """.trimMargin().toByteArray(StandardCharsets.UTF_8)
    return ArtifactTestJars.craftJar(
        dir.resolve("literals-case.jar"),
        mapOf(
            "lit/App.class" to buildClass("lit/App") {
                source("App.java")
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    line(5)
                    ldc("notification.missing.plugin.update.sources.title")
                    line(8)
                    ldc("Hello World")
                    code(Opcodes.POP to null, Opcodes.POP to null, Opcodes.RETURN to null)
                }
            },
            "lit/Keys.class" to buildClass("lit/Keys") {
                field(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    "TITLE",
                    "Ljava/lang/String;",
                    null,
                    "notification.missing.plugin.update.sources.title",
                )
            },
            "lit/Other.class" to buildClass("lit/Other") {
                method(Opcodes.ACC_PUBLIC, "work", "()V", null, null) {
                    ldc("other.marker")
                    code(Opcodes.POP to null, Opcodes.RETURN to null)
                }
            },
            "messages/Bundle.properties" to bundle,
        ),
    )
}
