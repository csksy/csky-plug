version = 4

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "hi"
    description = "Indian TV channels through m3u playlists - livetgtv is always on with its DRM clearkeys and stream cookies, and any playlist pasted in settings opens as its own extension"
    authors = listOf("KSHITIJ8473")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Gold_HD.png"
}
