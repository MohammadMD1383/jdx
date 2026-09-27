import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    // Shared golden-file helpers (T-054) live in `src/testFixtures`: a separate
    // jar that test modules consume via `testFixtures(project(":testfixtures"))`.
    // They must NOT live in `src/main` — every class there becomes a fixture the
    // corpus scans would treat as expected API.
    `java-test-fixtures`
}

// The plugin's jar defaults to `build/libs`, where it would sit next to the
// fixture jars and break every "exactly one binary fixture jar" assertion
// (`Fixtures.binaryJar` and friends). It lives in its own directory instead;
// variant-aware resolution follows the task output wherever it points.
tasks.named<Jar>("testFixturesJar") {
    destinationDirectory.set(layout.buildDirectory.dir("test-fixtures-libs"))
}

// # `:testfixtures`
//
// Deliberately nasty Java and Kotlin classes, compiled by Gradle into **both** a binary jar
// and a `-sources.jar`. Nine of the ten test families in `docs/TESTING.md` lean on this corpus
// (differential-vs-`javap`, metamorphic, fault injection, soak, …), so it is built early and
// built well. See `docs/TESTING.md` §11 for the required contents and how to add a fixture.
//
// ## Boundary rules a newcomer would otherwise break
//
// - There is deliberately **no `ModuleInfo.kt` here**: every `.java`/`.kt` file under `src/`
//   becomes a fixture that the differential and corpus tests scan. A documentation file would
//   pollute the corpus it describes. This build file comment is the module documentation.
// - Fixture classes must **never be loaded into a test JVM** — only read as bytes/jar entries
//   (D-017). `StaticInitMarker` has a static initialiser that writes a marker file; a test
//   that loads it (reflection, `Class.forName`, even reading an annotation) proves nothing and
//   ruins the D-017 proof. Use `Fixtures.classBytes(...)` (binary scan) instead.
// - Both jars must be **byte-deterministic** (AGENTS.md): fixed entry timestamps and
//   sorted entry order, or golden tests go flaky. Verified by `FixtureCorpusTest` (entry
//   timestamps) and by building twice and comparing sha256 (see the session log for T-006).

// Same toolchain as every other module; the version lives in the catalog (single source).
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
}

// The Kotlin module name leaks into bytecode (`internal` members are mangled as
// `name$module`). Pin it explicitly so a group/project rename cannot silently rewrite every
// `internal` expectation in the corpus.
kotlin {
    compilerOptions {
        moduleName.set("testfixtures")
    }
}

// A second Java source set compiled with `-g:none` (TESTING.md §7, §11): one fixture class
// must carry no debug info, so readers learn to fall back — and say so — when parameter names
// and line numbers are absent.
val noDebug = sourceSets.create("noDebug") {
    java.srcDir("src/nodebug/java")
}

configurations.named("noDebugCompileClasspath") {
    extendsFrom(configurations.getByName("implementation"))
}

tasks.named<JavaCompile>("compileNoDebugJava") {
    // `-g:none`: no LineNumberTable, no LocalVariableTable, no SourceFile contents beyond
    // the bare minimum. Asserted (without loading the class) in `FixtureCorpusTest`.
    // NOTE: Kotlin keeps the `is` prefix on Java boolean getters — `options.debug` does not
    // resolve; it is `options.isDebug` (L-017).
    options.isDebug = false
    // The annotation lives in main output; reuse it so NoDebug stays self-describing.
    val main = sourceSets.getByName("main")
    classpath = files(main.output) + (classpath ?: files())
}

// Two more source sets, compiled into a **pair** of jars for `jdx diff`
// (issue #23, TESTING.md §11.2). Unlike the main corpus these are two genuinely
// different versions of the same package, so a diff has something to compare.
//
// Both jars go to `build/diff-fixtures/`, NOT `build/libs`, and their base name does
// not start with `testfixtures-`: `FixtureJars.singleJar`, core's `Fixtures.singleJar`
// and `ArtifactTestJars.binaryJar` all `require(size == 1)` over `build/libs` filtering
// names that do, so a second jar there turns three unrelated assertions into exceptions.
// The separate directory is the same trick `testFixturesJar` uses above.
//
// No `-sources.jar` for the pair: a diff reads bytecode only, so a sources jar for it
// would be an artifact no test can use.
val diffV1 = sourceSets.create("diffV1") {
    java.srcDir("src/diffv1/java")
    kotlin.srcDir("src/diffv1/kotlin")
}
val diffV2 = sourceSets.create("diffV2") {
    java.srcDir("src/diffv2/java")
    kotlin.srcDir("src/diffv2/kotlin")
}

// A supertype that both `Orphaned` classes extend and that is in **neither** diff jar.
// `javac` will not compile a class whose supertype is missing, so the type has to exist
// to compile and then be kept out of the artifacts — which is exactly the situation the
// differ's "inheritance not checked" path exists for: a real superclass living in
// *another dependency*. Its output is a compile classpath entry and nothing else; no
// `Jar` task here reads it.
val diffAbsent = sourceSets.create("diffAbsent") {
    java.srcDir("src/diffabsent/java")
}

listOf(
    Triple("diffV1", "compileDiffV1Java", diffV1),
    Triple("diffV2", "compileDiffV2Java", diffV2),
).forEach { (sourceSetName, javaTaskName, _) ->
    configurations.named("${sourceSetName}CompileClasspath") {
        extendsFrom(configurations.getByName("implementation"))
    }
    // The main output carries `ExpectedMembers`; the diff fixtures live in their own
    // packages and do not annotate (the rule-coverage test in `:index` is what
    // self-describes them), so the classpath is only the JDK plus this module's deps.
    tasks.named<JavaCompile>(javaTaskName) {
        val main = sourceSets.getByName("main")
        classpath = files(main.output) + (classpath ?: files())
    }
    // The absent supertype compiles but is never packaged. Putting its output on the
    // compile classpath is also what orders `compileDiffAbsentJava` before this compile.
    tasks.named<JavaCompile>(javaTaskName) {
        val absent = sourceSets.getByName("diffAbsent")
        classpath = files(absent.output) + (classpath ?: files())
    }
}

// Every archive this module produces is reproducible: fixed timestamps, sorted entries.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val diffV1Jar = tasks.register<Jar>("diffV1Jar") {
    archiveBaseName.set("diff-fixtures-v1")
    destinationDirectory.set(layout.buildDirectory.dir("diff-fixtures"))
    from(diffV1.output)
}

val diffV2Jar = tasks.register<Jar>("diffV2Jar") {
    archiveBaseName.set("diff-fixtures-v2")
    destinationDirectory.set(layout.buildDirectory.dir("diff-fixtures"))
    from(diffV2.output)
}

tasks.named<Jar>("jar") {
    from(noDebug.output)
}

// The `-sources.jar`: the exact sources every fixture class was compiled from, so
// sources-pairing (T-007/T-020) has a ground-truth corpus from day one.
val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from("src/main/java", "src/main/kotlin", "src/nodebug/java")
}

// `build` (and therefore `check`) always produces both jars; nobody should discover a stale
// or missing `-sources.jar` halfway through a differential test run.
tasks.named("build") {
    dependsOn(sourcesJar, diffV1Jar, diffV2Jar)
}

dependencies {
    // Property tests for the shared test helpers (T-054/T-055). Versions live
    // only in the catalog — never inline (T-001 rule).
    testImplementation(libs.kotest.property)
}
