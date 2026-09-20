package dev.jdx.index.service

import dev.jdx.index.asm.buildClass
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.Opcodes

/**
 * Crafted doc-inheritance corpus (T-025, tier 2): a three-level `doc.*`
 * hierarchy whose bytecode (ASM-built, abstract so no code attributes are
 * needed) and sources (hand-written javadoc shapes) are fully controlled.
 *
 * - `doc.Base` documents its class, its `greet(String)` method (description,
 *   `{@code}`, `{@link}`, `@param`, `@return`), its `name` field and its
 *   constructor.
 * - `doc.Child` documents its class only: `greet` and `name` are
 *   undocumented overrides — `greet` must inherit from `Base`, `name` must
 *   not (fields hide, D-037).
 * - `doc.GrandChild` documents its class not at all and its `greet` with an
 *   `{@inheritDoc}` comment: the walk still passes through the undocumented
 *   `Child` to `Base` (transitivity), substituting the tag.
 * - `doc.Traffic` documents its class and its `RED` entry (the enum-entry
 *   shape, whose bytecode member is a field but whose source declaration is
 *   an entry).
 */
internal data class DocCaseJars(val binary: Path, val sources: Path)

internal fun buildDocCaseJars(dir: Path): DocCaseJars {
    val binary = dir.resolve("case.jar")
    val sources = dir.resolve("case-sources.jar")
    val abstractClass = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
    val abstractMethod = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
    writeDocJar(
        binary,
        mapOf(
            "doc/Base.class" to buildClass("doc/Base", access = abstractClass) {
                method(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null) {
                    code(Opcodes.ALOAD to 0)
                    invoke(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V")
                    code(Opcodes.RETURN to null)
                }
                method(abstractMethod, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null)
                field(Opcodes.ACC_PROTECTED, "name", "Ljava/lang/String;", null, null)
            },
            "doc/Child.class" to buildClass(
                "doc/Child",
                access = abstractClass,
                superName = "doc/Base",
            ) {
                method(abstractMethod, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null)
                field(Opcodes.ACC_PROTECTED, "name", "Ljava/lang/String;", null, null)
            },
            "doc/GrandChild.class" to buildClass(
                "doc/GrandChild",
                access = abstractClass,
                superName = "doc/Child",
            ) {
                method(abstractMethod, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null)
            },
            "doc/Traffic.class" to buildClass(
                "doc/Traffic",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_ENUM,
                superName = "java/lang/Enum",
            ) {
                field(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                    "RED",
                    "Ldoc/Traffic;",
                    null,
                    null,
                )
                field(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                    "YELLOW",
                    "Ldoc/Traffic;",
                    null,
                    null,
                )
            },
        ),
    )
    writeDocJar(
        sources,
        mapOf(
            "doc/Base.java" to
                """
                package doc;
                /** Base widgets: the documented supertype. */
                public abstract class Base {
                    /** Builds a Base. */
                    public Base() {
                    }
                    /**
                     * Greets warmly.
                     * Uses {@code Widget} and {@link doc.Base#greet(String) one-arg greet}.
                     * @param name who to greet
                     * @return the greeting
                     */
                    public abstract String greet(String name);
                    /** The shared name. */
                    protected String name;
                }
                """.trimIndent().toByteArray(Charsets.UTF_8),
            "doc/Child.java" to
                """
                package doc;
                /** Child widgets. */
                public abstract class Child extends Base {
                    @Override
                    public abstract String greet(String name);
                    protected String name;
                }
                """.trimIndent().toByteArray(Charsets.UTF_8),
            "doc/GrandChild.java" to
                """
                package doc;
                public abstract class GrandChild extends Child {
                    /** {@inheritDoc} With grandchild emphasis. */
                    @Override
                    public abstract String greet(String name);
                }
                """.trimIndent().toByteArray(Charsets.UTF_8),
            "doc/Traffic.java" to
                """
                package doc;
                /** Signals. */
                public enum Traffic {
                    /** Stop now. */
                    RED,
                    /** Caution. */
                    YELLOW;
                }
                """.trimIndent().toByteArray(Charsets.UTF_8),
        ),
    )
    return DocCaseJars(binary, sources)
}

internal fun writeDocJar(jar: Path, entries: Map<String, ByteArray>) {
    ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
        entries.entries.sortedBy { it.key }.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}
