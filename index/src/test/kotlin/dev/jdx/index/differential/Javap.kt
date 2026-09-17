package dev.jdx.index.differential

import java.io.File

/**
 * The `javap` oracle behind the differential harness (T-056, TESTING.md §5.1).
 *
 * `javap -p -s` is the only readily available program that prints ground truth
 * for an arbitrary class file — member names plus erased descriptors — without
 * anyone writing the truth down. This object finds `javap`, runs it, and parses
 * its output into [MemberKey] sets; [ServiceDifferential] diffs those sets
 * against what `jdx members --declared --access all --include-synthetic`
 * reports for the same class.
 *
 * The parser is shared (not copied per suite): the T-008 reader-level
 * differential in `asm.AsmClassReaderDifferentialTest` delegates here too, so a
 * parsing fix lands everywhere at once.
 */
internal object Javap {

    /**
     * One declared member as both sides name it: the simple name (`<init>` for
     * constructors, `<clinit>` for the class initialiser) plus the erased JVM
     * descriptor (`()V`, `Ljava/lang/String;`).
     */
    internal data class MemberKey(val name: String, val descriptor: String)

    /**
     * Locates `javap`: the test runtime's JDK first, `PATH` as a fallback.
     * Returns `null` when absent — never throws. Callers skip gracefully via
     * `assumeTrue(javap != null, ...)` (TESTING.md §5.1), so a missing JDK tool
     * never fails the build.
     */
    internal fun findJavap(): String? {
        val homeJavap = File(System.getProperty("java.home"), "bin/javap")
        if (homeJavap.canExecute()) return homeJavap.absolutePath
        return try {
            val probe = ProcessBuilder("javap", "-version").redirectErrorStream(true).start()
            val exitedZero = probe.waitFor() == 0
            probe.inputStream.readBytes()
            if (exitedZero) "javap" else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs `javap -p -s -classpath [classpath] [binaryName]`.
     *
     * Flags precede the class name: a trailing `-s` is parsed as a class name
     * (L-018). Returns the output lines, or `null` when `javap` itself cannot
     * read the class (corrupt entry, unknown name) — the soak suite records
     * those as skips, never failures.
     */
    internal fun tryRun(javap: String, classpath: String, binaryName: String): List<String>? {
        return try {
            val process = ProcessBuilder(javap, "-p", "-s", "-classpath", classpath, binaryName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val exit = process.waitFor()
            if (exit == 0) output.lines() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses `javap -p -s` output into `(name, descriptor)` pairs. Each member
     * line is followed by its `descriptor:` line; constructors print as the FQN
     * (they contain a `.`, unlike method names) and `static {};` is the class
     * initialiser. Header lines (`Compiled from`, the class declaration, the
     * closing brace) carry no member and are dropped.
     */
    internal fun parseMembers(output: List<String>): Set<MemberKey> {
        val members = mutableSetOf<MemberKey>()
        var pending: String? = null
        for (line in output) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "}" ||
                trimmed.startsWith("Compiled from") || trimmed.endsWith("{")
            ) {
                continue
            }
            if (trimmed.startsWith("descriptor:")) {
                val descriptor = trimmed.removePrefix("descriptor:").trim()
                check(pending != null) { "javap descriptor without a member line: $trimmed" }
                members.add(MemberKey(pending, descriptor))
                pending = null
            } else {
                pending = normaliseMemberLine(trimmed)
            }
        }
        return members
    }

    private fun normaliseMemberLine(line: String): String {
        val bare = line.removeSuffix(";")
        if ('(' in bare) {
            val nameToken = bare.substringBefore('(').trim().substringAfterLast(' ')
            return if ('.' in nameToken) "<init>" else nameToken
        }
        if (bare.trim() == "static {}") return "<clinit>"
        return bare.trim().substringAfterLast(' ')
    }
}
