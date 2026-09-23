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
    description = "CloudMoviez - HD Movies & TV Shows with multi audio downloads from GDFlix Instant, GDFlix Cloud, DotFlix and PixelDrain"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://new.cloudmoviez.shop/wp-content/uploads/2026/08/cropped-IMG_20250126_140606-150x150.jpg"
}
