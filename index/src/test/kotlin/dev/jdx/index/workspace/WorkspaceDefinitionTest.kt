package dev.jdx.index.workspace

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests for workspace-name validation (T-015). Pure string work — tier 1.
 *
 * Names are file stems under `workspaces/`, so validation is a traversal guard first
 * and a UX rule second: anything that could escape the directory must never validate.
 */
class WorkspaceDefinitionTest {

    @Test
    fun `ordinary names are valid`() {
        validateWorkspaceName("mc") shouldBe null
        validateWorkspaceName("my-app") shouldBe null
        validateWorkspaceName("app.v2_final") shouldBe null
        validateWorkspaceName("A1") shouldBe null
    }

    @Test
    fun `empty and overlong names are rejected`() {
        (validateWorkspaceName("") != null) shouldBe true
        (validateWorkspaceName("a".repeat(65)) != null) shouldBe true
        validateWorkspaceName("a".repeat(64)) shouldBe null
    }

    @Test
    fun `traversal and separators are rejected`() {
        validateWorkspaceName("../evil") shouldBe "workspace name '../evil' must start with a letter or digit"
        validateWorkspaceName("a/b") shouldBe "workspace name 'a/b' may only contain letters, digits, '.', '_' and '-'"
        validateWorkspaceName("a b") shouldBe "workspace name 'a b' may only contain letters, digits, '.', '_' and '-'"
        validateWorkspaceName("~evil") shouldBe "workspace name '~evil' must start with a letter or digit"
        validateWorkspaceName("mc;rm") shouldBe "workspace name 'mc;rm' may only contain letters, digits, '.', '_' and '-'"
        validateWorkspaceName(".hidden") shouldBe "workspace name '.hidden' must start with a letter or digit"
        validateWorkspaceName("-x") shouldBe "workspace name '-x' must start with a letter or digit"
    }

    @Test
    fun `dot-dot is reserved`() {
        validateWorkspaceName("..") shouldBe "workspace name '..' must start with a letter or digit"
    }

    @Test
    fun `defaults keep the JDK`() {
        WorkspaceDefinition(name = "mc", jars = emptyList()).includeJdk shouldBe true
    }
}
