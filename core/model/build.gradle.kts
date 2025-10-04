plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    // Kotlin stdlib is added automatically; additional dependencies can be declared here when needed.
}
