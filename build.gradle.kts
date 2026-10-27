buildscript {
    // AGP goes on the root classpath so :app and :pro share one copy. It's only resolved when the
    // Android modules are part of the build (see settings.gradle.kts).
    if (rootProject.subprojects.any { it.path == ":app" }) {
        dependencies {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// The root project has no check task of its own; this one runs build-logic's tests with everyone else's.
if (subprojects.any { it.path == ":app" }) {
    tasks.register("check") {
        dependsOn(gradle.includedBuild("build-logic").task(":test"))
    }
}
