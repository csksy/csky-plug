version = 6

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    description = "cartoons anime and movies from toonworld4all with hubcloud gdflix filepress and mega downloads"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Cartoon", "Anime", "Movie", "TvSeries", "AnimeMovie")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=toonworld4all.me&sz=64"
}
