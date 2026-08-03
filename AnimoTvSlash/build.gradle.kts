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
    language = "en"
    description = "Watch Anime Online Free in HD (Sub & Dub)"
    authors = listOf("KSHITIJ8473")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://animotvslash.org/wp-content/uploads/2025/10/Cropped_Image__2_-removebg-preview-1.png"
}
