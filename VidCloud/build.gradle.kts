version = 5

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}

cloudstream {
    language = "en"
    description = "PW batches from Study Stark: every lecture as an episode under its own topic season in the cloudstream player, with class notes, DPP sheets and DPP quizzes one row away"
    authors = listOf("KSHITIJ8473")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Others",
    )

    iconUrl = "https://vidcloud.eu.org/images/logo.png"
}
