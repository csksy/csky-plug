version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    description = "Anime with Sub and Dub - Multiple sources including Hindi audio"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=anisnatch.to&sz=%size%"
}
