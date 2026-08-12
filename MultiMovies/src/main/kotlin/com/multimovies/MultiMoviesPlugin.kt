package com.multimovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MultiMoviesProvider())
    }
}
