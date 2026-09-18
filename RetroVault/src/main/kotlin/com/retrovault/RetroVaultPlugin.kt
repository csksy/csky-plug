package com.retrovault

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class RetroVaultPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(RetroVault())
    }
}
