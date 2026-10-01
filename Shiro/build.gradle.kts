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
    description = "Anime with Sub, Dub and Hardsub - multiple servers, real episode titles, multi-language subtitles"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=shiro.so&sz=%size%"
}
