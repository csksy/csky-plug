version = 2

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    description = "Live sports, PPV events and 24/7 channels - NFL, NBA, soccer, UFC, F1 and more"
    authors = listOf("csksy")

    status = 1
    tvTypes = listOf(
        "Live"
    )
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=streamcorner.st&sz=%size%"
}
