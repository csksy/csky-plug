package com.laddu100.telegrameera

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TelegramEeraPlugin : Plugin() {
    override fun load(context: Context) {
        TelegramEeraSettings.init(context)
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(TelegramEeraProvider())
    }
}
