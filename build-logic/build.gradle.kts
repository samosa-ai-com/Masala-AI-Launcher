plugins {
    `kotlin-dsl`
}

dependencies {
    // AGP itself comes from the root build's classpath at runtime.
    compileOnly(libs.android.gradle.api)

    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        register("manifestCheck") {
            id = "masala.manifest-check"
            implementationClass = "com.samosaai.masala.buildlogic.ManifestCheckPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
