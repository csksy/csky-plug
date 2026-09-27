version = 11

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
    description = "Rare Toons India - Hindi, Tamil & Telugu anime/cartoons. All sources: WatchMultiQuality HLS, HubCloud, WatchNow, DLBeta PixelDrain, Mega, GOFILE-ZIP, ZIP-CLOUD with multi-audio support"
    authors = listOf("raghav,phisher,csksy")
    status = 1
    tvTypes = listOf("Anime", "Cartoon", "TvSeries", "Movie")
    iconUrl = "https://www.rareanimes.mov/wp-content/uploads/2023/11/cropped-Rare-Animes-India.png"
}
