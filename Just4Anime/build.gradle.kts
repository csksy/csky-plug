version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "just4anime.online - Anime with Sub / Dub / Hardsub separation. All 12 site servers probed per episode (MegaPlay, Senshi, AllAnime, AniDB, MegaVid, AniNeko x4, ZokoAnime, AnimeGG, AnimixPlay), real TMDB episode titles, filler tags, multi-language soft subtitles, Sub + Dub episode tabs. Dead servers auto-recover - nothing is skipped."
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://just4anime.online/favicon.ico"
}
