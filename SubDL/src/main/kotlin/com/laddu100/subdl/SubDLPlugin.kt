package com.laddu100.subdl

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SubDLPlugin : Plugin() {
    override fun load(context: Context) {

        Miruro.context = context

        initAniDbCFBypass(context)

        initAnidapCFBypass(context)

        initSenshiCFBypass(context)
        registerMainAPI(SubDL())

        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())

        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())
    }
}
