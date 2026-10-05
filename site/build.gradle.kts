// # Website generator (GitHub Pages)
//
// `:site` is a build-time tool, never a product module: nothing depends on it and it
// never reaches the fat jar. It turns the repository's own Markdown (README, docs/,
// docs/guide/) plus the *real output of the freshly built jdx* into the static site
// published by `.github/workflows/pages.yml`. That is the whole anti-drift design:
//
//   - command reference pages are generated from `jdx <cmd> --help` / `jdx help --json`,
//   - every ```console exec``` block in a doc is executed and its real output embedded,
//   - inline `jdx <command> --flag` mentions are checked against the real help text,
//   - every internal link and #anchor is resolved, and the build fails on any miss.
//
// So docs cannot silently disagree with the binary: the site build goes red instead.
// See site/AGENTS.md for the page manifest and how to add a page.
//
//   ./gradlew :site:buildSite            -> site/build/site (needs network once: gson fetch)
//   ./gradlew :site:serveSite            -> http://localhost:8000/jdx/ (preview)

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

// Default public URL of the site. Override with -Psite.baseUrl=https://jdx.example.dev/ for a
// custom domain (the generator then also writes a CNAME file).
val defaultBaseUrl = "https://mohammadmd1383.github.io/jdx/"

val siteOutputDir = layout.buildDirectory.dir("site")
val siteWorkDir = layout.buildDirectory.dir("site-work")

// Config-cache rule (T-067): only plain values cross into task actions.
val repoRootPath: String = rootDir.absolutePath
val launcherPath: String = project(":app").layout.buildDirectory.file("jdx").get().asFile.absolutePath

fun optionalProperty(name: String): String? =
    (findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

tasks.register<JavaExec>("buildSite") {
    group = "documentation"
    description = "Generates the GitHub Pages website into site/build/site from docs + the real jdx CLI."
    dependsOn(":app:installDist")
    mainClass.set("dev.jdx.site.SiteMainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    // Never up-to-date: the output depends on git history, the release list and the
    // network-fetched fixture, none of which Gradle can fingerprint cheaply.
    outputs.upToDateWhen { false }
    val arguments = mutableListOf(
        "--repo", repoRootPath,
        "--out", siteOutputDir.get().asFile.absolutePath,
        "--work", siteWorkDir.get().asFile.absolutePath,
        "--jdx", launcherPath,
        "--base-url", optionalProperty("site.baseUrl") ?: defaultBaseUrl,
    )
    optionalProperty("site.version")?.let { arguments += listOf("--version", it) }
    optionalProperty("site.releases")?.let { arguments += listOf("--releases", it) }
    optionalProperty("site.googleVerification")?.let { arguments += listOf("--google-verification", it) }
    optionalProperty("site.bingVerification")?.let { arguments += listOf("--bing-verification", it) }
    args(arguments)
}

tasks.register<JavaExec>("serveSite") {
    group = "documentation"
    description = "Serves site/build/site locally (run :site:buildSite first). -Psite.port=8000"
    mainClass.set("dev.jdx.site.SiteMainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    args(
        "--serve", siteOutputDir.get().asFile.absolutePath,
        "--base-url", optionalProperty("site.baseUrl") ?: defaultBaseUrl,
        "--port", optionalProperty("site.port") ?: "8000",
    )
}

// The repository-reading suites (manifest coverage, full-site build over the real docs
// with a stub CLI) need the repo root; hand it in so tests never guess paths.
listOf("test", "integrationTest").forEach { taskName ->
    tasks.named<Test>(taskName) {
        systemProperty("jdx.repoRoot", repoRootPath)
    }
}
