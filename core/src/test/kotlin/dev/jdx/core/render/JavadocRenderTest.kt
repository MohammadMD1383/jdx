package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Examples for the hand-rolled javadoc renderer (T-025, PROPOSAL.md §7.1).
 *
 * `core` stays dependency-free, so this never touches JavaParser: it renders
 * the raw `JavadocComment` content the `sources` seam hands over. HTML is
 * stripped, `{@code}`/`{@link}` and friends are unwrapped, block tags survive
 * as a tidy block, and `--raw` callers bypass this entirely.
 */
class JavadocRenderTest {

    @Test
    fun `plain description renders as its own lines`() {
        renderJavadoc("Does the thing.\nSecond line.") shouldBe listOf("Does the thing.", "Second line.")
    }

    @Test
    fun `leading stars are stripped`() {
        renderJavadoc("\n * Does the thing.\n * Second line.\n ") shouldBe
            listOf("Does the thing.", "Second line.")
    }

    @Test
    fun `html tags are stripped and entities unescaped`() {
        renderJavadoc("Uses <code>X</code> and <p>paragraphs</p> &lt;ok&gt; &amp; done.") shouldBe
            listOf("Uses X and paragraphs <ok> & done.")
    }

    @Test
    fun `code and link inline tags unwrap to their text`() {
        renderJavadoc("See {@code Foo#bar} and {@link com.example.Foo#bar label} end.") shouldBe
            listOf("See Foo#bar and label end.")
    }

    @Test
    fun `link without a label keeps the reference`() {
        renderJavadoc("See {@link com.example.Foo} end.") shouldBe listOf("See com.example.Foo end.")
    }

    @Test
    fun `literal and value tags unwrap`() {
        renderJavadoc("{@literal <T>} and {@value #MAX}") shouldBe listOf("<T> and #MAX")
    }

    @Test
    fun `block tags survive as a tidy block after a blank line`() {
        renderJavadoc("Does the thing.\n@param value the input\n@return the output\n@throws java.io.IOException on failure") shouldBe
            listOf(
                "Does the thing.",
                "",
                "@param value the input",
                "@return the output",
                "@throws java.io.IOException on failure",
            )
    }

    @Test
    fun `since and deprecated tags are kept`() {
        renderJavadoc("Old.\n@deprecated use {@code other} instead\n@since 1.2") shouldBe
            listOf("Old.", "", "@deprecated use other instead", "@since 1.2")
    }

    @Test
    fun `leading blank lines and trailing whitespace are dropped but inner blanks kept`() {
        renderJavadoc("\n\nFirst.\n\nSecond.\n\n") shouldBe listOf("First.", "", "Second.")
    }

    @Test
    fun `blank input renders to no lines`() {
        renderJavadoc("   \n *  \n") shouldBe emptyList()
    }

    @Test
    fun `inherited doc tag is replaced when a replacement is given`() {
        renderJavadoc("{@inheritDoc} Extra.", inheritDocReplacement = "Base docs.") shouldBe
            listOf("Base docs. Extra.")
    }

    @Test
    fun `inherited doc tag is dropped without a replacement`() {
        renderJavadoc("Prefix {@inheritDoc} suffix.") shouldBe listOf("Prefix  suffix.")
    }

    @Test
    fun `unclosed inline tag is left alone, never throws`() {
        renderJavadoc("See {@code unclosed and {@link also unclosed end.") shouldBe
            listOf("See {@code unclosed and {@link also unclosed end.")
    }
}
