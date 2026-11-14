package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import java.nio.file.Path
import org.objectweb.asm.Opcodes

/**
 * Crafted call-graph corpus (T-033, tier 2): ASM-built classes with fully
 * controlled `METHOD_CALL` edges — a chain, a diamond, a two-cycle, a
 * self-recursion, overloads, a constructor call and a field — so the expected
 * trees are exact, not sampled.
 *
 * - `c.Lib` declares two abstract `greet` overloads (`(String)` and
 *   `(String,int)`) plus an `int count` field (fields have no call hierarchy).
 * - `c.App#run()` calls `greet(String)` and `c.Util#help()`.
 * - `c.Util#help()` (static) calls `greet(String)` — the diamond base.
 * - `c.Main#main(String[])` and `c.Top#go()` call `c.App#run()`; `c.Top#go()`
 *   also calls `c.Util#help()` — so `Top#go` repeats under both depth-2
 *   branches (tree semantics).
 * - `c.Other#work()` calls the `(String,int)` overload only.
 * - `c.Loop#a()` and `c.Loop#b()` call each other (two-cycle).
 * - `c.Self#tick()` calls itself (self-recursion).
 * - `c.Factory#make()` constructs `c.Bean` (`NEW` is a type edge, the
 *   `INVOKESPECIAL <init>` is the call edge); `c.Bean#<init>` calls
 *   `c.Util#help()`.
 * - `d.Lib` is an empty namesake: short `Lib` queries are ambiguous (exit 2).
 */
internal fun buildCallsCaseJar(dir: Path): Path {
    val abstractMethod = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
    val greet1 = "(Ljava/lang/String;)Ljava/lang/String;"
    val greet2 = "(Ljava/lang/String;I)Ljava/lang/String;"
    return ArtifactTestJars.craftJar(
        dir.resolve("calls-case.jar"),
        mapOf(
            "c/Lib.class" to buildClass(
                "c/Lib",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER,
            ) {
                field(Opcodes.ACC_PUBLIC, "count", "I", null, null)
                method(abstractMethod, "greet", greet1, null, null)
                method(abstractMethod, "greet", greet2, null, null)
            },
            "c/App.class" to buildClass("c/App") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/Lib", "greet", greet1)
                    invoke(Opcodes.INVOKESTATIC, "c/Util", "help", "()V")
                }
            },
            "c/Util.class" to buildClass("c/Util") {
                method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "help", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/Lib", "greet", greet1)
                }
            },
            "c/Main.class" to buildClass("c/Main") {
                method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/App", "run", "()V")
                }
            },
            "c/Top.class" to buildClass("c/Top") {
                method(Opcodes.ACC_PUBLIC, "go", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/App", "run", "()V")
                    invoke(Opcodes.INVOKESTATIC, "c/Util", "help", "()V")
                }
            },
            "c/Other.class" to buildClass("c/Other") {
                method(Opcodes.ACC_PUBLIC, "work", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/Lib", "greet", greet2)
                }
            },
            "c/Loop.class" to buildClass("c/Loop") {
                method(Opcodes.ACC_PUBLIC, "a", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/Loop", "b", "()V")
                }
                method(Opcodes.ACC_PUBLIC, "b", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/Loop", "a", "()V")
                }
            },
            "c/Self.class" to buildClass("c/Self") {
                method(Opcodes.ACC_PUBLIC, "tick", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "c/Self", "tick", "()V")
                }
            },
            "c/Bean.class" to buildClass("c/Bean") {
                method(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) {
                    invoke(Opcodes.INVOKESTATIC, "c/Util", "help", "()V")
                }
            },
            "c/Factory.class" to buildClass("c/Factory") {
                method(Opcodes.ACC_PUBLIC, "make", "()V", null, null) {
                    typeInsn(Opcodes.NEW, "c/Bean")
                    invoke(Opcodes.INVOKESPECIAL, "c/Bean", "<init>", "()V")
                }
            },
            "d/Lib.class" to buildClass("d/Lib"),
        ),
    )
}

/**
 * Override / interface call-graph corpus (issue #6): virtual-dispatch edges
 * that exact name+descriptor matching misses.
 *
 * - `o.Iface#act()` is abstract; `o.Impl` overrides it.
 * - `o.ViaIface#run()` calls through the interface (`INVOKEINTERFACE`).
 * - `o.ViaImpl#run()` calls through the concrete type (`INVOKEVIRTUAL`).
 * - `o.Base#hook()` / `o.Child#hook()` is a class override pair with the same
 *   shape (call sites through base vs child).
 * - `o.StaticHost#util()` is static — override expansion must leave it alone.
 */
internal fun buildOverrideCallsCaseJar(dir: Path): Path {
    val act = "()V"
    val abstractMethod = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
    return ArtifactTestJars.craftJar(
        dir.resolve("override-calls-case.jar"),
        mapOf(
            "o/Iface.class" to buildClass(
                "o/Iface",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                superName = "java/lang/Object",
            ) {
                method(abstractMethod, "act", act, null, null)
            },
            "o/Impl.class" to buildClass(
                "o/Impl",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
                interfaces = arrayOf("o/Iface"),
            ) {
                method(Opcodes.ACC_PUBLIC, "act", act, null, null) {
                    code(Opcodes.RETURN to null)
                }
            },
            "o/ViaIface.class" to buildClass("o/ViaIface") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKEINTERFACE, "o/Iface", "act", act)
                    code(Opcodes.RETURN to null)
                }
            },
            "o/ViaImpl.class" to buildClass("o/ViaImpl") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "o/Impl", "act", act)
                    code(Opcodes.RETURN to null)
                }
            },
            "o/Base.class" to buildClass(
                "o/Base",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            ) {
                method(Opcodes.ACC_PUBLIC, "hook", act, null, null) {
                    code(Opcodes.RETURN to null)
                }
            },
            "o/Child.class" to buildClass(
                "o/Child",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
                superName = "o/Base",
            ) {
                method(Opcodes.ACC_PUBLIC, "hook", act, null, null) {
                    code(Opcodes.RETURN to null)
                }
            },
            "o/ViaBase.class" to buildClass("o/ViaBase") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "o/Base", "hook", act)
                    code(Opcodes.RETURN to null)
                }
            },
            "o/ViaChild.class" to buildClass("o/ViaChild") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKEVIRTUAL, "o/Child", "hook", act)
                    code(Opcodes.RETURN to null)
                }
            },
            "o/StaticHost.class" to buildClass("o/StaticHost") {
                method(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "util", act, null, null) {
                    code(Opcodes.RETURN to null)
                }
            },
            "o/ViaStatic.class" to buildClass("o/ViaStatic") {
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    invoke(Opcodes.INVOKESTATIC, "o/StaticHost", "util", act)
                    code(Opcodes.RETURN to null)
                }
            },
        ),
    )
}
