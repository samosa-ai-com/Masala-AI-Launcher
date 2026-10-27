pluginManagement {
    // The manifest check plugin. Needs AGP's API, so it's only built when :app is.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "masala"

include(":core")

// :app and :pro need the Android SDK. Without one (for example in a cloud container), only :core is
// built, so its JVM tests still run.
val localSdkDir = file("local.properties").takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("sdk.dir=") }
    ?.substringAfter("=")
val androidSdk = localSdkDir ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":pro", ":app")
} else {
    logger.lifecycle("Android SDK not found: building :core only")
}
