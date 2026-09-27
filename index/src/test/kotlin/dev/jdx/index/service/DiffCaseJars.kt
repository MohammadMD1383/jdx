package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.buildClass
import java.nio.file.Path
import org.objectweb.asm.Opcodes

/**
 * Crafted two-jar corpus for the `diff` service tests (issue #23, tier 2).
 *
 * Every class here is assembled with ASM at test time, so the expected answer is
 * known exactly and one declaration can be moved without disturbing the rest.
 * The suite deliberately does **not** read the `testfixtures` corpus: the v1/v2
 * pair there belongs to another task and is free to change shape underneath
 * these assertions, and a diff test whose fixture moved would fail for a reason
 * that has nothing to do with the code under test.
 */

/** One declaration placed on a crafted class: JVM name, descriptor, access flags. */
internal data class DiffMember(
    val name: String,
    val descriptor: String,
    val access: Int = Opcodes.ACC_PUBLIC,
)

/** One crafted type: a binary name plus the methods and fields it declares. */
internal data class DiffType(
    val binaryName: String,
    val methods: List<DiffMember> = emptyList(),
    val fields: List<DiffMember> = emptyList(),
    /**
     * Internal name of the superclass, as the class file spells it. A *non*-universal
     * one (`d/Base`, not `java/lang/Object`) is what makes the differ's inheritance
     * check genuinely unanswerable, which is the only way to reach its
     * "inheritance not checked" detail — `java.lang.Object` is a universal root and
     * is deliberately not treated as a missing supertype.
     */
    val superName: String = "java/lang/Object",
)

/**
 * Writes one side of the pair.
 *
 * [types] become `d/Greeter.class` entries; [corruptEntries] become class
 * entries holding bytes that are not a class file at all, which is the fault
 * injection of TESTING.md §7 — the entry exists, so the artifact "serves" a
 * class, and the reader has to degrade rather than throw.
 */
internal fun diffJar(
    jar: Path,
    types: List<DiffType> = emptyList(),
    corruptEntries: List<String> = emptyList(),
): Path {
    val entries = LinkedHashMap<String, ByteArray>()
    for (type in types) {
        val internalName = type.binaryName.replace('.', '/')
        entries["$internalName.class"] = buildDiffClass(internalName, type)
    }
    for (entry in corruptEntries) entries[entry] = CORRUPT_CLASS_BYTES
    return ArtifactTestJars.craftJar(jar, entries)
}

/** `d.Greeter` with the given declarations, plus the no-arg constructor a real class has. */
internal fun diffClass(binaryName: String, methods: List<DiffMember> = emptyList()): ByteArray =
    buildDiffClass(binaryName.replace('.', '/'), DiffType(binaryName, methods))

private fun buildDiffClass(internalName: String, type: DiffType): ByteArray {
    return buildClass(
        internalName,
        access = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
        superName = type.superName,
    ) {
        // Every real class has one, so a "removed method" finding in a test can
        // never accidentally be about the constructor. The owner is the declared
        // superclass, so a class with a non-Object super is still well formed.
        method(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) {
            invoke(Opcodes.INVOKESPECIAL, type.superName, "<init>", "()V")
            code(Opcodes.RETURN to null)
        }
        for (member in type.methods) {
            method(member.access, member.name, member.descriptor, null, null) {
                code(Opcodes.RETURN to null)
            }
        }
        for (member in type.fields) {
            field(member.access, member.name, member.descriptor, null, null)
        }
    }
}

/**
 * Bytes that are definitively not a class file: shorter than the 8-byte class
 * header, so [dev.jdx.index.asm.AsmClassReader] refuses them before it ever looks
 * for a major version. Three bytes is enough to pin the branch and keeps the
 * failure from depending on which check inside the reader happens to run first.
 */
private val CORRUPT_CLASS_BYTES: ByteArray = byteArrayOf(0x01, 0x02, 0x03)
