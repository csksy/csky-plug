version = 3

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "AniKuro - Anime & Movies from anikuro.ru (sub/dub selection for anime AND movies, real episode titles, 12 servers, multi-language subtitles). v3: fixed source extraction + dub badges"
    authors = listOf("csksy")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://anikuro.ru/static/favicon/favicon-96x96.png"
    requiresResources = false
}
