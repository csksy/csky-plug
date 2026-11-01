version = 4

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "en"
    description = "Cno.to - movies and series in up to 4K from the site's own servers with subtitles in 40+ languages, plus multi audio anime with every audio and subtitle track in the stream"
    authors = listOf("KSHITIJ8473")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://cno.to/icons/icon-192.png"
}
