package com.csksy.netmirror

import android.content.Context
import android.content.SharedPreferences

object NetMirrorStorage {
    private const val PREFS_NAME = "netmirror_storage"
    private const val KEY_COOKIE = "t_hash_t"
    private const val KEY_COOKIE_TS = "t_hash_t_ts"
    private const val KEY_API_BASE = "newtv_api_base"
    private const val KEY_API_BASE_TS = "newtv_api_base_ts"
    private const val KEY_USER_TOKEN_PREFIX = "usertoken_"
    private const val KEY_OTP = "newtv_otp"
    private const val KEY_CF_PREFIX = "cf_clearance_"
    private const val KEY_CF_TS_PREFIX = "cf_clearance_ts_"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, 0)
    }

    private fun ensureInit() {
        if (!::prefs.isInitialized) {
            prefs = appContext!!.getSharedPreferences(PREFS_NAME, 0)
        }
    }

    fun saveCookie(cookie: String) {
        ensureInit()
        prefs.edit()
            .putString(KEY_COOKIE, cookie)
            .putLong(KEY_COOKIE_TS, System.currentTimeMillis())
            .apply()
    }

    fun getCookie(): Pair<String?, Long> {
        ensureInit()
        val cookie = prefs.getString(KEY_COOKIE, null)
        val ts = prefs.getLong(KEY_COOKIE_TS, 0L)
        return Pair(cookie, ts)
    }

    fun clearCookie() {
        ensureInit()
        prefs.edit().remove(KEY_COOKIE).remove(KEY_COOKIE_TS).apply()
    }

    fun saveApiBase(apiBase: String) {
        ensureInit()
        prefs.edit()
            .putString(KEY_API_BASE, apiBase)
            .putLong(KEY_API_BASE_TS, System.currentTimeMillis())
            .apply()
    }

    fun getApiBase(): Pair<String?, Long> {
        ensureInit()
        val base = prefs.getString(KEY_API_BASE, null)
        val ts = prefs.getLong(KEY_API_BASE_TS, 0L)
        return Pair(base, ts)
    }

    fun saveUserToken(ott: String, token: String) {
        ensureInit()
        prefs.edit().putString(KEY_USER_TOKEN_PREFIX + ott, token).apply()
    }

    fun getUserToken(ott: String): String? {
        ensureInit()
        return prefs.getString(KEY_USER_TOKEN_PREFIX + ott, null)
    }

    fun saveOtp(otp: String) {
        ensureInit()
        prefs.edit().putString(KEY_OTP, otp).apply()
    }

    fun getOtp(): String? {
        ensureInit()
        return prefs.getString(KEY_OTP, null)
    }

    fun saveCfCookie(host: String, cookie: String) {
        ensureInit()
        prefs.edit()
            .putString(KEY_CF_PREFIX + host, cookie)
            .putLong(KEY_CF_TS_PREFIX + host, System.currentTimeMillis())
            .apply()
    }

    fun getCfCookie(host: String): Pair<String?, Long> {
        ensureInit()
        val cookie = prefs.getString(KEY_CF_PREFIX + host, null)
        val ts = prefs.getLong(KEY_CF_TS_PREFIX + host, 0L)
        return Pair(cookie, ts)
    }

    fun clearCfCookie(host: String) {
        ensureInit()
        prefs.edit().remove(KEY_CF_PREFIX + host).remove(KEY_CF_TS_PREFIX + host).apply()
    }
}
