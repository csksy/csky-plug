package com.laddu100.raghavanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RaghavAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // Pass context to Miruro for WebView-based Cloudflare bypass
        Miruro.context = context
        // Initialize AniDB Cloudflare bypass (persistent cookie storage)
        initAniDbCFBypass(context)
        // Initialize Anidap anti-bot bypass (_amx_id cookie for chad.anidap.se)
        initAnidapCFBypass(context)
        // Initialize Senshi Cloudflare bypass (senshi.live CF challenge)
        initSenshiCFBypass(context)
        registerMainAPI(RaghavAnime())

        // Register Miruro's custom extractors
        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())

        // Register AniWaves' custom extractors
        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())
    }
}
