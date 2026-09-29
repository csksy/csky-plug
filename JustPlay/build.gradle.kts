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
    description = "Multi source movies and series on a TMDB catalog with VegaMovies HDHub4u 4KHDHub NetNaija TheMoviesFlix Multimovies and Movies4u servers, per site toggles and remote domain override"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=themoviedb.org&sz=64"
}
