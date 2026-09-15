package com.csksy.anisnatch

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniSnatchPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AniSnatch())
    }
}
