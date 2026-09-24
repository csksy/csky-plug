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
    description = "Multi site movies and tv aggregator with VegaMovies HDHub4u 4KHDHub MovieBox NetNaija TheMoviesFlix Multimovies and Movies4u sources on a TMDB catalog with per site toggles and firebase domain override"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=64"
}
