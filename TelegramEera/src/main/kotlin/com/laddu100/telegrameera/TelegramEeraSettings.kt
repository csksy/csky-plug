package com.laddu100.telegrameera

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny settings store for the provider.
 *
 * The current CloudStream3 pre-release no longer exposes the old
 * getPreferenceKeys()/PreferenceKey extension API, so the plugin keeps its
 * configuration in its own SharedPreferences file. The bridge URL defaults to
 * [TelegramEeraProvider.DEFAULT_BRIDGE] and can be overridden here
 * programmatically if needed.
 */
object TelegramEeraSettings {
    private const val PREFS_NAME = "telegrameera_prefs"
    private const val KEY_BRIDGE = "bridge_url"
    private const val KEY_TIMEOUT = "request_timeout"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    /** Custom bridge URL, or empty string to use [TelegramEeraProvider.DEFAULT_BRIDGE]. */
    fun bridgeUrl(): String = prefs?.getString(KEY_BRIDGE, null) ?: ""

    fun setBridgeUrl(url: String) {
        prefs?.edit()?.putString(KEY_BRIDGE, url)?.apply()
    }

    /** Request timeout in seconds. */
    fun requestTimeout(): String = prefs?.getString(KEY_TIMEOUT, "90") ?: "90"

    fun setRequestTimeout(seconds: String) {
        prefs?.edit()?.putString(KEY_TIMEOUT, seconds)?.apply()
    }
}
