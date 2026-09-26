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
    description = "Anime with Sub and Dub - Ani-HD, HD and HD-1 servers with multiple CDNs per server, real episode titles and multi-language subtitles"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://www.google.com/s2/favicons?domain=animekai.ro&sz=%size%"
}
