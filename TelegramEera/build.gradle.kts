version = 1

cloudstream {
    language = "en"
    description = "Search & stream Movies/TV/Anime from Telegram (Search Zone group + MovieEera bot) through the Eera bridge"
    authors = listOf("csksy")

    status = 1 // 1 = Ok (public) - use 3 only while testing (beta plugins are hidden in the app)
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )
    iconUrl = "https://raw.githubusercontent.com/csksy/csky-plug/main/TelegramEera/icon.png"
}
