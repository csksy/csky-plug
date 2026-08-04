import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("kotlin-android")
}

val androidSdk = 34
val javaVersion = JavaVersion.VERSION_1_8

android {
    namespace = "com.laddu100.kyren"
    compileSdk = androidSdk

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "Kyren - Anime streaming"
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://kyren.moe/icon.png"
    requiresResources = false
}
