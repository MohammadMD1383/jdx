import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestReport

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
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
    doLast {
        var totalTime = 0.0
        var testCount = 0
        var failureCount = 0
        val slowest = mutableListOf<Triple<Double, String, String>>()
        subprojects.forEach { subproject ->
            val resultsDir = subproject.layout.buildDirectory.dir("test-results/test").get().asFile
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
                tier1BudgetSeconds,
                testCount,
            ),
        )
        slowest.sortedByDescending { it.first }.take(10).forEach { (time, className, name) ->
            logger.lifecycle("[tier1] slowest: %6.2fs %s — %s".format(time, className, name))
        }
        if (failureCount == 0 && totalTime > tier1BudgetSeconds) {
            throw GradleException(
                "Tier-1 budget exceeded: %.1fs > %.1fs. Move the slowest suite to tier 2 " +
                    "(@Tag(\"tier2\"), docs/TESTING.md §2).".format(totalTime, tier1BudgetSeconds),
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

// Tier 4: benchmarks (PROPOSAL.md §15, docs/TESTING.md §13). Real benchmarks land in T-050;
// the per-module `benchTest` tasks already run `@Tag("bench")` tests once they exist.
tasks.register("bench") {
    group = "verification"
    description = "Tier-4 benchmarks. Full benchmark suite lands in T-050."
    dependsOn(subprojects.map { it.tasks.named("benchTest") })
    doLast {
        logger.lifecycle("bench: no benchmarks yet (T-050). Per-module benchTest tasks are wired.")
    }
}

// Tier 4: mutation testing (docs/TESTING.md §10). Full Pitest wiring with the T-060 gates
// (core ≥ 95 % line / ≥ 80 % mutation) lands in T-060; this is the stable entry point.
tasks.register("mutationTest") {
    group = "verification"
    description = "Tier-4 mutation testing. Full Pitest wiring lands in T-060."
    doLast {
        logger.lifecycle("mutationTest: Pitest wiring lands in T-060. Gates will be core ≥ 95 % line / ≥ 80 % mutation.")
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
    }
    tasks.named("check") { dependsOn(tier2Test) }

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
        systemProperty("jdx.tier", "soak")
        systemProperty("jdx.corpusDir", corpusDir)
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
