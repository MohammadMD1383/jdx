package dev.jdx.index.differential

import dev.jdx.core.diff.ApiDiffer
import dev.jdx.core.diff.ApiSnapshot
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.CompatRule
import dev.jdx.core.model.ClassInfo
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactRoot
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.util.TreeMap
import kotlin.io.path.isRegularFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The tier-3 `javap` differential for the **diff** (issue #23, TESTING.md §5.1).
 *
 * The existing corpus differential proves the *reader* agrees with `javap` about one
 * class in isolation. This suite asks the harder question over two real **versions of
 * the same artifact**, and pins two things that nothing else can:
 *
 * 1. **The snapshot sees exactly what `javap` sees.** Run with `visibility = ALL` and
 *    synthetic members included, the two sets are the same universe, so this is an
 *    equality, not a subset. It is what makes claim 2 mean anything.
 * 2. **The differ is neither blind nor noisy, per class.** A class whose `javap`
 *    member set, kind and supertypes are unchanged must produce **no** finding naming
 *    it; a class where any of those changed must produce **at least one**. The first
 *    direction is the valuable one: a spurious `MEMBER_REMOVED` is how an agent decides
 *    not to take an upgrade, and the per-rule examples in `core` cannot see it.
 *
 * Classes neither side can read honestly (corrupt, a major version from the future, a
 * name the model rejects) are **skips with a counted reason**, never failures: this
 * suite owns "agreement where both sides answer" and degrades exactly like the tool.
 *
 * Tagged `soak`, never `tier2` (docs/TESTING.md §2): it needs two versions of a real
 * artifact. Runs via `./gradlew soak [-Pcorpus=<dir>]`, which hands the directory in as
 * `-Djdx.corpusDir`. Skips — never fails — when no versioned pair or no `javap` is
 * available, so a contributor without the owner's cache still gets a green run.
 */
@Tag("soak")
class JavapDiffDifferentialTest {

    private companion object {
        /**
         * Bounded on purpose: every class costs two `javap` process spawns, so an
         * unbounded pair would take a quarter of an hour. Six pairs of forty classes
         * is ~480 spawns and still covers every shape a real upgrade produces.
         */
        const val MAX_PAIRS: Int = 6

        const val CLASSES_PER_PAIR: Int = 40

        val CLINIT: Javap.MemberKey = Javap.MemberKey("<clinit>", "()V")

        /** A version, optionally qualified — enough to spot a real one and no other. */
        val VERSION_SHAPE: Regex = Regex("""v?\d[\w.\-+]*""")

        /** `Javap.MemberKey` is not `Comparable`; this is the only order it needs. */
        val KEY_ORDER: Comparator<Javap.MemberKey> = compareBy({ it.name }, { it.descriptor })

        /**
         * The rules this oracle can actually contradict: the ones that change **a
         * member's identity or a type's existence**, which is all `javap -p -s` plus
         * `Javap.parseMembers` retains.
         *
         * Everything else — modifiers (`final`, `static`, `abstract`, visibility,
         * `synchronized`, `native`, `varargs`, `transient`), annotations, deprecation,
         * parameter names, generic signatures, the `throws` clause, constant values,
         * annotation defaults and every Kotlin-visible shape — is invisible to this
         * oracle by construction, so a finding there is neither confirmed nor refuted
         * here. Those rules are pinned by the per-rule examples in
         * `core/diff/ApiDifferTest` instead; this suite's job is the identity layer,
         * which is the one a missed member silently corrupts.
         */
        val KEY_VISIBLE: Set<CompatRule> = setOf(
            CompatRule.TYPE_REMOVED,
            CompatRule.TYPE_ADDED,
            CompatRule.TYPE_KIND_CHANGED,
            CompatRule.SUPERTYPE_REMOVED,
            CompatRule.SUPERTYPE_ADDED,
            CompatRule.MEMBER_REMOVED,
            CompatRule.MEMBER_ADDED,
            CompatRule.ENUM_CONSTANT_REMOVED,
            CompatRule.MEMBER_MOVED_TO_SUPERTYPE,
            CompatRule.PARAMETER_TYPE_CHANGED,
            CompatRule.FIELD_TYPE_CHANGED,
            CompatRule.KOTLIN_NAME_CHANGED,
            CompatRule.KOTLIN_SUSPEND_CHANGED,
        )
    }

    @Test
    fun `a real version pair agrees with javap, and the differ neither blinks nor fires`() {
        val javap = Javap.findJavap()
        assumeTrue(javap != null, "javap not found: skipping javap-agreement checks (TESTING.md §5.1)")
        val corpusDir = File(
            System.getProperty("jdx.corpusDir")
                ?: "${System.getProperty("user.home")}/.gradle/caches",
        )
        assumeTrue(
            corpusDir.isDirectory,
            "corpus dir ${corpusDir.absolutePath} absent: skipping diff soak (TESTING.md §8)",
        )
        val pairs = findVersionPairs(corpusDir).take(MAX_PAIRS)
        assumeTrue(
            pairs.isNotEmpty(),
            "no two-version jar pair under ${corpusDir.absolutePath}: skipping diff soak; " +
                "populate the Gradle cache with a library that has two versions",
        )

        val failures = mutableListOf<String>()
        val skipReasons = mutableMapOf<String, Int>()
        var compared = 0
        var changedClasses = 0
        for ((oldJar, newJar) in pairs) {
            val label = "${oldJar.name} -> ${newJar.name}"
            val oldClasses = readClasses(oldJar, skipReasons)
            val newClasses = readClasses(newJar, skipReasons)
            val overlap = oldClasses.keys.intersect(newClasses.keys).sorted().take(CLASSES_PER_PAIR)
            if (overlap.isEmpty()) {
                skipReasons["no shared class names"] = (skipReasons["no shared class names"] ?: 0) + 1
                continue
            }
            val before = snapshotOf(oldJar, overlap.mapNotNull { oldClasses[it] })
            val after = snapshotOf(newJar, overlap.mapNotNull { newClasses[it] })
            val diff = ApiDiffer.diff(before, after)
            val findingsByType = diff.findings.groupBy { it.type }

            for (name in overlap) {
                val oldJavap = javapMembers(javap!!, oldJar, name, skipReasons) ?: continue
                val newJavap = javapMembers(javap, newJar, name, skipReasons) ?: continue
                compared++

                // (1) The snapshot sees exactly what javap sees.
                for ((side, info, javapSet) in listOf(
                    Triple(oldJar.name, oldClasses[name]!!, oldJavap),
                    Triple(newJar.name, newClasses[name]!!, newJavap),
                )) {
                    val snapshotSet = memberKeysOf(info)
                    if (snapshotSet != javapSet) {
                        failures.add(
                            "SET MISMATCH $label $name ($side): " +
                                "javap-only=${(javapSet - snapshotSet).sortedWith(KEY_ORDER)} " +
                                "jdx-only=${(snapshotSet - javapSet).sortedWith(KEY_ORDER)}",
                        )
                    }
                }

                // (2) Neither blind nor noisy. `javap -p -s` does not print the class
                // header's kind or its supertypes, so a change there is invisible to
                // this oracle; a class whose *declaration* changed is compared
                // jdx-against-jdx for that reason, and the "at least one finding"
                // direction still applies to it.
                val shapeChanged = oldJavap != newJavap
                val declarationsChanged = declarationShapeChanged(oldClasses[name]!!, newClasses[name]!!)
                if (shapeChanged || declarationsChanged) {
                    changedClasses++
                    if (findingsByType[name].isNullOrEmpty()) {
                        failures.add(
                            "BLIND $label $name: javap sees " +
                                "${javapOnly(oldJavap, newJavap)} removed and " +
                                "${javapOnly(newJavap, oldJavap)} added, but no finding names it",
                        )
                    }
                }

                // (3) No false alarm, within what this oracle can see — but only for a
                // class `javap` says did not change. `javap -p -s` loses too much to
                // judge every rule: `Javap.parseMembers` keeps only the name, so
                // modifiers, annotations, parameter names and generic signatures are
                // gone, and this suite strips the return type off the descriptor it does
                // keep. A finding outside [KEY_VISIBLE] therefore cannot be contradicted
                // here; the per-rule examples in `core/diff/ApiDifferTest` pin those.
                if (!shapeChanged && !declarationsChanged) {
                    val loud = findingsByType[name].orEmpty().filter { it.rule in KEY_VISIBLE }
                    if (loud.isNotEmpty()) {
                        failures.add(
                            "FALSE POSITIVE $label $name: javap reports an identical member set " +
                                "(${oldJavap.size} on both sides), but the diff reported " +
                                "${loud.map { finding -> finding.rule.name }}",
                        )
                    }
                }
            }
        }

        println(
            "SOAK javap-diff-differential pairs=${pairs.size} compared=$compared " +
                "changed=$changedClasses failed=${failures.size}",
        )
        skipReasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
            println("SOAK skip [$count x] $reason")
        }
        // An empty corpus passing vacuously would be a test that asserts nothing, and a
        // corpus where nothing changed would not exercise claim (2) at all.
        (compared > 0) shouldBe true
        (changedClasses > 0) shouldBe true
        failures shouldBe emptyList<String>()
    }

    // -- the two sides ------------------------------------------------------------

    /**
     * Every class the reader could parse, keyed by binary name. A jar that will not
     * open contributes nothing and is counted as a skip, never a failure.
     */
    private fun readClasses(jar: File, skipReasons: MutableMap<String, Int>): Map<String, ClassInfo> {
        val classes = mutableMapOf<String, ClassInfo>()
        try {
            openRoot(jar) { root ->
                for (path in root.classEntryPaths()) {
                    when (val read = root.openClass(path).use { AsmClassReader.read(it, path) }) {
                        is ClassReadResult.Ok ->
                            classes[path.removeSuffix(".class").replace('/', '.')] = read.info
                        is ClassReadResult.Corrupt -> count(skipReasons, "corrupt class")
                        is ClassReadResult.UnsupportedVersion -> count(skipReasons, "unsupported class version")
                    }
                }
            }
        } catch (_: Exception) {
            count(skipReasons, "unreadable jar")
        }
        return classes
    }

    private inline fun openRoot(jar: File, block: (ArtifactRoot) -> Unit) {
        ArtifactLoader.openJar(jar.toPath()).use(block)
    }

    private fun snapshotOf(jar: File, classes: List<ClassInfo>): ApiSnapshot =
        ApiSnapshot.of(jar.name, classes, ApiSurface.ALL, includeSynthetic = true)

    /**
     * The member keys a snapshot holds, in the same shape `javap` reports them:
     * `(name, parameter descriptor)` for a method or constructor, `(name, type
     * descriptor)` for a field, and never `<clinit>`.
     *
     * Two spelling details matter, and both are the kind this oracle exists to catch:
     * a parameter list is **concatenated with no separator** (`(Ljava/lang/String;I)`, not
     * `(Ljava/lang/String;, I)`), and it carries **no return type** — the return type is
     * an attribute of a diff, not part of a member's identity. [javapMembers] strips the
     * return type off `javap`'s full descriptor so the two sets are comparable.
     */
    private fun memberKeysOf(info: ClassInfo): Set<Javap.MemberKey> {
        val keys = mutableSetOf<Javap.MemberKey>()
        for (field in info.fields) {
            keys.add(Javap.MemberKey(field.name, field.type.descriptor))
        }
        for (method in info.methods) {
            if (method.name == "<clinit>") continue
            keys.add(
                Javap.MemberKey(
                    name = method.name,
                    descriptor = method.descriptor.parameters.joinToString(
                        prefix = "(", postfix = ")", separator = "",
                    ) { parameter -> parameter.descriptor },
                ),
            )
        }
        return keys
    }

    /**
     * What `javap -p -s` reports for [name], reduced to the same key shape
     * [memberKeysOf] produces and with `<clinit>` dropped, or `null` when `javap`
     * cannot read the class at all.
     *
     * A method's `javap` descriptor is the *full* JVM descriptor, so the return type
     * has to come off; a field's descriptor is already the type descriptor and is kept
     * verbatim. `javap -p -s` tells the two apart by shape, not by guesswork.
     */
    private fun javapMembers(
        javap: String,
        jar: File,
        name: String,
        skipReasons: MutableMap<String, Int>,
    ): Set<Javap.MemberKey>? {
        val output = Javap.tryRun(javap, jar.absolutePath, name)
        if (output == null) {
            count(skipReasons, "javap cannot read the class")
            return null
        }
        val keys = mutableSetOf<Javap.MemberKey>()
        for (key in Javap.parseMembers(output)) {
            if (key == CLINIT) continue
            val parameterPart = parameterDescriptorOf(key.descriptor)
            keys.add(if (parameterPart == null) key else Javap.MemberKey(key.name, parameterPart))
        }
        return keys
    }

    /** The `(…)` prefix of a full method descriptor, or `null` when it is a field's type. */
    private fun parameterDescriptorOf(descriptor: String): String? {
        if (!descriptor.startsWith("(")) return null
        val close = descriptor.indexOf(')')
        return if (close < 0) null else descriptor.substring(0, close + 1)
    }

    /**
     * Whether the *declaration* header changed — kind or supertypes. `javap -p -s`
     * does not print either, so the oracle cannot see these; they are reported
     * separately so a class whose only change is a kind flip is not called quiet.
     */
    private fun declarationShapeChanged(old: ClassInfo, new: ClassInfo): Boolean {
        if (old.kind != new.kind) return true
        if (old.access.visibility != new.access.visibility) return true
        if (old.superclass?.descriptor != new.superclass?.descriptor) return true
        return old.interfaces.map { it.descriptor }.sorted() != new.interfaces.map { it.descriptor }.sorted()
    }

    private fun javapOnly(left: Set<Javap.MemberKey>, right: Set<Javap.MemberKey>): List<Javap.MemberKey> =
        (left - right).sortedWith(KEY_ORDER)

    private fun count(reasons: MutableMap<String, Int>, reason: String) {
        reasons[reason] = (reasons[reason] ?: 0) + 1
    }

    /**
     * Jars of the same artifact in two versions, found through the Gradle module-cache
     * layout `<name>-<version>.jar`: the artifact key is the file name minus its last
     * dash-separated segment. Sorted for determinism; sources and javadoc jars never
     * qualify (they hold no classes, and a `-sources` name would parse as a version).
     */
    private fun findVersionPairs(corpusDir: File): List<Pair<File, File>> {
        val byArtifact = mutableMapOf<String, TreeMap<String, File>>()
        Files.walk(corpusDir.toPath()).use { walk ->
            walk.filter { path ->
                val name = path.fileName?.toString() ?: return@filter false
                path.isRegularFile() && name.endsWith(".jar") &&
                    !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
            }.forEach { path ->
                val file = path.toFile()
                val stem = file.name.removeSuffix(".jar")
                val dash = stem.lastIndexOf('-')
                if (dash <= 0) return@forEach
                val base = stem.substring(0, dash)
                val version = stem.substring(dash + 1)
                // A version that is not version-shaped would pair two different
                // artifacts; `1.2.3`/`2.0`/`v4` is enough of a filter and never
                // rejects a real Maven version.
                if (!VERSION_SHAPE.matches(version)) return@forEach
                byArtifact.getOrPut(base) { TreeMap() }[version] = file
            }
        }
        return byArtifact.values
            .filter { it.size >= 2 }
            .map { versions -> versions.values.first() to versions.values.last() }
            .sortedBy { (old, _) -> old.absolutePath }
    }

}
