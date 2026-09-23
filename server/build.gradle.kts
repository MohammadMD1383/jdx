dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    // Generating family for the socket tests (TESTING.md §2): the framing
    // round-trip property in DaemonServerTest.
    testImplementation(libs.kotest.property)
}
