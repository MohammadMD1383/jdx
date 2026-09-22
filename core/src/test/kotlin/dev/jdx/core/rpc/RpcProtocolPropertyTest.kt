package dev.jdx.core.rpc

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The generating family behind the v1 wire contract (T-040, TESTING.md §4): round-trip,
 * determinism, framing safety, and — the one that matters for a daemon reading whatever a
 * socket hands it — `decode` never throws, on any input at all.
 *
 * The character pool is deliberately hostile: quotes, backslashes, every escape JSON has a
 * short form for, raw control characters, non-ASCII, the JSON structural punctuation, and
 * U+2028/U+2029 (line separators to a JavaScript reader, ordinary characters to JSON).
 */
class RpcProtocolPropertyTest {

    private val hostileChars: List<Char> = listOf(
        '"', '\\', '/', '\n', '\r', '\t', '\b', '\u000C', '\u0000', '\u0001', '\u001F',
        ' ', 'a', 'Z', '0', '9', '{', '}', '[', ']', ':', ',', '-', 'e', 'λ', '中',
        ' ', ' ',
    )

    private fun arbHostileString(range: IntRange): Arb<String> =
        Arb.list(Arb.of(hostileChars), range).map { chars -> chars.joinToString("") }

    private fun arbParams(): Arb<Map<String, String>> =
        Arb.list(Arb.pair(arbHostileString(0..8), arbHostileString(0..8)), 0..6).map { it.toMap() }

    private fun arbRequest(): Arb<RpcRequest> =
        Arb.bind(Arb.of(RpcCommand.entries), arbHostileString(0..24), arbParams()) { command, query, params ->
            RpcRequest(command, query, params)
        }

    @Test
    fun `encode then decode is the identity`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbRequest()) { request ->
            assert(RpcRequest.decode(request.encode()) == request) {
                "round-trip lost information for $request"
            }
        }
    }

    @Test
    fun `a framed line round-trips, newline included`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbRequest()) { request ->
            assert(RpcRequest.decode(request.frame()) == request) { "framed round-trip failed for $request" }
        }
    }

    @Test
    fun `encoding is deterministic and independent of params insertion order`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbRequest(), Arb.int()) { request, seed ->
            // Same keys and values, different iteration order — sorting must erase the difference.
            val reordered = request.copy(
                params = request.params.entries
                    .shuffled(Random(seed))
                    .associate { entry -> entry.key to entry.value },
            )
            assert(request.encode() == request.encode()) { "encode not stable across calls" }
            assert(request.encode() == reordered.encode()) { "encode depends on params order: $request" }
        }
    }

    @Test
    fun `an encoded request is always exactly one line`(): Unit = runBlocking {
        // The framing invariant the daemon (T-041) and `batch` (T-045) both stand on.
        checkAll(JDX_PROPERTY_ITERATIONS, arbRequest()) { request ->
            assert(!request.encode().contains('\n')) { "raw newline in payload for $request" }
            assert(!request.encode().contains('\r')) { "raw carriage return in payload for $request" }
            assert(request.frame().count { it == '\n' } == 1) { "frame is not one line for $request" }
        }
    }

    @Test
    fun `decode never throws, whatever the socket hands it`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbHostileString(0..40)) { text ->
            RpcRequest.decode(text) // must return null or a request — never raise
        }
    }

    @Test
    fun `decode never throws on mutations of a valid encoding`(): Unit = runBlocking {
        // Pure random strings rarely reach the deep parser branches; damaged real payloads do.
        checkAll(JDX_PROPERTY_ITERATIONS, arbRequest(), Arb.int(), Arb.int(0..3)) { request, seed, kind ->
            val encoded = request.encode()
            val random = Random(seed)
            val at = random.nextInt(encoded.length)
            val mutated = when (kind) {
                0 -> encoded.substring(0, at)
                1 -> encoded.removeRange(at, at + 1)
                2 -> encoded.substring(0, at) + hostileChars.random(random) + encoded.substring(at)
                else -> encoded.substring(0, at) + hostileChars.random(random) + encoded.substring(at + 1)
            }
            RpcRequest.decode(mutated) // must not raise
        }
    }

    @Test
    fun `every proper prefix of a valid encoding is refused`(): Unit = runBlocking {
        // A truncated line is an incomplete object; accepting one would let a half-flushed
        // socket write execute a command the client never finished asking for.
        checkAll(JDX_PROPERTY_ITERATIONS, arbRequest(), Arb.int()) { request, seed ->
            val encoded = request.encode()
            val cut = Random(seed).nextInt(encoded.length)
            assert(RpcRequest.decode(encoded.substring(0, cut)) == null) {
                "accepted a truncated request: ${encoded.substring(0, cut)}"
            }
        }
    }
}
