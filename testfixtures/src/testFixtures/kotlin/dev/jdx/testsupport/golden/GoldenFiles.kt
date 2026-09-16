package dev.jdx.testsupport.golden

import java.io.File

/**
 * The shared golden-file helper (T-054): compare rendered output against
 * files under `src/test/resources/golden/`, rewritten with
 * `-Pgolden.update=true`.
 *
 * Two modes, chosen by [updateMode]:
 * - Verify (default): a missing file or a byte mismatch throws
 *   [AssertionError] whose message carries a [UnifiedDiff] — never two bare
 *   blobs. An orphaned golden (no test references it) fails the same way.
 * - Update: every expected file is (re)written with a trailing newline and
 *   the call prints one summary line per rewritten file, so the blast radius
 *   is visible before committing (docs/TESTING.md §14). Orphans still fail:
 *   deleting coverage must be a conscious `git rm`, not a side effect.
 *
 * Golden files are plain text (`.txt`/`.json`), committed, and readable in a
 * PR diff. Comparisons strip one trailing newline, so editors that ensure a
 * final newline never cause phantom mismatches.
 */
object GoldenFiles {

    /** System property carrying `-Pgolden.update` into the test JVM. */
    const val UPDATE_PROPERTY: String = "jdx.golden.update"

    /** True when goldens should be rewritten instead of compared. */
    fun isUpdateMode(getProperty: (String) -> String? = System::getProperty): Boolean =
        getProperty(UPDATE_PROPERTY) == "true"

    /**
     * Checks every entry of [contents] (file name to expected text) against
     * [dir], then fails on orphans. In update mode prints the per-file
     * rewrite summary via [out] and returns the rewritten names, sorted.
     *
     * @throws AssertionError on the first mismatch, missing file, or orphan.
     */
    fun verifyAll(
        dir: File,
        contents: Map<String, String>,
        updateMode: Boolean = isUpdateMode(),
        out: (String) -> Unit = ::println,
    ): List<String> {
        val rewritten = mutableListOf<String>()
        for ((fileName, actual) in contents.toSortedMap()) {
            if (check(dir, fileName, actual, updateMode)) rewritten.add(fileName)
        }
        failOnOrphans(dir, contents.keys)
        if (updateMode) {
            out("golden.update: rewrote ${rewritten.size} file(s) under $dir")
            rewritten.sorted().forEach { out("golden.update:   $it") }
        }
        return rewritten.sorted()
    }

    /**
     * Checks one golden file. Returns true when update mode rewrote it.
     *
     * @throws AssertionError on mismatch or on a missing file in verify mode.
     */
    fun check(
        dir: File,
        fileName: String,
        actual: String,
        updateMode: Boolean = isUpdateMode(),
    ): Boolean {
        val file = File(dir, fileName)
        if (updateMode) {
            file.parentFile.mkdirs()
            file.writeText(actual + "\n")
            return true
        }
        if (!file.isFile) {
            throw AssertionError(
                "missing golden file: ${file.path} (run with -Pgolden.update=true to create it)",
            )
        }
        val expected = file.readText().removeSuffix("\n")
        if (expected != actual) {
            throw AssertionError(
                "golden mismatch: ${file.path}\n" +
                    UnifiedDiff.diff(expected, actual, "${file.name} (expected)", "${file.name} (actual)"),
            )
        }
        return false
    }

    /**
     * Fails when [dir] holds a file no test claims. A missing directory is
     * not an error — a suite with no goldens yet has no orphans.
     *
     * @throws AssertionError listing every orphan, sorted.
     */
    fun failOnOrphans(dir: File, expectedNames: Set<String>) {
        if (!dir.isDirectory) return
        val orphans = (dir.listFiles { file -> file.isFile }?.map { it.name } ?: emptyList())
            .toSet() - expectedNames
        if (orphans.isNotEmpty()) {
            throw AssertionError(
                "orphaned golden files (no test references them): ${orphans.sorted()}",
            )
        }
    }
}
