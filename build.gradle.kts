plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so org.jetbrains.kotlin.android isn't applied.
    // These compiler plugins pull in the Kotlin Gradle plugins BOM, which aligns the Kotlin compiler
    // AGP uses to the `kotlin` version in gradle/libs.versions.toml.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
