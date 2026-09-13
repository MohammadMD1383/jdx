dependencies {
    implementation(project(":core"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.util)
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.sqlite.jdbc)
}
