version = 2

dependencies {
    // websocket client for the room relay, pure jvm so it loads from the plugin dex
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // only needed at compile time, the app provides these at runtime
    compileOnly("androidx.navigation:navigation-fragment-ktx:2.7.7")
    compileOnly("com.jaredrummler:colorpicker:1.1.0")
    compileOnly("com.google.android.material:material:1.4.0")
}

cloudstream {
    language = "en"
    description = "Movie, anime and entertainment trivia. Play solo offline or open a room for up to 5 friends with live scores. Multiplayer connects to the repo's shared cs-social-hub server automatically."
    authors = listOf("KSHITIJ8473")

    status = 3
    tvTypes = listOf(
        "Others",
    )
}
