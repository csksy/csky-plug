package com.laddu100.raghavanimesubdl

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RaghavAnimeSubDLPlugin : Plugin() {
    override fun load(context: Context) {

        Miruro.context = context

        initAniDbCFBypass(context)

        initAnidapCFBypass(context)

        initSenshiCFBypass(context)
        registerMainAPI(RaghavAnimeSubDL())

        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())

        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())
    }
}
