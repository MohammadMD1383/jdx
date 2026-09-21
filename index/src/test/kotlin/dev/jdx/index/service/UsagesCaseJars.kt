package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import java.nio.file.Path
import org.objectweb.asm.Opcodes

/**
 * Crafted usages corpus (T-030, tier 2): ASM-built classes with fully
 * controlled reference edges — every call, field access and type mention is
 * placed by hand, so the expected hit sets are exact, not sampled.
 *
 * - `u.Lib` declares two `greet` overloads (`(String)` and `(String,int)`,
 *   abstract so they carry no edges of their own) plus an `int count` field.
 * - `u.App#run()` calls `greet(String)`, reads and writes `count`, and
 *   mentions the type twice (`NEW` + `CHECKCAST` — deduped to one `ref` edge).
 * - `u.Other#work()` calls the `(String,int)` overload only.
 * - `v.Lib` is an empty namesake: short `Lib` queries are ambiguous (exit 2).
 */
internal fun buildUsagesCaseJar(dir: Path): Path {
    val abstractMethod = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
    return ArtifactTestJars.craftJar(
        dir.resolve("usages-case.jar"),
        mapOf(
            "u/Lib.class" to buildClass(
                "u/Lib",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER,
            ) {
                field(Opcodes.ACC_PUBLIC, "count", "I", null, null)
                method(abstractMethod, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null)
                method(abstractMethod, "greet", "(Ljava/lang/String;I)Ljava/lang/String;", null, null)
            },
            "u/App.class" to buildClass("u/App") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    typeInsn(Opcodes.NEW, "u/Lib")
                    invoke(Opcodes.INVOKEVIRTUAL, "u/Lib", "greet", "(Ljava/lang/String;)Ljava/lang/String;")
                    fieldInsn(Opcodes.GETFIELD, "u/Lib", "count", "I")
                    fieldInsn(Opcodes.PUTFIELD, "u/Lib", "count", "I")
                    typeInsn(Opcodes.CHECKCAST, "u/Lib")
                }
            },
            "u/Other.class" to buildClass("u/Other") {
                method(Opcodes.ACC_PUBLIC, "work", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "u/Lib", "greet", "(Ljava/lang/String;I)Ljava/lang/String;")
                }
            },
            "v/Lib.class" to buildClass("v/Lib"),
        ),
    )
}
