package com.laddu100

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TheMoviesFlixPlugin : Plugin() {
    override fun load() {
        registerMainAPI(TheMoviesFlix())
        registerExtractorAPI(FastDlExtractor())
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(VegaDriveExtractor())
        registerExtractorAPI(FilePressExtractor())
    }
}
