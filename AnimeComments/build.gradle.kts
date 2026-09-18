version = 1

dependencies {
    // only needed at compile time, the app provides these at runtime
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
    compileOnly("com.google.android.material:material:1.4.0")
}

cloudstream {
    language = "en"
    description = "Comment on any anime episode while it plays. A small bubble appears over the player, tap anywhere to bring it back, tap it to read and post comments for the exact episode. Works over every provider including RaghavAnime. Needs the free cs-social-hub worker from the repo."
    authors = listOf("KSHITIJ8473")

    status = 3
    tvTypes = listOf(
        "Others",
    )
}
