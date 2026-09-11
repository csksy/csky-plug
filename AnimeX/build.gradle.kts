version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    description = "AnimeX - Anime with Sub & Dub, all providers incl hardsub"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://animex.one/assets/logo.png"
}
