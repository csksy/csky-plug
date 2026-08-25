// use an integer for version numbers
// v21: ported NATIVE decryption algorithm (b64->reverse->swap->b64->AES)
//      from deployed PlayZTV into LIVETVCryptoUtils so the live API's
//      events.txt / categories.txt payloads decrypt correctly. Also
//      hardened LIVETVPlugin so settings always shows providers even
//      if the initial fetch at plugin load failed.
version = 21

android {
    namespace = "com.laddu100"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        buildConfigField("String", "FB_API_KEY", "\"AIzaSyDKRqLlbaZBIpHzLBiQTUrJqr3gN-nDWWc\"")
        buildConfigField("String", "FB_APP_ID", "\"1:516859456626:android:12a75869902c4f8a6826eb\"")
        buildConfigField("String", "FB_PROJECT_NUMBER", "\"516859456626\"")
    }
}
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Watch LIVE TV channels & sports via LIVE TV"
    authors = listOf("raghav")

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
    requiresResources = true

    iconUrl = "https://png.pngtree.com/png-vector/20191026/ourmid/pngtree-live-icon-design-template-vector-isolated-illustration-png-image_1874482.jpg"
}
