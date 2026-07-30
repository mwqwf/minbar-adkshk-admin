package com.ali.ishaqiyin_admin.ui

import android.content.Context
import com.ali.ishaqiyin_admin.data.guessContentType
import com.ali.ishaqiyin_admin.util.PickedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 📤 «المشاركة إلى إدارة منبر»: ملفّات تصل من تطبيقات أخرى (صوت/صورة/فيديو
 * /مستند). لا تُوجَّه تلقائياً بعد اليوم — تنتظر في [incoming] حتى يختار
 * المستخدم وجهتها من ورقة الخيارات (نمط واتساب):
 *
 *   • «إضافة درس صوتي»  ← [chooseLesson] تنقلها إلى [pending] فيلتقطها
 *     نموذج الدرس ملفّاً ملفّاً (نفس السلوك القديم تماماً).
 *   • «مجموعة الإدارة» أو «محادثة خاصّة» ← ترفعها الورقة عبر ChatUploader
 *     ثمّ تستدعي [clearIncoming].
 *
 * لا نسخ إلى cache: الرفع يقرأ الـUri مباشرةً.
 */
object ShareIntake {
    private val _incoming = MutableStateFlow<List<PickedFile>>(emptyList())

    /** ملفّات وصلت بالمشاركة ولم تُختَر وجهتها بعد (تُظهر ورقة الخيارات). */
    val incoming: StateFlow<List<PickedFile>> = _incoming

    private val _pending = MutableStateFlow<List<PickedFile>>(emptyList())

    /** طابور نموذج «إضافة درس» — يمتلئ فقط بعد اختيار المستخدم. */
    val pending: StateFlow<List<PickedFile>> = _pending

    fun add(files: List<PickedFile>) {
        if (files.isEmpty()) return
        val known = (_incoming.value + _pending.value).map { it.uri.toString() }.toSet()
        _incoming.value = _incoming.value + files.filter { it.uri.toString() !in known }
    }

    /** «إضافة درس صوتي»: تنقل الوارد إلى طابور النموذج. */
    fun chooseLesson() {
        val files = _incoming.value
        if (files.isEmpty()) return
        _incoming.value = emptyList()
        val known = _pending.value.map { it.uri.toString() }.toSet()
        _pending.value = _pending.value + files.filter { it.uri.toString() !in known }
    }

    /** بعد إرسال الوارد إلى الدردشة، أو عند صرف ورقة الخيارات. */
    fun clearIncoming() {
        _incoming.value = emptyList()
    }

    fun peek(): PickedFile? = _pending.value.firstOrNull()

    /** يؤكّد انتهاء النموذج من الملفّ الأوّل (رُفع أو أُلغي). */
    fun consumeFirst() {
        _pending.value = _pending.value.drop(1)
    }

    fun clear() {
        _incoming.value = emptyList()
        _pending.value = emptyList()
    }
}

/**
 * نوع محتوى الملفّ المشترَك: من الامتداد أوّلاً (أدقّ لأسماء التنزيلات)، ثمّ
 * من ContentResolver حين يعجز الامتداد — فبعض التطبيقات تشارك بأسماء بلا
 * امتداد، ورفعها كـoctet-stream يجعل الفقاعة «ملفّاً» لا صوتاً.
 */
fun Context.shareContentType(file: PickedFile): String {
    val byName = guessContentType(file.name)
    if (byName != "application/octet-stream") return byName
    return contentResolver.getType(file.uri)?.takeIf { it.isNotBlank() } ?: byName
}
