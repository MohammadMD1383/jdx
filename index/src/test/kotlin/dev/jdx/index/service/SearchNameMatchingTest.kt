package dev.jdx.index.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure name-matching behind `search`/`resolve`/`ls`/`tree` (T-017): how a
 * pattern meets a binary name, member name, package or artifact label. No
 * disk, no jars — hand-checked rules, so this stays in tier 1. Adversarial
 * strings live in core's `SymbolSearchPropertyTest`.
 */
class SearchNameMatchingTest {

    // -- type names -----------------------------------------------------------

    @Test
    fun `dotted glob matches binary and dotted forms`() {
        JdxService.matchesTypeName("com.google.*", "com.google.gson.Gson", false) shouldBe true
        JdxService.matchesTypeName("com.google.*", "com.example.Gson", false) shouldBe false
    }

    @Test
    fun `bare glob matches the simple name`() {
        JdxService.matchesTypeName("*Gson*", "com.google.gson.Gson", false) shouldBe true
        JdxService.matchesTypeName("Gson", "com.google.gson.Gson", false) shouldBe true
        JdxService.matchesTypeName("*Gson*", "com.google.gson.JsonParser", false) shouldBe false
    }

    @Test
    fun `nested classes match through the dollar`() {
        JdxService.matchesTypeName("java.util.Map.Entry", "java.util.Map\$Entry", false) shouldBe true
        JdxService.matchesTypeName("*Entry", "java.util.Map\$Entry", false) shouldBe true
    }

    @Test
    fun `dotted words name a location, not a substring family`() {
        JdxService.matchesTypeName(
            "dev.jdx.fixtures.Generics",
            "dev.jdx.fixtures.Generics",
            false,
        ) shouldBe true
        // The TESTING.md metamorphic law: an exact FQN finds exactly that type.
        JdxService.matchesTypeName(
            "dev.jdx.fixtures.Generics",
            "dev.jdx.fixtures.Generics\$Recursive",
            false,
        ) shouldBe false
        JdxService.matchesTypeName(
            "fixtures.Generics",
            "dev.jdx.fixtures.Generics",
            false,
        ) shouldBe true
        // Bare words keep substring semantics (PROPOSAL.md Appendix A).
        JdxService.matchesTypeName("JsonAdapter", "com.google.gson.JsonAdapter", false) shouldBe true
    }

    @Test
    fun `plain words match substrings and humps`() {
        JdxService.matchesTypeName("gson", "com.google.gson.Gson", false) shouldBe true
        JdxService.matchesTypeName("HMap", "com.example.HashMap", false) shouldBe true
        JdxService.matchesTypeName("HMap", "com.example.HashSet", false) shouldBe false
    }

    @Test
    fun `regex tries binary dotted and simple forms`() {
        JdxService.matchesTypeName(".*Gson$", "com.google.gson.Gson", true) shouldBe true
        JdxService.matchesTypeName("^Gson$", "com.google.gson.Gson", true) shouldBe true
        JdxService.matchesTypeName("^Gson$", "com.google.gson.GsonFactory", true) shouldBe false
    }

    // -- member names ---------------------------------------------------------

    @Test
    fun `member matching is glob regex or word`() {
        JdxService.matchesMemberName("to*son", "toJson", false) shouldBe true
        JdxService.matchesMemberName(".*Json", "toJson", true) shouldBe true
        JdxService.matchesMemberName("json", "toJson", false) shouldBe true
        JdxService.matchesMemberName("tJ", "toJson", false) shouldBe true
        JdxService.matchesMemberName("tJ", "toString", false) shouldBe false
    }

    // -- packages and artifacts -----------------------------------------------

    @Test
    fun `package globs match dotted names`() {
        JdxService.matchesPackageName("com.google.*", "com.google.gson", false) shouldBe true
        JdxService.matchesPackageName("com.google.*", "com.example", false) shouldBe false
        JdxService.matchesPackageName("gson", "com.google.gson", false) shouldBe true
    }

    @Test
    fun `artifact filters are globs when wild substrings otherwise`() {
        JdxService.matchesArtifactFilter("gson-*.jar", "gson-2.14.0.jar") shouldBe true
        JdxService.matchesArtifactFilter("gson", "gson-2.14.0.jar") shouldBe true
        JdxService.matchesArtifactFilter("GSON", "gson-2.14.0.jar") shouldBe true
        JdxService.matchesArtifactFilter("gson", "guava-33.0.jar") shouldBe false
        JdxService.matchesArtifactFilter(null, "anything.jar") shouldBe true
        JdxService.matchesPackageFilter("dev.jdx.*", "dev.jdx.fixtures") shouldBe true
        JdxService.matchesPackageFilter(null, "dev.jdx.fixtures") shouldBe true
    }
}
