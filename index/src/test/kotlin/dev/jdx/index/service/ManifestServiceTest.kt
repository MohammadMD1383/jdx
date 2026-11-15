package dev.jdx.index.service

import dev.jdx.index.service.JdxService.ManifestOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Service-level coverage for `jdx manifest` (issue #25): hits, miss, regex,
 * limit, `--in`, and ignore of versioned META-INF/MANIFEST.MF copies.
 */
class ManifestServiceTest {

    @TempDir
    lateinit var temp: Path

    private fun roots(vararg jars: Path): RootsSpec =
        RootsSpec(jarSpecs = jars.map { it.toString() }, includeJdk = false)

    @Test
    fun `lists attributes matching a name pattern`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val outcome = JdxService.manifest("Main-Class", roots(jar))
        val listing = (outcome as ServiceOutcome.ManifestList).listing
        listing.hits.map { "${it.name}=${it.value}" } shouldContainExactly listOf(
            "Main-Class=com.example.Main",
        )
        listing.renderText() shouldContain "manifests matching 'Main-Class'"
        listing.renderText() shouldContain "Main-Class: com.example.Main"
        listing.toJson("manifest") shouldContain "\"name\":\"Main-Class\""
        listing.renderText() shouldNotContain "Versioned-Only"
    }

    @Test
    fun `empty pattern lists every main attribute`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val outcome = JdxService.manifest(null, roots(jar))
        val listing = (outcome as ServiceOutcome.ManifestList).listing
        listing.hits.map { it.name }.sorted() shouldContainExactly listOf(
            "Implementation-Title",
            "Implementation-Version",
            "Main-Class",
            "Manifest-Version",
            "Multi-Release",
        )
        listing.renderText() shouldContain "manifests"
        listing.renderText() shouldNotContain "matching"
    }

    @Test
    fun `value text also matches the pattern`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val outcome = JdxService.manifest("CaseLib", roots(jar))
        val listing = (outcome as ServiceOutcome.ManifestList).listing
        listing.hits.map { it.name } shouldContainExactly listOf("Implementation-Title")
    }

    @Test
    fun `miss exits 1 with a copy-pasteable detail`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val outcome = JdxService.manifest("No-Such-Attr", roots(jar))
        outcome.shouldBeInstanceOf<ServiceOutcome.Failure>()
        outcome.exitCode shouldBe 1
        outcome.renderText() shouldContain "no manifest attributes matching"
    }

    @Test
    fun `regex and ignore-case filter names`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val regex = JdxService.manifest(
            "Main-Clas.",
            roots(jar),
            ManifestOptions(regex = true),
        ) as ServiceOutcome.ManifestList
        regex.listing.hits.map { it.name } shouldContainExactly listOf("Main-Class")

        val folded = JdxService.manifest(
            "MAIN-CLASS",
            roots(jar),
            ManifestOptions(ignoreCase = true),
        ) as ServiceOutcome.ManifestList
        folded.listing.hits.size shouldBe 1

        val sensitive = JdxService.manifest("MAIN-CLASS", roots(jar))
        sensitive.shouldBeInstanceOf<ServiceOutcome.Failure>()
    }

    @Test
    fun `limit truncates attribute rows`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val limited = JdxService.manifest(
            null,
            roots(jar),
            ManifestOptions(limit = 2),
        ) as ServiceOutcome.ManifestList
        limited.listing.hits.size shouldBe 2
        limited.listing.renderText() shouldContain "2 of 5 attributes shown"
    }

    @Test
    fun `in and exclude filter by artifact label`() {
        val a = ManifestCaseJars.buildManifestCaseJar(temp)
        val b = ManifestCaseJars.buildOtherManifestJar(temp)
        val onlyOther = JdxService.manifest(
            null,
            roots(a, b),
            ManifestOptions(inArtifact = "other-manifest.jar"),
        ) as ServiceOutcome.ManifestList
        onlyOther.listing.hits.map { it.artifact }.distinct() shouldContainExactly listOf(
            "other-manifest.jar",
        )

        val withoutOther = JdxService.manifest(
            null,
            roots(a, b),
            ManifestOptions(exclude = "other-manifest.jar"),
        ) as ServiceOutcome.ManifestList
        withoutOther.listing.hits.any { it.artifact == "other-manifest.jar" } shouldBe false
    }

    @Test
    fun `jar without a manifest is a miss for empty pattern alone`() {
        val jar = ManifestCaseJars.buildNoManifestJar(temp)
        val outcome = JdxService.manifest(null, roots(jar))
        outcome.shouldBeInstanceOf<ServiceOutcome.Failure>()
        outcome.exitCode shouldBe 1
    }

    @Test
    fun `directory roots expose META-INF MANIFEST too`() {
        val dir = temp.resolve("classes")
        val meta = dir.resolve("META-INF")
        java.nio.file.Files.createDirectories(meta)
        java.nio.file.Files.writeString(
            meta.resolve("MANIFEST.MF"),
            "Manifest-Version: 1.0\r\nMain-Class: com.dir.Main\r\n\r\n",
        )
        val outcome = JdxService.manifest("Main-Class", roots(dir))
        val listing = (outcome as ServiceOutcome.ManifestList).listing
        listing.hits.map { it.value } shouldContainExactly listOf("com.dir.Main")
    }

    @Test
    fun `entries flag lists per-entry attributes and matches entry path`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val all = JdxService.manifest(
            null,
            roots(jar),
            ManifestOptions(entries = true),
        ) as ServiceOutcome.ManifestList
        all.listing.hits.map { "${it.entry}|${it.name}=${it.value}" } shouldContainExactly listOf(
            "META-INF/services/com.example.Spi|SHA-256-Digest=def456digest",
            "com/example/Main.class|Content-Type=application/java-vm",
            "com/example/Main.class|SHA-256-Digest=abc123digest",
        )
        all.listing.renderText() shouldContain "  com/example/Main.class"
        all.listing.renderText() shouldContain "    SHA-256-Digest: abc123digest"
        all.listing.renderText() shouldNotContain "Main-Class"
        all.listing.renderText() shouldNotContain "Versioned-Only"
        all.listing.toJson("manifest") shouldContain "\"entry\":\"com/example/Main.class\""

        val byPath = JdxService.manifest(
            "Main.class",
            roots(jar),
            ManifestOptions(entries = true),
        ) as ServiceOutcome.ManifestList
        byPath.listing.hits.map { it.name } shouldContainExactly listOf(
            "Content-Type",
            "SHA-256-Digest",
        )

        val byDigest = JdxService.manifest(
            "def456",
            roots(jar),
            ManifestOptions(entries = true),
        ) as ServiceOutcome.ManifestList
        byDigest.listing.hits.single().entry shouldBe "META-INF/services/com.example.Spi"
    }

    @Test
    fun `entries miss exits 1 and default mode ignores entry sections`() {
        val jar = ManifestCaseJars.buildManifestCaseJar(temp)
        val miss = JdxService.manifest(
            "No-Such-Entry",
            roots(jar),
            ManifestOptions(entries = true),
        )
        miss.shouldBeInstanceOf<ServiceOutcome.Failure>()
        miss.exitCode shouldBe 1

        val main = JdxService.manifest("SHA-256-Digest", roots(jar))
        main.shouldBeInstanceOf<ServiceOutcome.Failure>()
    }
}
