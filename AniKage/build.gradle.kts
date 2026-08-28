version = 2

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "AniKage - Anime & Movies from anikage.cc (sub/dub selection for anime AND movies, real episode titles, all servers, multi-language subtitles). Sources: MegaPlay(Koto)/VidTube/VibeNeko/StreamHG/Earnvids with auto-recovery"
    authors = listOf("csksy")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://anikage.cc/favicon-96x96.png"
    requiresResources = false
}
