version = 17

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
    description = "MKVBase - Movie & Series Link Search"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://zenkai.to/assets/images/logo/favicon.png"
}
