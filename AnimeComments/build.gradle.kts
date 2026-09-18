version = 3

dependencies {
    // only needed at compile time, the app provides these at runtime
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
    compileOnly("com.google.android.material:material:1.4.0")
}

cloudstream {
    language = "en"
    description = "Comment on any anime episode while it plays. A comment button with a proper chat bubble icon floats over the player - tap it to read and post comments for the exact episode you are watching. Threads are shared across all users, works over every provider including RaghavAnime."
    authors = listOf("KSHITIJ8473")

    status = 3
    tvTypes = listOf(
        "Others",
    )
}
