version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
}

cloudstream {
    language = "hi"
    description = "TG TV - 1100+ Indian live TV channels from JioTV: Star Sports, Star Gold, Sony, Colors, Zee, movies, news, kids, devotional and every regional language the site carries, played with its own DRM keys"
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
