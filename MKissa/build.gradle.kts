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
    description = "MKissa - anime with sub and dub tracks, real episode titles and multi server playback from the site's own CDN, Ok, StreamSB, Mp4Upload and Filemoon"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Movie")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=mkissa.to&sz=64"
}
