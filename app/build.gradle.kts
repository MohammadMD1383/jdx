// # Assembly (T-004)
import org.gradle.api.tasks.testing.Test
//
// `:app` produces the runnable distribution: one self-contained fat jar plus the `jdx`
// launcher script (src/main/scripts/jdx) that resolves a JDK and starts it. The launcher,
// not Gradle, owns JDK resolution — JAVA_HOME is unset on the maintainer's machine and the
// build must never assume otherwise (CLAUDE.md §8).
//
// Why no shadow/application plugin: the fat jar is a plain `Jar` over the runtime classpath.
// Today's dependencies (our modules, kotlin-stdlib, clikt, kotlinx-serialization, ASM,
// sqlite-jdbc, JavaParser, Vineflower) ship at most ONE META-INF/services provider each and
// no relocated classes, so `duplicatesStrategy = EXCLUDE` is lossless. Revisit — and reach
// for shadow's service-file merging — the moment two dependencies claim the same service
// file or a dependency needs relocation (known gap; see the T-004 session entry).

// The fat jar: `cli` + every runtime dependency, one Main-Class.
val fatJar = tasks.register<Jar>("fatJar") {
    dependsOn(configurations.runtimeClasspath)
    archiveBaseName.set("jdx")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.jdx.cli.JdxCliKt",
            "Implementation-Title" to "jdx",
            "Implementation-Version" to project.version,
        )
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.map { entry -> if (entry.isDirectory) entry else zipTree(entry) }
    }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "module-info.class",
            "META-INF/versions/**/module-info.class",
        )
    }
    // Determinism (CLAUDE.md §2.5): same inputs -> same bytes.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// --- AppCDS archive (T-048) ---
//
// The launcher passes `-Xshare:auto` but CDS never engages without an archive.
// These tasks train one from the fat jar with the *build* JVM's `java` and ship
// `build/libs/jdx.jsa` next to the fat jar, where the launcher picks it up when
// present. The archive is keyed to the exact JDK build that created it; a stale
// or mismatched archive is ignored via `-Xshare:auto`, never a hard failure
// (probed: corrupt archive warns `Not a valid shared archive file` and runs
// normally, exit 0). Measured on this machine: `--version` 187 ms cold vs
// 90 ms warm — the PROPOSAL.md §15 ≤ 250 ms budget with room to spare.
//
// Training command: `members java.util.HashMap --limit 5 --no-daemon` loads the
// broadest single-run class set measured (CLI tree + ASM + resolver +
// renderers, ~3.3k classes — wider than `help` or `doctor`), needs no
// workspace, no network, and is read-only. One run only: merging multiple
// `DumpLoadedClassList` outputs breaks `-Xshare:dump` (conflicting lambda
// `id:` lines → `class list format error`), so the single broadest run wins.
//
// Config-cache rule (T-067): only plain values cross into task actions — the
// java executable path and file locations are captured below as locals.
val buildJavaExe: String = File(System.getProperty("java.home"), "bin/java").absolutePath
val cdsClassList = layout.buildDirectory.file("cds/jdx.lst")
val cdsArchive = layout.buildDirectory.file("libs/jdx.jsa")

val generateCdsClassList = tasks.register<Exec>("generateCdsClassList") {
    group = "distribution"
    description = "Trains the AppCDS class list from the fat jar (T-048)."
    dependsOn(fatJar)
    val fatJarFile = fatJar.flatMap { it.archiveFile }
    inputs.file(fatJarFile)
    outputs.file(cdsClassList)
    commandLine(
        buildJavaExe,
        "-Xshare:off",
        "-XX:DumpLoadedClassList=${cdsClassList.get().asFile.absolutePath}",
        "-jar",
        fatJarFile.get().asFile.absolutePath,
        "members",
        "java.util.HashMap",
        "--limit",
        "5",
        "--no-daemon",
    )
}

val createCdsArchive = tasks.register<Exec>("createCdsArchive") {
    group = "distribution"
    description = "Dumps the AppCDS shared archive (jdx.jsa) next to the fat jar (T-048)."
    dependsOn(generateCdsClassList)
    val fatJarFile = fatJar.flatMap { it.archiveFile }
    inputs.file(fatJarFile)
    inputs.file(cdsClassList)
    outputs.file(cdsArchive)
    commandLine(
        buildJavaExe,
        "-Xshare:dump",
        "-XX:SharedClassListFile=${cdsClassList.get().asFile.absolutePath}",
        "-XX:SharedArchiveFile=${cdsArchive.get().asFile.absolutePath}",
        "-cp",
        fatJarFile.get().asFile.absolutePath,
    )
}

// `./gradlew :app:installDist` -> app/build/jdx (executable) + app/build/libs/jdx-*-all.jar.
// The launcher finds the jar by glob relative to its own resolved path, so the pair can be
// symlinked anywhere (install.sh) and keeps working.
//
// Deliberately NOT a `Copy` task: `Copy` into the build-directory root declares the whole
// directory as its output, which collides with `:app:jar`'s outputs under Gradle's
// implicit-dependency validation. A one-file task with an explicit output keeps `build/jdx`
// (the path named in T-004's acceptance) without claiming anything else.
val launcherScript = layout.projectDirectory.file("src/main/scripts/jdx")
val installDist = tasks.register("installDist") {
    group = "distribution"
    description = "Installs the jdx launcher next to its fat jar in app/build."
    dependsOn(fatJar)
    dependsOn(createCdsArchive)
    inputs.file(launcherScript)
    outputs.file(layout.buildDirectory.file("jdx"))
    // Resolved here, at configuration time: touching `layout` (i.e. the project)
    // from `doLast` captures the project in the action and breaks the
    // configuration cache (T-067). Plain Files serialize fine.
    val launcherFile = launcherScript.asFile
    val installedFile = layout.buildDirectory.file("jdx").get().asFile
    doLast {
        installedFile.parentFile.mkdirs()
        installedFile.writeText(launcherFile.readText())
        installedFile.setExecutable(true, false)
    }
}

// The launcher and install.sh tests run the real built artifacts. Both tiers that run
// them (`test` for the stub-JDK suites, `tier2Test` for the real-JVM end-to-end tests)
// need the distribution built first.
listOf("test", "tier2Test").forEach { taskName ->
    tasks.named<Test>(taskName) { dependsOn(installDist) }
}

dependencies {
    implementation(project(":cli"))
    testImplementation(libs.kotest.property)
}
