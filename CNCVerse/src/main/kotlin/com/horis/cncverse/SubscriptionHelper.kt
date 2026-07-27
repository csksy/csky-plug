package com.horis.cncverse

import android.content.Context

object SubscriptionHelper {

    @Volatile
    private var popupShown: Boolean = false

    fun getPopupShown(): Boolean = popupShown

    fun setPopupShown(value: Boolean) {
        popupShown = value
    }

    fun isSubscribed(ctx: Context?): Boolean = true

    fun showPopupIfNeeded(ctx: Context?) {
        popupShown = true
    }
}
