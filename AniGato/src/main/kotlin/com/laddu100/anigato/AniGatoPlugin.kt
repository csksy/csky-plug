package com.laddu100.anigato

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniGatoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniGatoProvider())
        registerMainAPI(AniGatoCinemaProvider())
    }
}
