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
    private const val KEY_AUDIO_SPEED = "chat_audio_speed_v1"

    // آخر قسم استُعمل في «إضافة درس» — أغلب الدروس تُضاف إلى القسم نفسه
    // تباعاً، فإعادة اختياره تلقائياً توفّر نقرتين في كلّ درس.
    private const val KEY_LAST_ADD_CATEGORY = "last_add_category_v1"
    private const val KEY_LAST_ADD_SUBCATEGORY = "last_add_subcategory_v1"

    // آخر فلتر قسم في «التعديل والبحث» — يبقى بين الجلسات.
    private const val KEY_LAST_MANAGE_CATEGORY = "last_manage_category_v1"
    private const val KEY_LAST_MANAGE_SUBCATEGORY = "last_manage_subcategory_v1"

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

    /** سرعة تشغيل الصوتيّات المختارة (1× / 1.5× / 2×) — تُستعاد بين الجلسات. */
    var audioSpeed: Float
        get() = prefs.getFloat(KEY_AUDIO_SPEED, 1f)
        set(value) = prefs.edit().putFloat(KEY_AUDIO_SPEED, value).apply()

    /**
     * هل طُلب إذن الصور (لشريحة «إرفاق لقطة الشاشة الأخيرة»)؟ يُطلب مرّة
     * واحدة فقط في عمر التطبيق — الرفض لا يُتبَع بإلحاح، والتفعيل لاحقاً
     * يكون من إعدادات النظام.
     */
    var mediaPermissionAsked: Boolean
        get() = prefs.getBoolean("media_permission_asked_v1", false)
        set(value) = prefs.edit().putBoolean("media_permission_asked_v1", value).apply()

    /** معرّف محفوظ: النصّ الفارغ يُعامَل كغياب اختيار لا كمعرّف صالح. */
    private fun readId(key: String): String? =
        prefs.getString(key, null)?.takeIf { it.isNotEmpty() }

    private fun writeId(key: String, value: String?) {
        val editor = prefs.edit()
        if (value.isNullOrEmpty()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    /** آخر قسم رئيسي اختِير في نموذج «إضافة درس». */
    var lastAddCategoryId: String?
        get() = readId(KEY_LAST_ADD_CATEGORY)
        set(value) = writeId(KEY_LAST_ADD_CATEGORY, value)

    /** آخر قسم فرعي اختِير في نموذج «إضافة درس». */
    var lastAddSubcategoryId: String?
        get() = readId(KEY_LAST_ADD_SUBCATEGORY)
        set(value) = writeId(KEY_LAST_ADD_SUBCATEGORY, value)

    /** آخر فلتر قسم رئيسي في شاشة «التعديل والبحث». */
    var lastManageCategoryId: String?
        get() = readId(KEY_LAST_MANAGE_CATEGORY)
        set(value) = writeId(KEY_LAST_MANAGE_CATEGORY, value)

    /** آخر فلتر قسم فرعي في شاشة «التعديل والبحث». */
    var lastManageSubcategoryId: String?
        get() = readId(KEY_LAST_MANAGE_SUBCATEGORY)
        set(value) = writeId(KEY_LAST_MANAGE_SUBCATEGORY, value)

    // ── تقليل كتابات/نداءات الشبكة المتكرّرة عند كلّ إقلاع ──

    /** بصمة آخر رمز جهاز كُتب فعلاً في Firestore (رمز+دور+كتم). */
    var lastDeviceTokenSig: String?
        get() = readId("device_token_sig_v1")
        set(value) = writeId("device_token_sig_v1", value)

    /** لحظة آخر كتابة لرمز الجهاز — لإعادة الكتابة الدوريّة رغم ثبات البصمة. */
    var lastDeviceTokenWriteMs: Long
        get() = prefs.getLong("device_token_write_ms_v1", 0L)
        set(value) = prefs.edit().putLong("device_token_write_ms_v1", value).apply()

    /**
     * 📝 مسودة نموذج «إضافة درس» (JSON): الحقول النصّية والاختيارات وحدها —
     * لا ملفّات الصوت. تُكتب عند تغيّر الحقول (بمهلة قصيرة) وتُمسح عند نجاح
     * الإدراج أو التفريغ اليدويّ، فإغلاق التطبيق أثناء التعبئة لا يضيّعها.
     */
    var addLessonDraft: String?
        get() = readId("add_lesson_draft_v1")
        set(value) = writeId("add_lesson_draft_v1", value)

    /** أُلغي اشتراك موضوع FCM القديم على هذا الجهاز (يكفي مرّة لكلّ تثبيت). */
    var legacyTopicUnsubscribed: Boolean
        get() = prefs.getBoolean("legacy_topic_unsubscribed_v1", false)
        set(value) = prefs.edit().putBoolean("legacy_topic_unsubscribed_v1", value).apply()

    /** بصمة آخر عضويّة كُتبت فعلاً (upsertSelf) — اكتب فقط إن تغيّرت القيمة. */
    var lastChatMemberSig: String?
        get() = readId("chat_member_sig_v1")
        set(value) = writeId("chat_member_sig_v1", value)

    /** لحظة آخر كتابة عضويّة كاملة — لإعادة الكتابة الدوريّة رغم ثبات البصمة. */
    var lastChatMemberWriteMs: Long
        get() = prefs.getLong("chat_member_write_ms_v1", 0L)
        set(value) = prefs.edit().putLong("chat_member_write_ms_v1", value).apply()

    /**
     * آخر قيمة `chatMuted` كُتبت فعلاً في وثيقة رمز الجهاز
     * ("" = لم تُكتب بعد) — اكتب فقط إن تغيّرت القيمة.
     */
    var lastChatMutedWritten: String?
        get() = readId("chat_muted_written_v1")
        set(value) = writeId("chat_muted_written_v1", value)
}
