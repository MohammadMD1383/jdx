package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import java.nio.file.Path
import org.objectweb.asm.Opcodes

/**
 * Crafted samples corpus (T-034, tier 2): ASM-built classes with fully
 * controlled `METHOD_CALL` edges — plain callers, a test-named caller, a
 * `$`-nested (generated) caller, overloads and a constructor call — so the
 * expected exemplariness ranking is exact, not sampled.
 *
 * - `s.Lib` declares two abstract `greet` overloads (`(String)` and
 *   `(String,int)`) plus an `int count` field (fields have no examples).
 * - `s.App#run()` calls `greet(String)` — the plain example.
 * - `s.Util#help()` (static) calls `greet(String)` — the second plain example.
 * - `s.AppTest#runTest()` calls `greet(String)` — test-named, ranks last.
 * - `s.Gen$Inner#run()` calls `greet(String)` — `$` nesting reads as
 *   generated, ranking after plain callers but before tests.
 * - `s.Other#work()` calls the `(String,int)` overload only — the fullest
 *   overload, ranking first among plain callers.
 * - `s.Factory#make()` constructs `s.Bean` (`NEW` is a type edge, the
 *   `INVOKESPECIAL <init>` is the call edge); `s.Bean#<init>` calls
 *   `s.Util#help()`.
 * - `t.Lib` is an empty namesake: short `Lib` queries are ambiguous (exit 2).
 *
 * The sibling `samples-case-sources.jar` holds `s/App.java` only, so
 * `s.App#run()` renders with a snippet while every other caller renders
 * snippet-less (the degrade path).
 */
internal fun buildSamplesCaseJar(dir: Path): Path {
    val abstractMethod = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
    val greet1 = "(Ljava/lang/String;)Ljava/lang/String;"
    val greet2 = "(Ljava/lang/String;I)Ljava/lang/String;"
    val jar = ArtifactTestJars.craftJar(
        dir.resolve("samples-case.jar"),
        mapOf(
            "s/Lib.class" to buildClass(
                "s/Lib",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER,
            ) {
                field(Opcodes.ACC_PUBLIC, "count", "I", null, null)
                method(abstractMethod, "greet", greet1, null, null)
                method(abstractMethod, "greet", greet2, null, null)
            },
            "s/App.class" to buildClass("s/App") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "s/Lib", "greet", greet1)
                }
            },
            "s/Util.class" to buildClass("s/Util") {
                method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "help", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "s/Lib", "greet", greet1)
                }
            },
            "s/AppTest.class" to buildClass("s/AppTest") {
                method(Opcodes.ACC_PUBLIC, "runTest", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "s/Lib", "greet", greet1)
                }
            },
            "s/Gen\$Inner.class" to buildClass("s/Gen\$Inner") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "s/Lib", "greet", greet1)
                }
            },
            "s/Other.class" to buildClass("s/Other") {
                method(Opcodes.ACC_PUBLIC, "work", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "s/Lib", "greet", greet2)
                }
            },
            "s/Bean.class" to buildClass("s/Bean") {
                method(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) {
                    invoke(Opcodes.INVOKESTATIC, "s/Util", "help", "()V")
                }
            },
            "s/Factory.class" to buildClass("s/Factory") {
                method(Opcodes.ACC_PUBLIC, "make", "()V", null, null) {
                    typeInsn(Opcodes.NEW, "s/Bean")
                    invoke(Opcodes.INVOKESPECIAL, "s/Bean", "<init>", "()V")
                }
            },
            "t/Lib.class" to buildClass("t/Lib"),
        ),
    )
    ArtifactTestJars.craftJar(
        dir.resolve("samples-case-sources.jar"),
        mapOf(
            "s/App.java" to
                """
                package s;
                public class App {
                    public void run() {
                        Lib lib = null;
                        lib.greet("hi");
                    }
                }
                """.trimIndent().toByteArray(Charsets.UTF_8),
        ),
    )
    return jar
}
