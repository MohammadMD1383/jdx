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

    tasks.withType<Test>().configureEach {
        // Tier separation (docs/TESTING.md §2). Tier 1 (`test`) must stay under 30 s, so the
        // slow families are tagged and excluded here. T-053 builds the full tier machinery
        // (`check`, `soak`, `bench`, `mutationTest`) on top of this.
        useJUnitPlatform {
            excludeTags("soak", "bench")
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }
    }
}
