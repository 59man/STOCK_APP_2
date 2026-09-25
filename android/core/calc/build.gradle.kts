plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(libs.junit)
    // Reads the shared web/android parity fixture in test-fixtures/.
    testImplementation(libs.kotlinx.serialization.json)
}
