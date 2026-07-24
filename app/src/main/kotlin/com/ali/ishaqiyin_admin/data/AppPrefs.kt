package com.ali.ishaqiyin_admin.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** تفضيلات محليّة بسيطة (بديل shared_preferences). تُهيَّأ من AdminApplication. */
@SuppressLint("StaticFieldLeak")
object AppPrefs {
    private const val FILE = "minbar_admin_prefs"
    private const val KEY_CHAT_MUTED = "admin_chat_muted_v1"
    private const val KEY_AUTO_DOWNLOAD = "chat_auto_download_v1"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** مجلّد وسائط الدردشة المخزَّنة على الجهاز. */
    val context: Context get() = appContext

    var chatMuted: Boolean
        get() = prefs.getBoolean(KEY_CHAT_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_MUTED, value).apply()

    var autoDownloadMedia: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()
}
