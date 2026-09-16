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
// - Both jars must be **byte-deterministic** (CLAUDE.md §2.5): fixed entry timestamps and
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

// Every archive this module produces is reproducible: fixed timestamps, sorted entries.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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
    dependsOn(sourcesJar)
}

dependencies {
    // Property tests for the shared test helpers (T-054/T-055). Versions live
    // only in the catalog — never inline (T-001 rule).
    testImplementation(libs.kotest.property)
}
