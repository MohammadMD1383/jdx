package dev.jdx.index.asm

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Tier-1 coverage for [ModuleInfoReader] (issue #25 module-info slice).
 */
class ModuleInfoReaderTest {

    @Test
    fun `parses a crafted open module with every directive kind`() {
        val bytes = craftModuleInfo(
            name = "com.example.mod",
            open = true,
            version = "1.0",
            requires = listOf(
                Triple("java.base", Opcodes.ACC_MANDATED, null),
                Triple("java.logging", Opcodes.ACC_TRANSITIVE, null),
            ),
            exports = listOf("com/example/api" to emptyArray(), "com/example/spi" to arrayOf("com.friend")),
            opens = listOf("com/example/internal" to arrayOf("com.friend")),
            uses = listOf("com/example/Spi"),
            provides = listOf("com/example/Spi" to arrayOf("com/example/Impl", "com/example/Other")),
        )
        val parsed = ModuleInfoReader.read(bytes)
        parsed shouldNotBe null
        parsed!!.name shouldBe "com.example.mod"
        parsed.open shouldBe true
        parsed.version shouldBe "1.0"
        parsed.requires.map { it.module } shouldBe listOf("java.base", "java.logging")
        parsed.requires[0].flags shouldBe listOf("mandated")
        parsed.requires[1].flags shouldBe listOf("transitive")
        parsed.exports.map { it.packageName } shouldBe listOf("com.example.api", "com.example.spi")
        parsed.exports[1].to shouldBe listOf("com.friend")
        parsed.opens.single().packageName shouldBe "com.example.internal"
        parsed.uses shouldBe listOf("com.example.Spi")
        parsed.provides.single().service shouldBe "com.example.Spi"
        parsed.provides.single().providers shouldBe listOf("com.example.Impl", "com.example.Other")
    }

    @Test
    fun `corrupt and non-module bytes yield null`() {
        ModuleInfoReader.read(byteArrayOf(1, 2, 3)) shouldBe null
        ModuleInfoReader.read(byteArrayOf()) shouldBe null
        // A regular class has no Module attribute.
        ModuleInfoReader.read(buildClass("com/example/Plain")) shouldBe null
    }

    @Test
    fun `internalToDotted swaps slashes`() {
        ModuleInfoReader.internalToDotted("com/foo/Bar") shouldBe "com.foo.Bar"
        ModuleInfoReader.internalToDotted("already.dotted") shouldBe "already.dotted"
    }
}

/**
 * Builds a minimal `module-info.class` for tests. Pure ASM — no disk.
 * Public so service-layer case jars in another package can reuse it.
 */
fun craftModuleInfo(
    name: String,
    open: Boolean = false,
    version: String? = null,
    requires: List<Triple<String, Int, String?>> = listOf(
        Triple("java.base", Opcodes.ACC_MANDATED, null),
    ),
    exports: List<Pair<String, Array<String>>> = emptyList(),
    opens: List<Pair<String, Array<String>>> = emptyList(),
    uses: List<String> = emptyList(),
    provides: List<Pair<String, Array<String>>> = emptyList(),
): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null)
    val access = if (open) Opcodes.ACC_OPEN else 0
    val mv = writer.visitModule(name, access, version)
    for ((module, flags, ver) in requires) {
        mv.visitRequire(module, flags, ver)
    }
    for ((packaze, to) in exports) {
        // No third arg → empty varargs → unqualified export (do not pass a
        // Kotlin `null`, which becomes a one-element String[] {null}).
        if (to.isEmpty()) mv.visitExport(packaze, 0)
        else mv.visitExport(packaze, 0, *to)
    }
    for ((packaze, to) in opens) {
        if (to.isEmpty()) mv.visitOpen(packaze, 0)
        else mv.visitOpen(packaze, 0, *to)
    }
    for (service in uses) {
        mv.visitUse(service)
    }
    for ((service, providers) in provides) {
        mv.visitProvide(service, *providers)
    }
    mv.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
}
