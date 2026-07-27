package com.horis.cncverse

import android.content.Context
import android.content.SharedPreferences

object NetflixMirrorStorage {

    private const val PREFS_NAME = "NetflixMirrorPrefs"

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        this.context = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveCookie(cookie: String) {
        prefs.edit()
            .putString("nf_cookie", cookie)
            .putLong("nf_cookie_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getCookie(): Pair<String?, Long> {
        val cookie = prefs.getString("nf_cookie", null)
        val ts = prefs.getLong("nf_cookie_timestamp", 0L)
        return cookie to ts
    }

    fun clearCookie() {
        prefs.edit()
            .remove("nf_cookie")
            .remove("nf_cookie_timestamp")
            .apply()
    }

    fun saveUserToken(ott: String, token: String) {
        prefs.edit()
            .putString("usertoken_$ott", token)
            .putLong("usertoken_timestamp_$ott", System.currentTimeMillis())
            .apply()
    }

    fun getUserToken(ott: String): Pair<String?, Long> {
        val token = prefs.getString("usertoken_$ott", null)
        val ts = prefs.getLong("usertoken_timestamp_$ott", 0L)
        return token to ts
    }

    fun saveApiBase(apiBase: String) {
        prefs.edit()
            .putString("newtv_api_base", apiBase)
            .putLong("newtv_api_base_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getApiBase(): Pair<String?, Long> {
        val base = prefs.getString("newtv_api_base", null)
        val ts = prefs.getLong("newtv_api_base_timestamp", 0L)
        return base to ts
    }

    fun saveOtp(otp: String) {
        prefs.edit().putString("newtv_otp", otp).apply()
    }

    fun getOtp(): String? = prefs.getString("newtv_otp", null)

    fun saveCfCookie(cookie: String) {
        prefs.edit()
            .putString("cf_clearance", cookie)
            .putLong("cf_clearance_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getCfCookie(): Pair<String?, Long> {
        val cookie = prefs.getString("cf_clearance", null)
        val ts = prefs.getLong("cf_clearance_timestamp", 0L)
        return cookie to ts
    }

    fun clearCfCookie() {
        prefs.edit()
            .remove("cf_clearance")
            .remove("cf_clearance_timestamp")
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
