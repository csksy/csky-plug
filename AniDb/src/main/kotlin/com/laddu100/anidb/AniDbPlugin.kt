
package com.laddu100.anidb

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AniDbPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniDbProvider())
    }
}
