import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val paperReaderHostSigner = providers.gradleProperty("paperReaderHostSignerSha256")
    .orElse("0".repeat(64))
    .get()
require(paperReaderHostSigner.matches(Regex("[0-9a-fA-F]{64}")))

android {
    namespace = "dev.paperreader.extensions.sample.source"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.paperreader.extensions.sample.source"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PAPERREADER_HOST_SIGNER_SHA256", "\"$paperReaderHostSigner\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.paperreader:extension-api:0.1.0")
}
