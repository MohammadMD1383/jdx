dependencies {
    implementation(project(":core"))
    implementation(project(":index"))
    implementation(project(":sources"))
    implementation(project(":decompile"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
}
