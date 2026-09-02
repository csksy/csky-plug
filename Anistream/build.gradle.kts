version = 3

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "Anistream - Anime with Sub & Dub, 10 servers (Neko, Kiwi, Mimi, Yuki, Minky, Zen, Hawk, Beep, Sora, Wave), real episode titles, softsub/hardsub tags, multi-language subtitles. v2: Cloudflare bypass + DNS-over-HTTPS fallback + cookie session + real error messages"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://anistream.one/favicon.svg"
}
