package com.laddu100.onlytesting

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.laddu100.onlytesting.settings.SettingsFragment

@CloudstreamPlugin
class OnlyTestingPlugin : Plugin() {
    override fun load(context: Context) {
        Miruro.context = context
        initAniDbCFBypass(context)
        initAnidapCFBypass(context)
        initSenshiCFBypass(context)
        EnmaDecryptor.setContext(context)
        EnmaDecryptor.startInit()
        registerMainAPI(OnlyTesting())
        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())
        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())

        this.openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                val frag = SettingsFragment()
                frag.show(activity.supportFragmentManager, "RaghavAnimeSettings")
            }
        }
    }
}
