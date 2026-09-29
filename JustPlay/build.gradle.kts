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
    description = "watch Multi source - Movies and Series"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSknDTJSN2AhrHzfxF75gr9-qG4x-JzwHRnvPDjZP2aa-93KRaXPF1_ZQdm&s=10"
}
