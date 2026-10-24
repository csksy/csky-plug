package com.atvp

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeTVPlusPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeTVPlusProvider())
    }
}
