version = 3

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "Re:ANIME - Anime with Sub & Dub (direct CDN playback)"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    language = "en"
    iconUrl = "https://reanime.to/favicon-32x32.png"
}
