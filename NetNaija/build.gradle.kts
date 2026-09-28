version = 15

android {
    namespace = "com.netnaija"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "hi"
    description = "NetNaija - Multi Language Movies, Series and Live Sports. HD streaming with multiple dubs and subtitles."
    authors = listOf("raghav")
    status = 1
    requiresResources = true
    tvTypes = listOf("Movie", "TvSeries", "Live")
    iconUrl = "https://netnaija.film/favicon.ico"
}
