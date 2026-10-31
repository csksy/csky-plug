version = 1

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "en"
    description = "Sub and dub anime from AniDream"
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://www.google.com/s2/favicons?domain=anidream.to&sz=%size%"
}
