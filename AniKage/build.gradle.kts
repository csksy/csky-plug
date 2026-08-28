version = 3

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    description = "AniKage - Anime & Movies from anikage.cc (sub/dub selection for anime AND movies, real episode titles, all servers, multi-language subtitles). Sources: MegaPlay(Koto)/VidTube/VibeNeko/StreamHG/Earnvids + Kiwi/Uwu/Megg/Dib/Wave via prox with one-time verification"
    authors = listOf("csksy")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://anikage.cc/favicon-96x96.png"
    requiresResources = false
}
