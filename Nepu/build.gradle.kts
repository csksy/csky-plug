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
    language = "en"
    description = "Movies, shows, cartoons and anime in HD with sub and dub tracks, streamed through Nepu's own HLS relay"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")
    iconUrl = "https://www.google.com/s2/favicons?domain=nepu.io&sz=%size%"
}
