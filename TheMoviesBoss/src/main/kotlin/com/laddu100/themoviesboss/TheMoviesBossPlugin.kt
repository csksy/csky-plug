package com.laddu100.themoviesboss

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TheMoviesBossPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TheMoviesBoss())
        registerExtractorAPI(TmbCloudExtractor())
        registerExtractorAPI(HubCloudExtractor())
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(GofileExtractor())
        registerExtractorAPI(GDFlixExtractor())
        registerExtractorAPI(DriveleechExtractor())
        registerExtractorAPI(DriveseedExtractor())
    }
}
