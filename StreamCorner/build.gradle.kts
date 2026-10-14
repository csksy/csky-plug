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
    language = "en"
    description = "Live sports and 24/7 channels - soccer, MLB, NFL, NBA, PPV events, SlingTV, Peacock and Paramount+"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=streamcorner.st&sz=%size%"
}
