package dev.jdx.cli.bench

import dev.jdx.cli.render.benchResult
import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Test

/**
 * Tier 1: `jdx bench` timing math, name mapping and rendering (T-050).
 * No IO — jar reads and the clock are exercised in tier 2 (`BenchServiceTest`)
 * and tier 4 (`BenchSmokeTest`).
 */
class BenchRunnerTest {

    @Test
    fun `median pins odd even single and empty`() {
        BenchRunner.median(emptyList()) shouldBe 0
        BenchRunner.median(listOf(7)) shouldBe 7
        BenchRunner.median(listOf(30, 10, 20)) shouldBe 20
        // Even counts take the lower middle (deterministic, documented).
        BenchRunner.median(listOf(40, 10, 30, 20)) shouldBe 20
    }

    @Test
    fun `median matches the sorted oracle over generated lists`(): Unit = runBlocking {
        checkAll(200, Arb.list(Arb.long(0L..10_000L), 1..9)) { samples ->
            val sorted = samples.sorted()
            BenchRunner.median(samples) shouldBe sorted[(sorted.size - 1) / 2]
        }
    }

    @Test
    fun `binaryName maps entries and drops package-info`() {
        BenchRunner.binaryName("com/foo/Bar.class") shouldBe "com.foo.Bar"
        BenchRunner.binaryName("com/foo/Bar\$Baz.class") shouldBe "com.foo.Bar\$Baz"
        BenchRunner.binaryName("module-info.class") shouldBe "module-info"
        BenchRunner.binaryName("com/foo/package-info.class") shouldBe null
        BenchRunner.binaryName("com/foo/Bar.java") shouldBe null
    }

    @Test
    fun `binaryName never throws on hostile strings`(): Unit = runBlocking {
        checkAll(200, Arb.string()) { text ->
            // Pins totality; the value only needs to be stable.
            BenchRunner.binaryName(text) shouldBe BenchRunner.binaryName(text)
        }
    }

    @Test
    fun `sampleRefs pins first middle last`() {
        BenchRunner.sampleRefs(emptyList()) shouldBe emptyList()
        BenchRunner.sampleRefs(listOf("a.A")) shouldBe listOf("a.A")
        BenchRunner.sampleRefs(listOf("a.A", "b.B", "c.C")) shouldBe listOf("a.A", "b.B", "c.C")
        BenchRunner.sampleRefs(listOf("a.A", "b.B", "c.C", "d.D")) shouldBe listOf("a.A", "c.C", "d.D")
    }

    @Test
    fun `cases pin the section-15 workload`() {
        BenchRunner.CASES.map { it.name } shouldBe listOf("load", "show", "members", "search", "hierarchy")
        BenchRunner.CASES.first { it.name == "load" }.targetMs shouldBe 8000
        BenchRunner.CASES.filter { it.name != "load" }.map { it.targetMs }.toSet() shouldBe setOf(250L)
    }

    @Test
    fun `text renders every row with its target and the tally`() {
        val report = BenchRunner.Report(
            label = "demo.jar",
            classCount = 10,
            iterations = 3,
            rows = listOf(
                BenchRunner.Row("load", 8000, 100, true),
                BenchRunner.Row("show", 250, 999, false),
            ),
        )
        val text = benchResult(report).renderText()

        text.lines().first() shouldBe "jdx bench demo.jar (10 classes, 3 iterations)"
        text shouldContain "load"
        text shouldContain "100 ms"
        text shouldContain "(target ≤ 8000 ms)  ok"
        text shouldContain "(target ≤ 250 ms)  OVER"
        text.lines().last() shouldBe "1/2 within target"
    }

    @Test
    fun `json envelope carries every row`() {
        val report = BenchRunner.Report(
            label = "demo.jar",
            classCount = 10,
            iterations = 3,
            rows = listOf(BenchRunner.Row("load", 8000, 100, true)),
        )
        val parsed = Json.parseToJsonElement(benchResult(report).toJson()).jsonObject

        parsed["command"]?.jsonPrimitive?.content shouldBe "bench"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val result = parsed["result"]?.jsonObject ?: error("bench json has no result")
        result["label"]?.jsonPrimitive?.content shouldBe "demo.jar"
        val rows = result["rows"]?.jsonArray ?: error("bench json has no result.rows")
        rows.size shouldBe 1
        rows[0].jsonObject["name"]?.jsonPrimitive?.content shouldBe "load"
        rows[0].jsonObject["medianMs"]?.jsonPrimitive?.longOrNull shouldBe 100
    }

    @Test
    fun `rendering is deterministic over generated reports`(): Unit = runBlocking {
        checkAll(100, Arb.string(0..24)) { label ->
            val report = BenchRunner.Report(
                label,
                classCount = 5,
                iterations = 1,
                rows = listOf(BenchRunner.Row("show", 250, 3, true)),
            )
            benchResult(report).renderText() shouldBe benchResult(report).renderText()
            benchResult(report).toJson() shouldBe benchResult(report).toJson()
        }
    }
}
