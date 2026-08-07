package com.bollyflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BollyFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BollyFlixProvider())
        // GDFlix family: each registered mainUrl pattern lets loadExtractor
        // dispatch a gdflix.* file page to our GDFlix extractor which parses
        // every download button (Cloud R2, Instant, Drivebot, Fast Cloud, GoFile).
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlix())
        // fastdlserver links 302-redirect to a gdflix.* page; this extractor
        // follows the redirect then hands off to the GDFlix extractor.
        registerExtractorAPI(FastDlServer())
    }
}
