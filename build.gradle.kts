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
val tier1BudgetSeconds = (findProperty("tier1.budget") as String?)?.toDoubleOrNull() ?: 30.0
val corpusDir = (findProperty("corpus") as String?) ?: "${System.getProperty("user.home")}/.gradle/caches"

// Prints the tier-1 total test time and the 10 slowest tests, and fails the build when the
// total exceeds the tier-1 budget (override with `-Ptier1.budget=<seconds>`). Wired as a
// finalizer of every module's `test` task, so the report always prints at the end of a
// `./gradlew test` run. Times come from the JUnit XML results (`test-results/test`), so the
// gate is meaningful on a full run; a single-module run aggregates stale results from the
// other modules (said in the report, not hidden). When tests themselves failed the budget
// gate stays silent — the build is already red, and one failure at a time is enough.
tasks.register("verifyTier1Budget") {
    group = "verification"
    description = "Enforces the tier-1 <30 s budget: prints total test time and the 10 slowest tests (docs/TESTING.md §2)."
    // Resolved here, at configuration time: touching `subprojects`, the project
    // version, or any script-level value from `doLast` captures the script object
    // in the action and breaks the configuration cache (T-067). Plain values
    // captured as locals serialize fine.
    val tier1ResultsDirs: List<java.io.File> =
        subprojects.map { it.layout.buildDirectory.dir("test-results/test").get().asFile }
    val tier1Budget: Double = tier1BudgetSeconds
    doLast {
        var totalTime = 0.0
        var testCount = 0
        var failureCount = 0
        val slowest = mutableListOf<Triple<Double, String, String>>()
        tier1ResultsDirs.forEach { resultsDir ->
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
                        slowest.add(
                            Triple(
                                time,
                                "${case.getAttribute("classname")}",
                                case.getAttribute("name"),
                            ),
                        )
                    }
                } catch (_: Exception) {
                    // A concurrently-written or corrupt XML must never fail the budget gate;
                    // the test task itself already reports real failures.
                    logger.warn("verifyTier1Budget: could not parse {}", xml)
                }
            }
        }
        logger.lifecycle(
            "[tier1] total test time: %.1fs (budget %.1fs) across %d tests".format(
                totalTime,
                tier1Budget,
                testCount,
            ),
        )
        slowest.sortedByDescending { it.first }.take(10).forEach { (time, className, name) ->
            logger.lifecycle("[tier1] slowest: %6.2fs %s — %s".format(time, className, name))
        }
        if (failureCount == 0 && totalTime > tier1Budget) {
            throw GradleException(
                "Tier-1 budget exceeded: %.1fs > %.1fs. Move the slowest suite to tier 2 " +
                    "(@Tag(\"tier2\"), docs/TESTING.md §2).".format(totalTime, tier1Budget),
            )
        }
    }
}

// Aggregated HTML report across all modules and tiers 1–2 (docs/TESTING.md §13).
tasks.register<TestReport>("testReport") {
    group = "verification"
    description = "Aggregated HTML test report across all modules (tiers 1–2). Output: build/reports/allTests."
    destinationDirectory.set(layout.buildDirectory.dir("reports/allTests"))
    testResults.from(
        files(
            subprojects.flatMap { subproject ->
                listOf("test", "tier2Test").map { taskName ->
                    subproject.layout.buildDirectory.dir("test-results/$taskName")
                }
            },
        ),
    )
    dependsOn(
        subprojects.flatMap { subproject ->
            listOf(subproject.tasks.named("test"), subproject.tasks.named("tier2Test"))
        },
    )
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

// Tier 4: benchmarks (PROPOSAL.md §15, T-050). The `bench` smoke lives in
// `:cli` (`BenchSmokeTest`, @Tag("bench"), over the fixture jar); per-module
// `benchTest` tasks run every `@Tag("bench")` test.
tasks.register("bench") {
    group = "verification"
    description = "Tier-4 benchmarks. See BenchSmokeTest (T-050) for the smoke run."
    dependsOn(subprojects.map { it.tasks.named("benchTest") })
}

// Tier 4: mutation testing (docs/TESTING.md §10, T-060). Runs PIT over every gated module;
// the per-module `pitest {}` blocks own their thresholds (core ≥ 80 % mutation, build-failing)
// while this task is the single entry point from TESTING.md §13.
tasks.register("mutationTest") {
    group = "verification"
    description = "Tier-4 mutation testing (PIT): core gate ≥ 80 % mutation score, build-failing. Others measured and reported."
    dependsOn(
        project(":core").tasks.named("pitest"),
        project(":index").tasks.named("pitest"),
        project(":sources").tasks.named("pitest"),
        project(":decompile").tasks.named("pitest"),
    )
    doLast {
        logger.lifecycle("mutationTest: PIT reports in <module>/build/reports/pitest (XML + HTML, un-timestamped).")
        logger.lifecycle("mutationTest: core gate is >= 80 % mutation score (build-failing); index/sources/decompile are measured and reported (D-021).")
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
    inputs.files(fileTree(lintRoot) { include("**/*.kt", "**/*.kts") })
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

    // Every Test task uses JUnit Platform and the same failure-focused logging. Tag filters
    // are set per task below — never here — so each tier reads as one self-contained block.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }
    }

    // Tier 1 (`test`): the TDD loop, budget < 30 s enforced by `verifyTier1Budget`.
    // Anything touching disk, a subprocess, or a real jar belongs in tier 2 or above.
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("tier2", "soak", "bench")
        }
        finalizedBy(rootProject.tasks.named("verifyTier1Budget"))
    }

    // Tier 2: slow suites (golden, fault-injection, parity, jar/JVM tests). `check` runs
    // both `test` and this, so tier 2 is a strict superset of tier 1. A custom `Test` task
    // gets no test classes by convention — point it at the `test` source set explicitly,
    // or it silently runs NO-SOURCE and the tier looks green while testing nothing.
    val tier2Test = tasks.register<Test>("tier2Test") {
        group = "verification"
        description = "Tier-2 slow suites for this module (@Tag(\"tier2\")). Runs as part of `check`."
        val testSets = project.extensions.getByType<SourceSetContainer>().getByName("test")
        testClassesDirs = testSets.output.classesDirs
        classpath = testSets.runtimeClasspath
        useJUnitPlatform {
            includeTags("tier2")
        }
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
    tasks.named("check") {
        dependsOn(tier2Test)
        // T-052: the `lint` style gate runs with every `check` (CONTRIBUTING.md
        // promises "tests + lint"). One root task, not per-module duplicates.
        dependsOn(rootProject.tasks.named("lint"))
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
            layout.buildDirectory.file("jacoco/tier2Test.exec"),
        )
        tasks.withType<JacocoReport>().configureEach {
            dependsOn("test", "tier2Test")
            executionData(coverageExecData)
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn("test", "tier2Test")
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
    }
}
