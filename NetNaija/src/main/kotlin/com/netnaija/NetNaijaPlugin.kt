package com.netnaija

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.netnaija.settings.NetNaijaSettingsFragment

@CloudstreamPlugin
class NetNaijaPlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("netnaija", 0)
        registerMainAPI(NetNaija(sharedPref))
        this.openSettings = { ctx ->
            val activity = getActivity(ctx)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    NetNaijaSettingsFragment(this, sharedPref).show(
                        activity.supportFragmentManager,
                        "NetNaijaSettings"
                    )
                } catch (e: Throwable) {
                    // A dead activity can reject the fragment transaction; the
                    // settings simply stay closed until the next open.
                }
            }
        }
    }

    // Plugin settings open from arbitrary view contexts, so the activity is
    // recovered by walking the context wrapper chain.
    private fun getActivity(context: Context): AppCompatActivity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is AppCompatActivity) return current
            current = current.baseContext
        }
        return null
    }
}
