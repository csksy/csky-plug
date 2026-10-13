version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "hi"
    description = "Live sports and TV channels from JioTV+, Jio Hotstar, Sony LIV, FanCode and Bigg Boss with a channel manager in settings"
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://i.postimg.cc/nzRJ94Vb/31-Gngoi-H5p-L.png"
}
