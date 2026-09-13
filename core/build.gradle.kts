kotlin {
    // Every public declaration in `core` needs an explicit visibility and return type.
    // This module is the vocabulary the whole project speaks; an accidentally-public helper
    // becomes someone else's dependency. See CONTRIBUTING.md.
    explicitApi()
}

// No production dependencies by design — see ModuleInfo.kt in this module.
// Test-only: property testing (TESTING.md §4); T-055 promotes the shared generators
// in src/test/kotlin/.../gen/ to a project-wide generator library.
dependencies {
    testImplementation(libs.kotest.property)
}
