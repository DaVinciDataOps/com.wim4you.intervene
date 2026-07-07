// Top-level build file where you can add configuration options common to all sub-projects/modules.
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.updateDaemonJvm {
    languageVersion.set(JavaLanguageVersion.of(21))
}
