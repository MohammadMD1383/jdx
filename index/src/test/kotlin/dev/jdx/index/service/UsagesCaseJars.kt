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
 * - T-075 enrichment: `u.Widget` (concrete, empty) is constructed once by
 *   `u.Factory#create()` (`NEW` + `<init>` call → `--kind new`); `u.Boom`
 *   (exception) is declared in `u.App#risky()`'s `throws` (`--kind throw`);
 *   `u.Mark` (annotation) annotates the `u.Factory` class and the
 *   `u.Other#work()` method (`--kind annotation`).
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
                method(Opcodes.ACC_PUBLIC, "risky", "()V", null, arrayOf("u/Boom")) {
                    code(Opcodes.RETURN to null)
                }
            },
            "u/Other.class" to buildClass("u/Other") {
                method(Opcodes.ACC_PUBLIC, "work", "()V", null, null) {
                    annotation("Lu/Mark;", true).visitEnd()
                    invoke(Opcodes.INVOKEVIRTUAL, "u/Lib", "greet", "(Ljava/lang/String;I)Ljava/lang/String;")
                }
            },
            "u/Widget.class" to buildClass("u/Widget") {
                method(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) {
                    invoke(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V")
                    code(Opcodes.RETURN to null)
                }
            },
            "u/Factory.class" to buildClass("u/Factory") {
                annotate("Lu/Mark;", true).visitEnd()
                method(Opcodes.ACC_PUBLIC, "create", "()V", null, null) {
                    typeInsn(Opcodes.NEW, "u/Widget")
                    invoke(Opcodes.INVOKESPECIAL, "u/Widget", "<init>", "()V")
                }
            },
            "u/Boom.class" to buildClass("u/Boom", superName = "java/lang/Exception"),
            "u/Mark.class" to buildClass(
                "u/Mark",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE,
            ),
            "v/Lib.class" to buildClass("v/Lib"),
        ),
    )
}
