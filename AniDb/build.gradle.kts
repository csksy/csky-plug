
plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.laddu100.anidb"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

cloudstream {
    description = "AniDB - Watch anime with multiple sources and proper sub/dub separation."
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://anidb.app/images/fav-512.png"
    requiresResources = false
}
