package com.example

import android.content.Context

/**
 * Process-wide lightweight application context reference used solely for safe runtime checks
 * (such as verifying FirebaseApp initialization state) without leaking Activity instances.
 */
object AppContextHolder {
    @Volatile
    private var appContext: Context? = null

    fun set(context: Context) {
        appContext = context.applicationContext
    }

    fun getOrNull(): Context? = appContext
}
