package dev.jdx.cli.commands

import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

/**
 * The crafted two-jar corpus every `diff` tier-2 suite in this package reads
 * (issue #23).
 *
 * The corpus is built here rather than read from `testfixtures`, for the reason
 * `index`'s own `DiffCaseJars` already states: a diff test whose fixture moved
 * underneath it fails for a reason that has nothing to do with the code under
 * test, and the fixture corpus belongs to other suites.
 *
 * **File names are the contract.** A report labels each side by file name, never
 * by an absolute path (AGENTS.md §2.5), so `diff-old.jar` / `diff-new.jar` are
 * what every assertion and every golden in this package sees, on every machine.
 */
internal object DiffCaseJars {

    /** The baseline side: a fixed, unversioned file name so output stays hermetic. */
    const val OLD_NAME: String = "diff-old.jar"

    /** The candidate side. */
    const val NEW_NAME: String = "diff-new.jar"

    /**
     * Compiles [sources] (file name → text) and zips the resulting `.class`
     * files into [jar]. `diff` reads bytecode, so the service tier needs classes
     * a real compiler produced rather than a description of one.
     *
     * The compiler is [ToolProvider.getSystemJavaCompiler] — the test runtime's
     * own JDK compiler, in-process: no `javac` on `PATH`, no subprocess, no
     * extra build dependency. Returns `null` on a JRE image with no compiler so
     * a caller can skip rather than fail (the `Javap.findJavap` precedent,
     * TESTING.md §5.1).
     */
    fun compile(jar: Path, sources: Map<String, String>): Path? {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: return null
        val classes = Files.createDirectories(jar.resolveSibling("${jar.fileName}.build"))
        val diagnostics = ByteArrayOutputStream()
        val units: Iterable<JavaFileObject> = sources.map { (name, text) -> sourceObject(name, text) }
        val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)
        val compiled = fileManager.use { manager ->
            // `getStandardFileManager` takes no writer; diagnostics reach us
            // through the task's `out`.
            compiler
                .getTask(PrintWriter(diagnostics, true), manager, null, listOf("-d", classes.toString()), null, units)
                .call()
        }
        if (compiled != true) {
            error("javac failed for $jar:\n" + diagnostics.toString(Charsets.UTF_8))
        }
        val entries = LinkedHashMap<String, ByteArray>()
        Files.walk(classes).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val entry = classes.relativize(file).toString().replace('\\', '/')
                entries[entry] = Files.readAllBytes(file)
            }
        }
        return jarFrom(jar, entries)
    }

    /** Zips [entries] (entry name → bytes) into [jar], entry order sorted. */
    fun jarFrom(jar: Path, entries: Map<String, ByteArray>): Path {
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((name, bytes) in entries.entries.sortedBy { it.key }) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    /**
     * An in-memory compilation unit. The name is the *package-qualified source
     * path* javac expects, and it is also the name the class file inside the jar
     * takes, so one string drives both halves.
     */
    private fun sourceObject(name: String, text: String): JavaFileObject =
        object : SimpleJavaFileObject(
            URI("string:///difffix/" + name),
            JavaFileObject.Kind.SOURCE,
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
        }
}

/**
 * The v1/v2 pair [DiffCommandsServiceTest] and [DiffCommandsGoldenTest] both read.
 *
 * One class carries one deliberate change per rule family, so the expected tally
 * is arithmetic rather than a sample: 7 breaking, 2 suspicious, 3 informational,
 * 1 type added, 1 type removed — 12 findings, which is also what makes
 * `--limit 2` and `--severity breaking` say something.
 *
 * Sources rather than ASM: `cli` has no ASM on its *compile* classpath (`:index`
 * keeps it `implementation`), and adding a test dependency for a fixture would be
 * a build change this suite does not get to make. The public surface of a
 * hand-written class like these does not move with the JDK, which is the only
 * thing the goldens depend on — not the class-file bytes.
 */
internal object DiffCorpus {

    /** The baseline side: one type that disappears, one that gains an interface. */
    val OLD_SOURCES: Map<String, String> = mapOf(
        "difffix/Api.java" to """
            package difffix;

            public class Api {
                public Api() { }

                public int width() { return 1; }

                public static int shared() { return 2; }

                public void legacy() { }

                public String checked() { return "x"; }

                public int variadic(int one) { return one; }

                public Object echo(java.util.List items) { return null; }

                public int sealedFinal() { return 3; }

                public int narrowed() { return 4; }
            }
        """.trimIndent(),
        "difffix/Extra.java" to """
            package difffix;

            public class Extra {
                public void gone() { }
            }
        """.trimIndent(),
    )

    /**
     * The candidate side: every change [OLD_SOURCES] needs, one per rule.
     *
     * Two of the changes are compiler artefacts worth naming, because they are
     * what a *real* upgrade looks like and a hand-written fixture would have
     * hidden both: `variadic(int)` → `variadic(int...)` changes the erased
     * descriptor (`(I)I` → `([I)I`) and so reports as the breaking
     * `PARAMETER_TYPE_CHANGED`, never as `VARARGS_CHANGED`; and `narrowed()`
     * going `private` leaves the public surface entirely, so the default report
     * calls it `MEMBER_REMOVED` and only `--visibility all` shows the
     * `MEMBER_VISIBILITY_NARROWED` underneath.
     *
     * `echo` is the one member whose *erased* shape never moves — parameterising a
     * raw `List` only rewrites the `Signature` attribute — which is the only way
     * to reach the suspicious `GENERIC_SIGNATURE_CHANGED`: any change to a
     * type-variable bound changes the erasure too, and the binary break then wins.
     */
    val NEW_SOURCES: Map<String, String> = mapOf(
        "difffix/Api.java" to """
            package difffix;

            public class Api implements Marker {
                public Api() { }

                public long width() { return 1L; }

                public int shared() { return 2; }

                public String checked() throws java.io.IOException { return "x"; }

                public int variadic(int... one) { return one.length; }

                public Object echo(java.util.List<String> items) { return null; }

                public final int sealedFinal() { return 3; }

                private int narrowed() { return 4; }

                public int fresh() { return 5; }
            }
        """.trimIndent(),
        "difffix/Marker.java" to """
            package difffix;

            public interface Marker { }
        """.trimIndent(),
    )

    /**
     * Compiles both sides under [dir] and returns `(old, new)`, or `null` when
     * the test runtime has no Java compiler (a skip, never a failure).
     */
    fun build(dir: Path): Pair<Path, Path>? {
        val old = DiffCaseJars.compile(dir.resolve(DiffCaseJars.OLD_NAME), OLD_SOURCES) ?: return null
        val new = DiffCaseJars.compile(dir.resolve(DiffCaseJars.NEW_NAME), NEW_SOURCES) ?: return null
        return old to new
    }
}
