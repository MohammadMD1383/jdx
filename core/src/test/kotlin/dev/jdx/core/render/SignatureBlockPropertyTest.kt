package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for the signature renderer (T-024, TESTING.md §4 —
 * the standing bar demands a generating family, not only examples).
 */
class SignatureBlockPropertyTest {

    // JSON escapes `"`/`\`/control chars, so generated text is restricted to
    // plain printable text — otherwise the raw entry is not a JSON substring.
    private fun arbToken(): Arb<String> =
        Arb.string(1..24).map { s -> s.filter { c -> c >= ' ' && c != '"' && c != '\\' } }
            .map { s -> if (s.isEmpty()) "m" else s }

    private fun arbEntry(): Arb<SignatureEntry> =
        Arb.string(1..12).map { s -> s.filter { it.isLetterOrDigit() } }
            .map { name ->
                val clean = if (name.isEmpty()) "m" else name
                SignatureEntry(
                    canonicalRef = "com.example.A#$clean()",
                    kind = MemberKind.METHOD,
                    signature = "public void $clean()",
                    declaringType = "com.example.A",
                )
            }

    private fun blockOf(entries: List<SignatureEntry>, max: Int): SignatureBlock =
        buildSignatureBlock(
            query = "com.example.A#m",
            declaringType = "com.example.A",
            entries = entries,
            provenance = listOf(Provenance(artifact = "test.jar", origin = Origin.BYTECODE)),
            maxSignatures = max,
        )

    @Test
    fun `rendering is byte-identical across runs`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbEntry(), 0..20), Arb.int(0..25)) { entries, max ->
            val first = blockOf(entries, max)
            val second = blockOf(entries, max)
            first.renderText() shouldBe second.renderText()
            first.toJson(command = "signature") shouldBe second.toJson(command = "signature")
        }
    }

    @Test
    fun `every shown text signature and ref is covered by the json`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbEntry(), 0..20), Arb.int(0..25)) { entries, max ->
            val block = blockOf(entries, max)
            val json = block.toJson(command = "signature")
            for (entry in block.signatures) {
                json shouldContain entry.signature
                json shouldContain entry.canonicalRef
            }
            json shouldContain block.query
            json shouldContain block.declaringType
        }
    }

    @Test
    fun `truncation law holds shown below total with hint exactly when cut`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbEntry(), 0..20), Arb.int(0..25)) { entries, max ->
            val full = blockOf(entries, Int.MAX_VALUE)
            val cut = blockOf(entries, max)
            if (cut.truncation == null) {
                cut.signatures shouldBe full.signatures
            } else {
                val truncation = cut.truncation!!
                (truncation.shown <= truncation.total) shouldBe true
                truncation.hint shouldContain "--limit"
                truncation.total shouldBe full.signatures.size
                truncation.shown shouldBe cut.signatures.size
                full.signatures.take(cut.signatures.size) shouldBe cut.signatures
            }
        }
    }

    @Test
    fun `plain text never contains escapes`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbToken(), 0..20), Arb.int(0..25)) { tokens, max ->
            val entries = tokens.mapIndexed { index, token ->
                SignatureEntry(
                    canonicalRef = "com.example.A#m$index()",
                    kind = MemberKind.METHOD,
                    signature = "public void m$index($token arg0)",
                    declaringType = "com.example.A",
                )
            }
            val text = blockOf(entries, max).renderText()
            (text.contains("\u001B")) shouldBe false
        }
    }
}
