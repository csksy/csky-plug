version = 4

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "AniKuro - Anime & Movies from anikuro.ru (sub/dub selection for anime AND movies, real episode titles, 12 servers, multi-language subtitles). v4: fixed no-links (URL double-prefix) + DUB badges via aliased AniList query"
    authors = listOf("csksy")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://anikuro.ru/static/favicon/favicon-96x96.png"
    requiresResources = false
}
