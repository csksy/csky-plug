package com.laddu100.animotvslash

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimoTvSlashPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimoTvSlashProvider())
    }
}
