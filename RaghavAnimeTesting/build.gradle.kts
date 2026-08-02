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
    description = "RaghavAnimeTesting - Aggregated Anime Plugin (SUB/DUB) - Testing Build"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://www.pngall.com/wp-content/uploads/13/Anime-Logo-PNG-Images.png"
}
