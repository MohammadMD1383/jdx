package dev.jdx.testsupport.golden

/**
 * A minimal unified-diff renderer for golden-test failures (T-054).
 *
 * This is what a contributor reads when a golden comparison fails, so the
 * output follows the familiar `---`/`+++`/`@@` shape instead of dumping two
 * blobs. Pure string work with no dependencies — safe to share from every
 * test source set. Deterministic: same inputs always yield identical bytes.
 */
object UnifiedDiff {

    /** Context lines kept around each change inside a hunk. */
    const val CONTEXT_LINES: Int = 3

    /** Body lines (hunk headers included) emitted before an omission trailer. */
    const val MAX_BODY_LINES: Int = 200

    /**
     * Renders [expected] vs [actual] as a unified diff, or `""` when they are
     * equal. Line model: empty text is zero lines; otherwise lines split on
     * `"\n"` (callers compare file bytes with the trailing newline stripped,
     * so no `\ No newline` marker is needed).
     */
    fun diff(
        expected: String,
        actual: String,
        expectedLabel: String = "expected",
        actualLabel: String = "actual",
    ): String {
        val expectedLines = splitLines(expected)
        val actualLines = splitLines(actual)
        if (expectedLines == actualLines) return ""
        // The equal prefix/suffix are aligned in both files, so the changed
        // middle starts at the same index on each side. Only context-window
        // slices of the equal edges become Keep ops — the rest is too far
        // from any change to earn screen space.
        val prefix = commonPrefix(expectedLines, actualLines)
        val suffix = commonSuffix(expectedLines, actualLines, prefix)
        val leadStart = maxOf(0, prefix - CONTEXT_LINES)
        val expectedTrailEnd = minOf(expectedLines.size, expectedLines.size - suffix + CONTEXT_LINES)
        val actualTrailEnd = minOf(actualLines.size, actualLines.size - suffix + CONTEXT_LINES)
        val ops = buildList {
            for (offset in leadStart until prefix) add(Op.Keep(offset, offset))
            addAll(
                editScript(
                    expectedLines.subList(prefix, expectedLines.size - suffix),
                    actualLines.subList(prefix, actualLines.size - suffix),
                    baseExpected = prefix,
                    baseActual = prefix,
                ),
            )
            val trailLength = minOf(
                expectedTrailEnd - (expectedLines.size - suffix),
                actualTrailEnd - (actualLines.size - suffix),
            )
            for (offset in 0 until trailLength) {
                add(Op.Keep(expectedLines.size - suffix + offset, actualLines.size - suffix + offset))
            }
        }
        val hunks = splitHunks(ops)
        val body = buildList {
            for (hunk in hunks) {
                add(hunkHeader(hunk, ops))
                for (op in ops.subList(hunk.first, hunk.last + 1)) {
                    when (op) {
                        is Op.Keep -> add(" " + expectedLines[op.expectedIndex])
                        is Op.Delete -> add("-" + expectedLines[op.expectedIndex])
                        is Op.Insert -> add("+" + actualLines[op.actualIndex])
                    }
                }
            }
        }
        val renderedBody = if (body.size > MAX_BODY_LINES) {
            body.take(MAX_BODY_LINES) + "... (${body.size - MAX_BODY_LINES} more diff lines omitted)"
        } else {
            body
        }
        return (listOf("--- $expectedLabel", "+++ $actualLabel") + renderedBody)
            .joinToString("\n")
    }

    /** One step of the edit script; every op carries its position in both files. */
    private sealed interface Op {
        val expectedIndex: Int
        val actualIndex: Int
        data class Keep(override val expectedIndex: Int, override val actualIndex: Int) : Op
        data class Delete(override val expectedIndex: Int, override val actualIndex: Int) : Op
        data class Insert(override val expectedIndex: Int, override val actualIndex: Int) : Op
    }

    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split("\n")

    private fun commonPrefix(first: List<String>, second: List<String>): Int {
        var prefix = 0
        while (prefix < first.size && prefix < second.size && first[prefix] == second[prefix]) {
            prefix++
        }
        return prefix
    }

    private fun commonSuffix(first: List<String>, second: List<String>, prefix: Int): Int {
        var suffix = 0
        while (suffix < first.size - prefix && suffix < second.size - prefix &&
            first[first.size - 1 - suffix] == second[second.size - 1 - suffix]
        ) {
            suffix++
        }
        return suffix
    }

    /**
     * Least-edit script turning [middleExpected] into [middleActual] via LCS.
     * The bases are the full-file offsets of the middle blocks, so emitted
     * indices are absolute. Past a million DP cells the LCS table would dwarf
     * any readable diff, so the middle degrades to one delete-all/insert-all
     * block — still a correct diff, just coarser.
     */
    private fun editScript(
        middleExpected: List<String>,
        middleActual: List<String>,
        baseExpected: Int,
        baseActual: Int,
    ): List<Op> {
        if (middleExpected.size.toLong() * middleActual.size.toLong() > 1_000_000L) {
            return buildList {
                middleExpected.indices.forEach { add(Op.Delete(baseExpected + it, baseActual)) }
                middleActual.indices.forEach { add(Op.Insert(baseExpected + middleExpected.size, baseActual + it)) }
            }
        }
        val table = Array(middleExpected.size + 1) { IntArray(middleActual.size + 1) }
        for (i in 1..middleExpected.size) {
            for (j in 1..middleActual.size) {
                table[i][j] = if (middleExpected[i - 1] == middleActual[j - 1]) {
                    table[i - 1][j - 1] + 1
                } else {
                    maxOf(table[i - 1][j], table[i][j - 1])
                }
            }
        }
        // Backtrack from the end. Ties prefer Insert, so the reversed
        // forward script lists deletions before insertions within a
        // change — the order every unified-diff reader expects. The rule
        // is fixed, so output stays deterministic across runs.
        val ops = mutableListOf<Op>()
        var i = middleExpected.size
        var j = middleActual.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && middleExpected[i - 1] == middleActual[j - 1] -> {
                    ops.add(Op.Keep(baseExpected + i - 1, baseActual + j - 1))
                    i--
                    j--
                }
                j > 0 && (i == 0 || table[i][j - 1] >= table[i - 1][j]) -> {
                    ops.add(Op.Insert(baseExpected + i, baseActual + j - 1))
                    j--
                }
                else -> {
                    ops.add(Op.Delete(baseExpected + i - 1, baseActual + j))
                    i--
                }
            }
        }
        return ops.reversed()
    }

    /**
     * Groups [ops] into index ranges, one per hunk. A keep-run longer than
     * twice the context closes the current hunk and opens the next, so
     * distant changes render as separate hunks with `@@` headers each.
     */
    private fun splitHunks(ops: List<Op>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var hunkStart = 0
        var i = 0
        while (i < ops.size) {
            if (ops[i] !is Op.Keep) {
                i++
                continue
            }
            var j = i
            while (j < ops.size && ops[j] is Op.Keep) j++
            if (j - i > 2 * CONTEXT_LINES) {
                if (ops.subList(hunkStart, i).any { it !is Op.Keep }) {
                    ranges.add(hunkStart until i + CONTEXT_LINES)
                }
                hunkStart = j - CONTEXT_LINES
            }
            i = j
        }
        var hunkEnd = ops.size
        // Trim trailing context that exceeds the context window.
        var trailingKeeps = 0
        while (hunkEnd > hunkStart && ops[hunkEnd - 1] is Op.Keep) {
            trailingKeeps++
            hunkEnd--
        }
        hunkEnd += minOf(trailingKeeps, CONTEXT_LINES)
        // A trailing keep-run longer than twice the context leaves a
        // change-free tail behind: those lines are already covered by the
        // previous hunk's trailing context, so they earn no hunk of their own.
        if (hunkEnd > hunkStart && ops.subList(hunkStart, hunkEnd).any { it !is Op.Keep }) {
            ranges.add(hunkStart until hunkEnd)
        }
        return ranges
    }

    private fun hunkHeader(hunk: IntRange, ops: List<Op>): String {
        val hunkOps = ops.subList(hunk.first, hunk.last + 1)
        val first = hunkOps.first()
        val expectedCount = hunkOps.count { it is Op.Keep || it is Op.Delete }
        val actualCount = hunkOps.count { it is Op.Keep || it is Op.Insert }
        return "@@ -${formatRange(first.expectedIndex, expectedCount)} " +
            "+${formatRange(first.actualIndex, actualCount)} @@"
    }

    /** Standard compact form: `start,0` for an empty side, bare start for one line. */
    private fun formatRange(startBefore: Int, count: Int): String = when (count) {
        0 -> "$startBefore,0"
        1 -> "${startBefore + 1}"
        else -> "${startBefore + 1},$count"
    }
}
