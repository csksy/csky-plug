package com.cinemalux

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemaLuxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinemaLuxProvider())
        // LuxeDrive: handles drive.linkstore.zip/file/* links which redirect to
        // luxedrive.dad pages containing GDFlix, R2-direct, and Pixeldrain buttons.
        registerExtractorAPI(LuxeDrive())
    }
}
