version = 2

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Movies and series with multi-language audio and qualities from Play4u"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    iconUrl = "https://www.google.com/s2/favicons?domain=play4u.org&sz=64"
}
