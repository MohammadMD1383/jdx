import info.solidsoft.gradle.pitest.PitestPluginExtension
import info.solidsoft.gradle.pitest.PitestTask
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestReport
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared (not applied) so gated modules can `alias(libs.plugins.pitest)` in their own
    // build files (T-060). Applied per-module, never here: cli/mcp/server are thin adapters
    // with no coverage gate (D-021), and testfixtures is fixtures, not product code.
    alias(libs.plugins.pitest) apply false
}

// Shared configuration for every module.
//
// Kept as a `subprojects` block rather than a buildSrc convention plugin while the build is
// small: one file, no extra compilation step, and a newcomer can read the whole thing in a
// minute. Promote to buildSrc when this grows past ~100 lines or when modules need genuinely
// different treatment.
//
// NOTE: the generated `libs` accessor is only in scope in a project's *own* build script, not
// inside a `subprojects { }` closure. So catalog values are captured here, at root scope, and
// the captured providers are used below. Module build files use `libs.*` directly and are
// unaffected.
val javaToolchainVersion = libs.versions.java.get().toInt()
val kotlinStdlib = libs.kotlin.stdlib
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.platform.launcher
val kotestAssertions = libs.kotest.assertions
// T-060: every coverage/mutation tool version lives in the catalog (T-001 rule); captured
// here at root scope for the shared gate configuration below. Module `pitest {}` blocks
// read the same catalog entries through `libs.versions.*` directly.
val jacocoToolVersion = libs.versions.jacoco.get()

// --- Test tiers (T-053, docs/TESTING.md §2) ---
//
// Tags are the mechanism: `@Tag("tier2")` marks slow suites (jar reads, JVM spawns, golden
// files, fault injection, parity) that run in `check` but not in the fast `test` loop;
// `@Tag("soak")` and `@Tag("bench")` mark tiers 3 and 4. The per-module tasks below give
// every tag a runner; the root tasks at the bottom of this file give every tier a command.
val unitTestBudgetSeconds =
    (findProperty("unit.test.budget") as String?)?.toDoubleOrNull()
        ?: (findProperty("tier1.budget") as String?)?.toDoubleOrNull()
        ?: 2.5
val integrationTestBudgetSeconds =
    (findProperty("integration.test.budget") as String?)?.toDoubleOrNull()
        ?: (findProperty("tier2.budget") as String?)?.toDoubleOrNull()
        ?: 120.0
val corpusDir = (findProperty("corpus") as String?) ?: "${System.getProperty("user.home")}/.gradle/caches"
// The two fixture-corpus system properties, named once so the PIT wiring and its gate
// cannot drift apart. These strings must equal `FixtureJars.FIXTURES_DIR_PROPERTY` and
// `DiffFixtureJars.DIR_PROPERTY` in `testfixtures/src/testFixtures/.../fixtures/` — a
// mismatch is not silent, it turns into a red PIT pre-scan (which is how #66 was found).
val fixtureJarsDirProperty = "jdx.fixturesDir"
val diffFixtureJarsDirProperty = "jdx.diffFixturesDir"

// CI mode (#57): `-Pci` (bare or `=true`) or `CI`/`GITHUB_ACTIONS` env. Relaxes
// wall-clock test gates (per-test budget report-only, PIT timeouts lifted) so slow
// runners never produce false-positive reds. Product timeouts (Vineflower/javap,
// Maven) are untouched. NOTE: a bare `-Pci` does not yield the string "true" from
// `findProperty`, so presence (anything but explicit `=false`) means CI here.
val isCi: Boolean =
    (findProperty("ci")?.toString()?.let { it != "false" } ?: false) ||
        System.getenv("CI") != null ||
        System.getenv("GITHUB_ACTIONS") == "true"

// Tier 4 guard: Tier 4 tests (benchmarks and mutation testing) take ~30 minutes
// and consume heavy CPU/resources. Explicit permission is required via
// `-PconfirmTier4=true` or `-PallowHeavy=true`.
val isTier4Confirmed: Boolean =
    findProperty("confirmTier4")?.toString().equals("true", ignoreCase = true) ||
        findProperty("allowHeavy")?.toString().equals("true", ignoreCase = true)
val tier4PermissionMessage =
    "Tier 4 tests are resource-intensive and long-running (~30 minutes, heavy CPU). " +
        "Tier 4 tests must not run without explicit user permission. " +
        "To run them intentionally, pass -PconfirmTier4=true (or -PallowHeavy=true)."

// Enforces per-test budgets: unit tests <= 2.5s (-Punit.test.budget), integration tests <= 120.0s
// (-Pintegration.test.budget). Wired as a finalizer of every module's `test` and `integrationTest` tasks.
// Times come from JUnit XML results. In CI mode (-Pci), violations are report-only.
tasks.register("verifyPerTestBudget") {
    group = "verification"
    description = "Enforces per-test budgets across unit and integration tests (docs/TESTING.md §2)."
    val unitResultsDirs: List<java.io.File> =
        subprojects.map { it.layout.buildDirectory.dir("test-results/test").get().asFile }
    val integrationResultsDirs: List<java.io.File> =
        subprojects.flatMap { subproject ->
            listOf(
                subproject.layout.buildDirectory.dir("test-results/integrationTest").get().asFile,
                subproject.layout.buildDirectory.dir("test-results/tier2Test").get().asFile,
            )
        }
    val unitBudget: Double = unitTestBudgetSeconds
    val integrationBudget: Double = integrationTestBudgetSeconds
    val ciMode: Boolean = isCi
    doLast {
        var totalTime = 0.0
        var testCount = 0
        var failureCount = 0
        val slowest = mutableListOf<ParsedTestCase>()
        val violations = mutableListOf<ParsedTestCase>()

        fun parseResults(dirs: List<java.io.File>, tier: String, budget: Double) {
            dirs.forEach { resultsDir ->
                if (!resultsDir.isDirectory) return@forEach
                resultsDir.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { xml ->
                    try {
                        val suite = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(xml).documentElement
                        totalTime += suite.getAttribute("time").toDoubleOrNull() ?: 0.0
                        testCount += suite.getAttribute("tests").toIntOrNull() ?: 0
                        failureCount += (suite.getAttribute("failures").toIntOrNull() ?: 0) +
                            (suite.getAttribute("errors").toIntOrNull() ?: 0)
                        val cases = suite.getElementsByTagName("testcase")
                        for (i in 0 until cases.length) {
                            val case = cases.item(i) as org.w3c.dom.Element
                            val time = case.getAttribute("time").toDoubleOrNull() ?: 0.0
                            val className = case.getAttribute("classname")
                            val name = case.getAttribute("name")
                            val tc = ParsedTestCase(
                                time = time,
                                className = className,
                                name = name,
                                tier = tier,
                                budget = budget,
                            )
                            slowest.add(tc)
                            if (time > budget) {
                                violations.add(tc)
                            }
                        }
                    } catch (_: Exception) {
                        logger.warn("verifyPerTestBudget: could not parse {}", xml)
                    }
                }
            }
        }

        parseResults(unitResultsDirs, "unit", unitBudget)
        parseResults(integrationResultsDirs, "integration", integrationBudget)

        logger.lifecycle(
            "[test-budget] total test time: %.1fs across %d tests (budgets: unit %.1fs, integration %.1fs)".format(
                totalTime,
                testCount,
                unitBudget,
                integrationBudget,
            ),
        )
        logger.lifecycle("[test-budget] top 10 slowest tests:")
        slowest.sortedByDescending { it.time }.take(10).forEachIndexed { index, tc ->
            val exceeded = if (tc.time > tc.budget) " [EXCEEDED > %.1fs]".format(tc.budget) else ""
            logger.lifecycle(
                "[test-budget]   %2d. %6.2fs [%s] %s — %s%s".format(
                    index + 1,
                    tc.time,
                    tc.tier,
                    tc.className,
                    tc.name,
                    exceeded,
                ),
            )
        }

        if (violations.isNotEmpty()) {
            logger.lifecycle("[test-budget] %d test(s) exceeded per-test budget:".format(violations.size))
            violations.sortedByDescending { it.time }.take(10).forEach { tc ->
                logger.lifecycle(
                    "[test-budget]   - [%s] %s.%s took %.2fs > %.1fs budget".format(
                        tc.tier,
                        tc.className,
                        tc.name,
                        tc.time,
                        tc.budget,
                    ),
                )
            }
            if (violations.size > 10) {
                logger.lifecycle("[test-budget]   ... and %d more".format(violations.size - 10))
            }
        }

        if (failureCount == 0 && violations.isNotEmpty()) {
            if (ciMode) {
                logger.lifecycle(
                    "[test-budget] CI mode (-Pci): %d test(s) exceeded budget — report only, build stays green.".format(
                        violations.size,
                    ),
                )
            } else {
                val unitViolation = violations.filter { it.tier == "unit" }.maxByOrNull { it.time }
                val worst = unitViolation ?: violations.maxByOrNull { it.time } ?: violations.first()
                val advice = if (worst.tier == "unit") {
                    "Optimize this test or categorize it as an integration test (@Tag(\"integration\"))."
                } else {
                    "Optimize this test or increase budget via -Pintegration.test.budget=<seconds>."
                }
                throw GradleException(
                    "Per-test budget exceeded: test '${worst.className}.${worst.name}' took ${"%.2f".format(worst.time)}s > budget ${"%.2f".format(worst.budget)}s. $advice",
                )
            }
        }
    }
}

// Deprecated alias for backwards compatibility
tasks.register("verifyTier1Budget") {
    group = "verification"
    description = "Deprecated alias: delegates to verifyPerTestBudget."
    dependsOn("verifyPerTestBudget")
}

// Aggregated HTML report across all modules and tiers 1–2 (docs/TESTING.md §13).
tasks.register<TestReport>("testReport") {
    group = "verification"
    description = "Aggregated HTML test report across all modules (tiers 1–2). Output: build/reports/allTests."
    destinationDirectory.set(layout.buildDirectory.dir("reports/allTests"))
    testResults.from(
        files(
            subprojects.flatMap { subproject ->
                listOf("test", "integrationTest", "tier2Test").map { taskName ->
                    subproject.layout.buildDirectory.dir("test-results/$taskName")
                }
            },
        ),
    )
    dependsOn(
        subprojects.flatMap { subproject ->
            listOf(subproject.tasks.named("test"), subproject.tasks.named("integrationTest"))
        },
    )
}

// Root task: integrationTest runs integration tests across all modules
tasks.register("integrationTest") {
    group = "verification"
    description = "Integration tests across all modules (@Tag(\"integration\"), @Tag(\"tier2\"))."
    dependsOn(subprojects.map { it.tasks.named("integrationTest") })
}

// Compatibility alias for integrationTest
tasks.register("tier2Test") {
    group = "verification"
    description = "Compatibility alias: delegates to integrationTest."
    dependsOn("integrationTest")
}

// Alias for test
tasks.register("unitTest") {
    group = "verification"
    description = "Unit tests across all modules (runs `test`)."
    dependsOn(subprojects.map { it.tasks.named("test") })
}

// Tier 3: the corpus soak (docs/TESTING.md §8). Deliberately NOT part of `check`, so
// `./gradlew check` stays green on machines with no local jar corpus. Point it at a corpus
// with `-Pcorpus=<dir>` (default: this machine's Gradle cache); tests read it via the
// `jdx.corpusDir` system property and must skip — never fail — when it is absent.
tasks.register("soak") {
    group = "verification"
    description = "Tier-3 corpus soak: runs @Tag(\"soak\") tests. Usage: ./gradlew soak [-Pcorpus=<dir>]."
    dependsOn(subprojects.map { it.tasks.named("soakTest") })
}

// Guard task for Tier 4 tests. Runs early to fail fast before long compiles or test runs.
val enforceTier4Permission = tasks.register("enforceTier4Permission") {
    group = "verification"
    description = "Enforces explicit user permission for Tier 4 tests (-PconfirmTier4=true or -PallowHeavy=true)."
    val confirmed = isTier4Confirmed
    val message = tier4PermissionMessage
    doLast {
        if (!confirmed) {
            throw GradleException(message)
        }
    }
}

// Tier 4: benchmarks (PROPOSAL.md §15, T-050). The `bench` smoke lives in
// `:cli` (`BenchSmokeTest`, @Tag("bench"), over the fixture jar); per-module
// `benchTest` tasks run every `@Tag("bench")` test.
tasks.register("bench") {
    group = "verification"
    description = "Tier-4 benchmarks. See BenchSmokeTest (T-050) for the smoke run."
    val confirmed = isTier4Confirmed
    val message = tier4PermissionMessage
    doFirst {
        if (!confirmed) {
            throw GradleException(message)
        }
    }
    dependsOn(enforceTier4Permission)
    dependsOn(subprojects.map { it.tasks.named("benchTest") })
}

// Tier 4: mutation testing (docs/TESTING.md §10, T-060). Runs PIT over every gated module;
// the per-module `pitest {}` blocks own their thresholds (core ≥ 80 % mutation, build-failing)
// while this task is the single entry point from TESTING.md §13.
tasks.register("mutationTest") {
    group = "verification"
    description = "Tier-4 mutation testing (PIT): core gate ≥ 80 % mutation score, build-failing. Others measured and reported."
    val confirmed = isTier4Confirmed
    val message = tier4PermissionMessage
    doFirst {
        if (!confirmed) {
            throw GradleException(message)
        }
    }
    dependsOn(enforceTier4Permission)
    dependsOn(
        pitestModulesRun.map { module -> project(":$module").tasks.named("pitest") } +
            // #66: check the preconditions first, so a wiring regression reads as one clear
            // red line instead of four confusing PIT pre-scan aborts 20 minutes in.
            tasks.named("verifyPitestWiring"),
    )
    doLast {
        logger.lifecycle("mutationTest: PIT reports in <module>/build/reports/pitest (XML + HTML, un-timestamped).")
        logger.lifecycle("mutationTest: core gate is >= 80 % mutation score (build-failing); index/sources/decompile are measured and reported (D-021).")
    }
}

// #66: what every PIT module's `pitest` task will (or will not) hand its minion. Filled in
// at configuration time by the hook below, asserted by `verifyPitestWiring`. Plain strings
// in a plain list: a captured `Project`/`Task` would break the configuration cache (T-067),
// and the snapshot has to be a value, not a live view of a changing task model.
val pitestModules = mutableListOf<String>()
val pitWiringGaps = mutableListOf<String>()

// The modules tier-4 mutation testing runs over — one list, read by `mutationTest` to
// build its task graph and by `verifyPitestWiring` to assert that the modules which
// *apply* the PIT plugin are exactly these. Spelling it twice would be a third copy of a
// fact that has to agree, which is the whole lesson of #66.
val pitestModulesRun = listOf("core", "index", "sources", "decompile")

// #66: the fixture-corpus wiring for mutation testing, shared by **every** module that
// applies the PIT plugin (today core/index/sources/decompile) instead of living as a
// private copy in `index`. Two facts make this mandatory rather than cosmetic, and both
// only bite on a clean checkout — which is exactly why it went unnoticed locally:
//
//  1. A PIT minion is a *fresh JVM*. It does not inherit the `test`/`tier2Test` system
//    properties, so a corpus test that reads `jdx.fixturesDir` falls back to walking up
//    from `user.dir` — which finds a stale `testfixtures/build/libs` on a developer
//    machine and nothing at all on a runner.
//  2. `dependsOn(":testfixtures:jar", …)` lives on `test`/`tier2Test` only, and the
//    `testFixtures(project(":testfixtures"))` test-classpath edge builds the *binary*
//    jar alone. The `-sources.jar` is produced by `:testfixtures:build`, which
//    `./gradlew mutationTest` never reaches — so a clean runner has no corpus at all.
//
// Together they abort PIT in the *pre-scan* (which runs the suite once unmutated to
// compute line coverage) with "N tests did not pass without mutation … requires a green
// suite" — before a single mutant is generated. `core`'s ≥ 80 % gate therefore never ran
// in CI at all, which is worse than no gate: a gate that cannot execute looks exactly
// like a passing one. `verifyPitestWiring` below is what stops that from recurring.
//
// `projectsEvaluated` rather than a `plugins.withId`/`configureEach` pair, because the
// task is registered *lazily* by the PIT plugin: `configureEach` would only fire when
// something realises it (i.e. when `pitest` is actually requested), so a gate that runs
// in `check` would never see any wiring. This hook realises the four tasks at the end of
// configuration — the same moment a real `./gradlew mutationTest` would, and after every
// `pitest {}` extension block has run, so nothing here can pre-empt a module's own
// configuration. The cost is creating four task objects on every build; the benefit is
// that the wiring and its gate can never disagree about what a run will see.
gradle.projectsEvaluated {
    val fixtureJarsDir = project(":testfixtures").layout.buildDirectory.dir("libs").get().asFile
    val diffFixtureJarsDir =
        project(":testfixtures").layout.buildDirectory.dir("diff-fixtures").get().asFile
    subprojects.forEach { module ->
        // No `plugins.withId` guard needed: without the PIT plugin there is no task of
        // this type, so the collection is simply empty.
        module.tasks.withType(PitestTask::class.java).forEach { pitestTask ->
            // The properties go on the *extension*, not on the task: `PitestTask` is a
            // `JavaExec`, and its own `jvmArgs` are the PIT launcher's, while the minions
            // that run the suite get theirs from `pitest { jvmArgs }`. This is the same
            // property the module `pitest {}` blocks write to.
            val extension = module.extensions.getByType(PitestPluginExtension::class.java)
            extension.jvmArgs.add("-D$fixtureJarsDirProperty=" + fixtureJarsDir.absolutePath)
            extension.jvmArgs.add(
                "-D$diffFixtureJarsDirProperty=" + diffFixtureJarsDir.absolutePath,
            )
            // Absolute paths are fine here: PIT runs inside this build, never from a
            // relocatable cache entry, and no test hard-codes a path (T-006).
            pitestTask.dependsOn(":testfixtures:jar", ":testfixtures:sourcesJar")
            // The `jdx diff` pair (issue #23, TESTING.md §11.2) has its own directory and
            // its own property. `:index` is the only module that reads it today, but the
            // wiring is identical for every PIT module, and splitting it again is how the
            // triplication started.
            pitestTask.dependsOn(":testfixtures:diffV1Jar", ":testfixtures:diffV2Jar")
            val confirmed = isTier4Confirmed
            val message = tier4PermissionMessage
            pitestTask.doFirst {
                if (!confirmed) {
                    throw GradleException(message)
                }
            }
            pitestTask.dependsOn(enforceTier4Permission)
            pitestTask.mustRunAfter(enforceTier4Permission)

            // Recorded, not asserted, here: the assertion lives in `verifyPitestWiring`,
            // which every `check` runs, so a future edit that drops one of the facts above
            // fails on a push instead of in a 20-minute weekly run.
            pitestModules += module.path.removePrefix(":")
            val gaps = mutableListOf<String>()
            val forwarded = extension.jvmArgs.getOrElse(emptyList())
            listOf(fixtureJarsDirProperty, diffFixtureJarsDirProperty).forEach { property ->
                if (forwarded.none { it.startsWith("-D$property=") }) {
                    gaps += "the PIT minion never receives -D$property"
                }
            }
            // The *declared* dependencies, not resolved ones: resolving a task graph is not
            // allowed this early in the lifecycle (and a gate must never perturb what it
            // measures). `dependsOn` holds exactly the paths wired two lines above.
            val declared = pitestTask.dependsOn.toList()
            listOf("jar", "sourcesJar", "diffV1Jar", "diffV2Jar").forEach { jar ->
                val path = ":testfixtures:$jar"
                if (declared.none { it.toString() == path }) {
                    gaps += "no task dependency on $path, so a clean checkout has no corpus"
                }
            }
            if (gaps.isNotEmpty()) pitWiringGaps += "${module.path}: ${gaps.joinToString()}"
        }
    }
}

// #66: the wiring `mutationTest` depends on, asserted on every `check`.
//
// Why a gate at all: the missing PIT corpus wiring only shows up on a *clean* checkout, and
// its symptom is PIT aborting in the pre-scan — which the weekly `heavy` run reports as a
// red nightly, not as a broken gate. Nothing in `check` noticed, so `core`'s ≥ 80 %
// mutation score (T-060) had never actually been enforced in CI. A gate that cannot run
// is indistinguishable from a passing one, so the preconditions get their own cheap gate:
// no JVM, no PIT, sub-second, and red on exactly the conditions that produced #66.
//
// Three invariants, all cheap, all about *the whole set* rather than one module:
//  1. some module applied the PIT plugin (otherwise this gate checked nothing and would
//     pass vacuously — e.g. if the plugin id ever changed);
//  2. every PIT module forwards both corpus properties and builds all four fixture jars,
//     so a module added tomorrow inherits the wiring and this proves it did;
//  3. the PIT modules are exactly the ones `mutationTest` runs, so a fifth module cannot
//     join the build and be left out of the tier-4 run.
tasks.register("verifyPitestWiring") {
    group = "verification"
    description = "Asserts every PIT module is wired to the fixture corpus (#66) — the precondition for a green PIT pre-scan."
    // Deferred to execution time on purpose: the gaps are only known once every subproject
    // has been configured, which is after this task's own configuration block ran. A
    // provider is captured here (not a script value) so the configuration cache can
    // serialise it (T-067).
    val gaps = provider { pitWiringGaps.toList() }
    val modules = provider { pitestModules.toSortedSet() }
    val expected = provider { pitestModulesRun.toSortedSet() }
    doLast {
        val found = modules.get()
        if (found.isEmpty()) {
            throw GradleException(
                "verifyPitestWiring: no module applied the PIT plugin, so this gate checked " +
                    "nothing. Has the plugin id `info.solidsoft.pitest` changed?",
            )
        }
        val missing = gaps.get()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "verifyPitestWiring: ${missing.size} PIT module(s) cannot get a green " +
                    "pre-scan (issue #66):\n" + missing.joinToString("\n"),
            )
        }
        if (found != expected.get()) {
            throw GradleException(
                "verifyPitestWiring: PIT runs in [${found.joinToString()}] but mutationTest " +
                    "runs [${expected.get().joinToString()}]. Add the module to both, or " +
                    "remove the plugin — a module nobody mutates is a module nobody gates.",
            )
        }
        logger.lifecycle("verifyPitestWiring: PIT wired to the fixture corpus in ${found.joinToString()}.")
    }
}

// T-052: dependency-free style gate (CONTRIBUTING.md already promises
// `./gradlew check` runs "tests + lint" — this task is the lint half). No
// detekt/ktlint: four grep-able rules need no new dependency, and 100-column
// stays a soft limit (774 main-source lines over it), so it is explicitly not
// gated here. Every rule is green on today's tree (verified pre-claim); a new
// violation fails `check` with file:line pointers.
tasks.register("lint") {
    group = "verification"
    description = "Style gate: trailing whitespace, tabs, bare TODO/FIXME, console IO in library mains (T-052)."
    // Captured at configuration time as plain values: touching `project` from
    // `doLast` breaks the configuration cache (T-067).
    val lintRoot: java.io.File = rootDir
    val lintExtensions = listOf(".kt", ".kts")
    val libModules = listOf("core", "index", "sources", "decompile")
    // Hardened inputs (issue: one-build `:lint` validation red "uses this output of
    // :core:compileKotlin" after build-logic change amid --rerun-tasks/stash churn).
    // Root cause: the old `fileTree(lintRoot) { include(...) }` declared every
    // `**/build/**` path as an input, overlapping `compileKotlin` outputs (e.g.
    // `core/build/...`). Gradle's task-dependency validation then demanded an
    // explicit dependency that a source-only style gate must never have. The
    // excludes below mirror the runtime skips in `doLast`, so declared inputs
    // match actual reads and can never overlap task outputs. Do NOT re-add
    // `build/` to lint inputs to fix a wiring flake; the 4 lint rules are unchanged.
    inputs.files(
        fileTree(lintRoot) {
            include("**/*.kt", "**/*.kts")
            exclude("**/build/**", "**/.git/**", "**/.gradle/**", "**/.kotlin/**")
        },
    ).withPropertyName("sources").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    doLast {
        val taskRef = Regex("T-\\d+")
        val violations = mutableListOf<String>()
        lintRoot.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (lintExtensions.none { file.name.endsWith(it) }) return@forEach
            // Build outputs, VCS metadata and Gradle homes are not sources.
            val rel = file.relativeTo(lintRoot).path
            if (rel.split('/').any { it == "build" || it == ".git" || it == ".gradle" || it == ".kotlin" }) return@forEach
            val inLibMain = libModules.any { rel.startsWith("$it/src/main/") }
            file.readLines().forEachIndexed { index, line ->
                val where = "$rel:${index + 1}"
                if (line.endsWith(' ') || line.endsWith('\t')) violations += "$where: trailing whitespace"
                if ('\t' in line) violations += "$where: tab character (4-space indent, CONTRIBUTING.md)"
                // The rule names its own markers (T-052 self-hosting): without the
                // task reference on these two lines, lint would flag itself.
                if ((line.contains("TODO") || line.contains("FIXME")) && !taskRef.containsMatchIn(line)) { // T-052
                    violations += "$where: bare TODO/FIXME without a task reference (rule owned by T-052)"
                }
                if (inLibMain && (line.contains("println(") || line.contains("System.exit") || line.contains("printStackTrace"))) {
                    violations += "$where: console IO in a library main (adapters own IO, D-004)"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "lint: ${violations.size} violation(s):\n" +
                    violations.sorted().take(20).joinToString("\n") +
                    if (violations.size > 20) "\n… and ${violations.size - 20} more" else "",
            )
        }
        logger.lifecycle("lint: clean.")
    }
}

data class ParsedTestCase(
    val time: Double,
    val className: String,
    val name: String,
    val tier: String,
    val budget: Double,
) : java.io.Serializable

open class JdxTestProgressListener(
    val taskPath: String,
    val slowThresholdSeconds: Double,
) : org.gradle.api.tasks.testing.TestListener {
    private var taskStartTime: Long = 0L
    private val testStartTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) {
        if (suite.parent == null && taskStartTime == 0L) {
            taskStartTime = System.currentTimeMillis()
        }
    }

    override fun afterSuite(suite: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
        if (suite.parent == null) {
            val totalSec = if (taskStartTime > 0L) (System.currentTimeMillis() - taskStartTime) / 1000.0 else 0.0
            println("[$taskPath] Finished ${completedCount.get()} tests in ${"%.2f".format(totalSec)}s (${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped)")
        } else if (result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE) {
            println("[$taskPath] [SUITE FAILED] ${suite.name}")
            result.exceptions.forEach { ex ->
                val sw = java.io.StringWriter()
                ex.printStackTrace(java.io.PrintWriter(sw))
                println(sw.toString().trimEnd())
            }
        }
    }

    override fun beforeTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor) {
        if (taskStartTime == 0L) {
            taskStartTime = System.currentTimeMillis()
        }
        val key = System.identityHashCode(testDescriptor).toString()
        testStartTimes[key] = System.currentTimeMillis()
    }

    override fun afterTest(testDescriptor: org.gradle.api.tasks.testing.TestDescriptor, result: org.gradle.api.tasks.testing.TestResult) {
        val count = completedCount.incrementAndGet()
        val key = System.identityHashCode(testDescriptor).toString()
        val startedAt = testStartTimes.remove(key)
        val measuredSec = if (startedAt != null) (System.currentTimeMillis() - startedAt) / 1000.0 else 0.0
        val resultSec = (result.endTime - result.startTime).coerceAtLeast(0L) / 1000.0
        val durationSec = if (resultSec > 0.0) resultSec else measuredSec
        val elapsedSec = if (taskStartTime > 0L) (System.currentTimeMillis() - taskStartTime) / 1000.0 else durationSec

        val displayClass = testDescriptor.className?.substringAfterLast('.') ?: (testDescriptor.parent?.name ?: "Unknown")
        val methodName = testDescriptor.name
        val isSlow = durationSec > slowThresholdSeconds
        val rate = if (elapsedSec > 0.1) "%.1f tests/s".format(count / elapsedSec) else ""

        if (isSlow) {
            println("[$taskPath] [SLOW TEST] $displayClass > $methodName took ${"%.3f".format(durationSec)}s (> ${"%.1f".format(slowThresholdSeconds)}s threshold)")
        }

        when (result.resultType) {
            org.gradle.api.tasks.testing.TestResult.ResultType.SUCCESS -> {
                println("[$taskPath] #$count [SUCCESS] $displayClass > $methodName (${"%.3f".format(durationSec)}s, elapsed ${"%.1f".format(elapsedSec)}s${if (rate.isNotEmpty()) ", $rate" else ""})")
            }
            org.gradle.api.tasks.testing.TestResult.ResultType.SKIPPED -> {
                println("[$taskPath] #$count [SKIPPED] $displayClass > $methodName (elapsed ${"%.1f".format(elapsedSec)}s)")
            }
            org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE -> {
                println("[$taskPath] #$count [FAILED] $displayClass > $methodName (took ${"%.3f".format(durationSec)}s, elapsed ${"%.1f".format(elapsedSec)}s)")
                result.exceptions.forEach { ex ->
                    val sw = java.io.StringWriter()
                    ex.printStackTrace(java.io.PrintWriter(sw))
                    println(sw.toString().trimEnd())
                }
            }
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "dev.jdx"
    version = rootProject.findProperty("jdxVersion")?.toString() ?: "0.1.0-SNAPSHOT"

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(javaToolchainVersion)

        compilerOptions {
            // Treat JSR-305 nullability annotations on Java APIs as strict Kotlin types.
            // We read a lot of annotated Java (ASM, JavaParser); without this their platform
            // types silently defeat null-safety, which is half of why we chose Kotlin (D-001).
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }

    // T-083: warnings are errors on test compilations too (main compilations were
    // gated in T-052). Task-scoped, not the extension default, so the task name
    // stays visible next to the gate. Test sources additionally opt into kotest's
    // experimental API once via a compiler flag (T-083) rather than per-call-site
    // `@OptIn` annotations — scoped to `compileTestKotlin`, whose classpath always
    // carries kotest through the shared test dependencies below: the same flag on
    // `compileTestFixturesKotlin` breaks the build (unresolved opt-in marker is a
    // hard error there, proven red in-session). Java is gated with `-Werror` as
    // well — every Java source in the repo is a `testfixtures` fixture, and the one
    // deliberate warning (`strictfp` in `VarargsAndModifiers`) is suppressed at its
    // declaration with a comment pointing here.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        if (name == "compileKotlin") {
            compilerOptions.allWarningsAsErrors.set(true)
        }
        if (name == "compileTestKotlin" || name == "compileTestFixturesKotlin") {
            compilerOptions.allWarningsAsErrors.set(true)
        }
        if (name == "compileTestKotlin") {
            compilerOptions.freeCompilerArgs.add("-opt-in=io.kotest.common.ExperimentalKotest")
        }
    }
    tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        options.compilerArgs.add("-Werror")
    }

    dependencies {
        "implementation"(kotlinStdlib)

        "testImplementation"(junitJupiter)
        "testRuntimeOnly"(junitLauncher)
        "testImplementation"(kotestAssertions)
    }

    // Every Test task uses JUnit Platform and live progress logging via JdxTestProgressListener.
    // #57 CI policy: no per-task `timeout` is set here or in CI — Test tasks stay
    // fail-open on wall-clock so slow runners never produce false-positive reds.
    // Per-test budgets (verifyPerTestBudget, report-only under -Pci) and PIT
    // timeoutConstInMillis (lifted under -Pci) are wall-clock gates.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            // Keep empty events so JdxTestProgressListener handles all output cleanly without duplicate stack traces
            events()
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }
        val tPath = path
        val isUnit = name == "test" || name == "unitTest"
        val slowThreshold = if (isUnit) 1.0 else 5.0
        addTestListener(JdxTestProgressListener(tPath, slowThreshold))
    }

    // Tier 1 (`test`): the TDD loop, per-test budget enforced by `verifyPerTestBudget`.
    // Anything touching disk, a subprocess, or a real jar belongs in integration / tier 2 or above.
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("integration", "tier2", "soak", "bench")
        }
        finalizedBy(rootProject.tasks.named("verifyPerTestBudget"))
    }

    // Integration tests (`integrationTest`): slow suites (golden, fault-injection, parity, jar/JVM tests).
    // `check` runs both `test` and this.
    val integrationTest = tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Integration tests for this module (@Tag(\"integration\"), @Tag(\"tier2\")). Runs as part of `check`."
        val testSets = project.extensions.getByType<SourceSetContainer>().getByName("test")
        testClassesDirs = testSets.output.classesDirs
        classpath = testSets.runtimeClasspath
        useJUnitPlatform {
            includeTags("integration", "tier2")
        }
        finalizedBy(rootProject.tasks.named("verifyPerTestBudget"))
        // Golden-file update mode (T-054, TESTING.md §14): `./gradlew check
        // -Pgolden.update=true` rewrites every golden under
        // `src/test/resources/golden`. Centralised here so every module's
        // golden suite honours the same flag; the flag travels as a system
        // property so tests stay environment-agnostic. Standard streams are
        // shown in update mode so the per-file rewrite summary (which files
        // changed — the reviewer's blast radius) reaches the console instead
        // of dying in a captured test log.
        systemProperty("jdx.golden.update", (findProperty("golden.update") as String?) ?: "false")
        if ((findProperty("golden.update") as String?) == "true") {
            testLogging.showStandardStreams = true
        }
    }

    // Compatibility alias: tier2Test delegates to integrationTest
    tasks.register("tier2Test") {
        group = "verification"
        description = "Compatibility alias: delegates to integrationTest."
        dependsOn(integrationTest)
    }

    // Alias: unitTest delegates to test
    tasks.register("unitTest") {
        group = "verification"
        description = "Alias: delegates to test."
        dependsOn(tasks.named("test"))
    }

    tasks.named("check") {
        dependsOn(integrationTest)
        // T-052: the `lint` style gate runs with every `check` (CONTRIBUTING.md
        // promises "tests + lint"). One root task, not per-module duplicates.
        dependsOn(rootProject.tasks.named("lint"))
        // #66: same treatment for the PIT corpus wiring — the precondition for the
        // `core` mutation gate to run at all. Cheap, and it is the only thing standing
        // between a clean checkout and a mutation tier that silently never executes.
        dependsOn(rootProject.tasks.named("verifyPitestWiring"))
    }

    // Line-coverage gates (T-060, docs/TESTING.md §10, D-021). Only the modules with a gate
    // get the `jacoco` plugin: core/index/sources/decompile. cli/mcp/server are thin
    // adapters with deliberately no gate; testfixtures/app are not product code.
    if (project.name in setOf("core", "index", "sources", "decompile")) {
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = jacocoToolVersion
        }
        // core ≥ 95 % line; index/sources/decompile ≥ 85 % line. sources/decompile are
        // KDoc-only stubs until M3 lands, so their gates pass vacuously today and start
        // biting the moment real code arrives — that is intentional, not a hole.
        val lineMinimum: Double = if (project.name == "core") 0.95 else 0.85
        // Both tiers contribute coverage: tier-1 unit/property tests AND tier-2 golden,
        // differential, fault-injection and parity suites. Gating on tier 1 alone would
        // punish the tier split from T-053, where jar-reading tests live in tier 2.
        val coverageExecData = files(
            layout.buildDirectory.file("jacoco/test.exec"),
            layout.buildDirectory.file("jacoco/integrationTest.exec"),
            layout.buildDirectory.file("jacoco/tier2Test.exec"),
        )
        tasks.withType<JacocoReport>().configureEach {
            dependsOn("test", "integrationTest")
            executionData(coverageExecData)
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn("test", "integrationTest")
            executionData(coverageExecData)
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = lineMinimum.toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
    }

    // Tier 3: corpus soak. Excluded from `check` by construction (a separate task nothing
    // in the default lifecycle depends on). `jdx.tier=soak` lets the exclusion proof test
    // tell a real soak run apart from an accidental one.
    tasks.register<Test>("soakTest") {
        group = "verification"
        description = "Tier-3 corpus soak for this module (@Tag(\"soak\")). Runs via `./gradlew soak`, never via `check`."
        val testSets = project.extensions.getByType<SourceSetContainer>().getByName("test")
        testClassesDirs = testSets.output.classesDirs
        classpath = testSets.runtimeClasspath
        useJUnitPlatform {
            includeTags("soak")
        }
        // Soak indexes JDK-scale artifacts plus their reference edges (T-029
        // roughly doubles the stored rows): the default 512 MB worker heap
        // OOMs on the full `jrt:/` run. Tier 3 is explicitly the heavy tier,
        // so size its heap like one — `check` never pays this.
        maxHeapSize = "2g"
        systemProperty("jdx.tier", "soak")
        systemProperty("jdx.corpusDir", corpusDir)
        // Seeded sampling (T-059): `-PsoakSeed=<n>` reproduces a failing sample.
        // Only forwarded when given; tests fall back to their DEFAULT_SEED.
        (findProperty("soakSeed") as String?)?.let { seed ->
            systemProperty("jdx.soakSeed", seed)
        }
    }

    // Tier 4: benchmarks. Benchmarks themselves land in T-050.
    tasks.register<Test>("benchTest") {
        group = "verification"
        description = "Tier-4 benchmarks for this module (@Tag(\"bench\")). Runs via `./gradlew bench`."
        val testSets = project.extensions.getByType<SourceSetContainer>().getByName("test")
        testClassesDirs = testSets.output.classesDirs
        classpath = testSets.runtimeClasspath
        useJUnitPlatform {
            includeTags("bench")
        }
        val confirmed = isTier4Confirmed
        val message = tier4PermissionMessage
        doFirst {
            if (!confirmed) {
                throw GradleException(message)
            }
        }
        dependsOn(rootProject.tasks.named("enforceTier4Permission"))
        mustRunAfter(rootProject.tasks.named("enforceTier4Permission"))
    }
}
