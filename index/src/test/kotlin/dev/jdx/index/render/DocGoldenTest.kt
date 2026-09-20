package dev.jdx.index.render

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.render.DocSubject
import dev.jdx.core.render.buildDocBlock
import dev.jdx.core.render.renderJavadoc
import dev.jdx.index.service.buildDocCaseJars
import dev.jdx.sources.JavaDocResult
import dev.jdx.sources.SourceDoc
import dev.jdx.sources.findMemberDocs
import dev.jdx.sources.findTypeDoc
import dev.jdx.sources.openSourceRoot
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

/**
 * Golden tests for the doc renderer over real fixture sources plus the
 * crafted inheritance corpus (T-025).
 *
 * Each pinned doc renders once as text and once as JSON; both outputs are
 * pinned to committed files under `src/test/resources/golden/doc/`.
 * Provenance uses fixed labels (`fixture-sources.jar`, `case-sources.jar` —
 * never temp paths), so goldens are byte-identical on every machine.
 * Service-level resolution (ambiguity, inheritance, exit codes) is covered
 * in `DocServiceTest`; here the assertions are pure rendering — including an
 * inherited and a truncated variant.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted (TESTING.md §14). Comparison, update mode and the orphan check come
 * from the shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class DocGoldenTest {

    @Test
    fun `text and json goldens cover pinned docs`(@TempDir tempDir: Path) {
        val contents = mutableMapOf<String, String>()

        val fixtureRoot = openSourceRoot(FixtureJars.sourcesJar().toPath())
        try {
            val found = findTypeDoc(fixtureRoot, "dev.jdx.fixtures.Generics")
            val doc = (found as? JavaDocResult.Found)?.docs?.singleOrNull()
                ?: fail("dev.jdx.fixtures.Generics: expected Found, got $found")
            contents += docGoldens(
                key = "dev.jdx.fixtures.Generics",
                canonicalRef = "dev.jdx.fixtures.Generics",
                declaringType = "dev.jdx.fixtures.Generics",
                subject = DocSubject.TYPE,
                doc = doc,
                rendered = renderJavadoc(doc.rawComment),
                artifact = "fixture-sources.jar",
                inheritedFrom = null,
            )
        } finally {
            runCatching { fixtureRoot.close() }
        }

        val jars = buildDocCaseJars(tempDir)
        val caseRoot = openSourceRoot(jars.sources)
        try {
            val ref = MemberSymbolRef(
                declaringType = typeNameFromBinaryName("doc.Base") as dev.jdx.core.model.TypeName.ClassType,
                name = "greet",
                parameterTypes = listOf(typeNameFromBinaryName("java.lang.String")),
            )
            val found = findMemberDocs(caseRoot, ref)
            val doc = (found as? JavaDocResult.Found)?.docs?.singleOrNull()
                ?: fail("doc.Base#greet: expected Found, got $found")
            val rendered = renderJavadoc(doc.rawComment)
            contents += docGoldens(
                key = "doc.Base#greet",
                canonicalRef = "doc.Base#greet(java.lang.String)",
                declaringType = "doc.Base",
                subject = DocSubject.METHOD,
                doc = doc,
                rendered = rendered,
                artifact = "case-sources.jar",
                inheritedFrom = null,
            )
            // The inherited rendering the service builds for an undocumented
            // override: the subclass ref over the supertype's comment.
            contents += docGoldens(
                key = "doc.Child#greet-inherited",
                canonicalRef = "doc.Child#greet(java.lang.String)",
                declaringType = "doc.Child",
                subject = DocSubject.METHOD,
                doc = doc,
                rendered = rendered,
                artifact = "case-sources.jar",
                inheritedFrom = "doc.Base",
            )
            // Truncation: the same doc through --max-lines 2.
            contents += docGoldens(
                key = "doc.Base#greet-truncated",
                canonicalRef = "doc.Base#greet(java.lang.String)",
                declaringType = "doc.Base",
                subject = DocSubject.METHOD,
                doc = doc,
                rendered = rendered,
                artifact = "case-sources.jar",
                inheritedFrom = null,
                maxLines = 2,
            )
        } finally {
            runCatching { caseRoot.close() }
        }

        GoldenFiles.verifyAll(File("src/test/resources/golden/doc"), contents)
    }

    private fun docGoldens(
        key: String,
        canonicalRef: String,
        declaringType: String,
        subject: DocSubject,
        doc: SourceDoc,
        rendered: List<String>,
        artifact: String,
        inheritedFrom: String?,
        maxLines: Int = Int.MAX_VALUE,
    ): Map<String, String> {
        val provenance = listOf(
            Provenance(
                artifact = artifact,
                origin = Origin.SOURCES,
                file = doc.file,
                lineRange = doc.startLine..doc.endLine,
            ),
        )
        val block = buildDocBlock(
            canonicalRef = canonicalRef,
            declaringType = declaringType,
            subject = subject,
            file = doc.file,
            startLine = doc.startLine,
            endLine = doc.endLine,
            rendered = rendered,
            provenance = provenance,
            inheritedFrom = inheritedFrom,
            maxLines = maxLines,
        )
        return mapOf("$key.txt" to block.renderText(), "$key.json" to block.toJson(command = "doc"))
    }
}
