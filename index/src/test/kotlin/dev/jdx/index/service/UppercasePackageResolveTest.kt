package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import dev.jdx.index.service.JdxService.RootsSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import java.nio.file.Path

/**
 * End-to-end coverage for D-025 uppercase package segments (issue #1 soak
 * fallout): jars like JNA ship types under `…win32.COM.…`, and both the `$`
 * binary form and the dot-only top-level form must resolve against that jar
 * instead of exiting 1 while suggesting the same FQN.
 */
class UppercasePackageResolveTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `dollar form under uppercase package package resolves`() {
        val jar = craftUppercasePackageJar()
        val outcome = JdxService.members(
            "com.example.win32.COM.Outer\$Inner",
            RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false),
        )
        outcome.shouldBeInstanceOf<JdxService.ServiceOutcome.MemberList>()
        outcome.exitCode shouldBe 0
        outcome.renderText(false) shouldContain "com.example.win32.COM.Outer\$Inner"
    }

    @Test
    fun `dot-only top-level under uppercase package resolves via recovery`() {
        val jar = craftUppercasePackageJar()
        val outcome = JdxService.members(
            "com.example.win32.COM.Outer",
            RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false),
        )
        outcome.shouldBeInstanceOf<JdxService.ServiceOutcome.MemberList>()
        outcome.exitCode shouldBe 0
        outcome.renderText(false) shouldContain "com.example.win32.COM.Outer"
    }

    private fun craftUppercasePackageJar(): Path {
        val outer = buildClass("com/example/win32/COM/Outer") {
            method(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) {
                invoke(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V")
                code(Opcodes.RETURN to null)
            }
            method(Opcodes.ACC_PUBLIC, "ping", "()V", null, null) {
                code(Opcodes.RETURN to null)
            }
            inner("com/example/win32/COM/Outer\$Inner", "com/example/win32/COM/Outer", "Inner", Opcodes.ACC_PUBLIC)
        }
        val inner = buildClass("com/example/win32/COM/Outer\$Inner") {
            method(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) {
                invoke(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V")
                code(Opcodes.RETURN to null)
            }
            method(Opcodes.ACC_PUBLIC, "pong", "()V", null, null) {
                code(Opcodes.RETURN to null)
            }
        }
        val jar = temp.resolve("uppercase-pkg.jar")
        ArtifactTestJars.craftJar(
            jar,
            mapOf(
                "com/example/win32/COM/Outer.class" to outer,
                "com/example/win32/COM/Outer\$Inner.class" to inner,
            ),
        )
        Files.exists(jar) shouldBe true
        return jar
    }
}
