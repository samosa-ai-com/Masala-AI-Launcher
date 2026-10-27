import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("masala.manifest-check")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * the private prototype's samosaConfig: endpoints and keys come from an environment variable or `local.properties`
 * (both gitignored), never from Git. A checkout without them builds against inert placeholders.
 */
fun samosaConfig(name: String, placeholder: String): String =
    providers.environmentVariable(name).orNull ?: localProps.getProperty(name) ?: placeholder

android {
    namespace = "com.samosaai.masala"
    compileSdk = 36

    defaultConfig {
        // Placeholder until the real app ID is chosen (plan §8).
        applicationId = "com.samosaai.masala"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The model endpoint (plan 0.2): Samosa's OpenAI-compatible proxy by default. MASALA_LLM_*
        // override it, for example with a local LM Studio server for testing.
        val samosaApi = samosaConfig("SAMOSA_API_URL", "https://api.samosa-ai.example")
        buildConfigField("String", "MASALA_LLM_BASE_URL", "\"${samosaConfig("MASALA_LLM_BASE_URL", "$samosaApi/v1/")}\"")
        buildConfigField("String", "MASALA_LLM_API_KEY", "\"${samosaConfig("MASALA_LLM_API_KEY", "")}\"")
        buildConfigField("String", "MASALA_LLM_MODEL", "\"${samosaConfig("MASALA_LLM_MODEL", "chai-small")}\"")
        // Speech-to-text: the model endpoint unless MASALA_STT_* say otherwise. An empty model means
        // "use the first speech model the server lists".
        val llmBase = samosaConfig("MASALA_LLM_BASE_URL", "$samosaApi/v1/")
        buildConfigField("String", "MASALA_STT_BASE_URL", "\"${samosaConfig("MASALA_STT_BASE_URL", llmBase)}\"")
        buildConfigField("String", "MASALA_STT_API_KEY", "\"${samosaConfig("MASALA_STT_API_KEY", samosaConfig("MASALA_LLM_API_KEY", ""))}\"")
        buildConfigField("String", "MASALA_STT_MODEL", "\"${samosaConfig("MASALA_STT_MODEL", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

room {
    // Checked in, so every schema change shows up in review and migrations can be tested.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":pro"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.health.connect)
    implementation(libs.pdfbox.android)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.okhttp.mockwebserver)
}
