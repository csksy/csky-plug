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
    description = "KMMovies - Download & stream Movies / TV Series (all episodes & qualities, multi-audio) from kmmovies.online"
    authors = listOf("csksy")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "hi"
    iconUrl = "https://www.google.com/s2/favicons?domain=kmmovies.online&sz=%size%"
}
