package com.ali.ishaqiyin_admin.ui

import android.content.Context
import android.net.Uri
import com.ali.ishaqiyin_admin.data.guessContentType
import com.ali.ishaqiyin_admin.util.PickedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** ملفّ مشترَك بعد نسخه إلى كاش التطبيق — جاهز لرفع لا يعتمد على إذن مؤقّت. */
data class PreparedShare(
    /** الوارد الأصلي (`content://`) — يُقرأ منه نوع المحتوى فقط. */
    val source: PickedFile,
    /** النسخة المحليّة (`file://`) — هي ما يُرفع فعلاً. */
    val file: PickedFile,
    /** النسخة نفسها كي تُحذف بعد انتهاء الرفع (`deleteAfter`). */
    val cached: File,
)

/**
 * 📤 «المشاركة إلى إدارة منبر»: ملفّات تصل من تطبيقات أخرى (صوت/صورة/فيديو).
 * لا تُوجَّه تلقائياً بعد اليوم — تنتظر في [incoming] حتى يختار المستخدم
 * وجهتها من ورقة الخيارات (نمط واتساب):
 *
 *   • «إضافة درس صوتي»  ← [chooseLesson] تنقلها إلى [pending] فيلتقطها
 *     نموذج الدرس ملفّاً ملفّاً (نفس السلوك القديم تماماً).
 *   • «مجموعة الإدارة» أو «محادثة خاصّة» ← تُجهَّز بـ[prepareForChat] ثمّ
 *     ترفعها الورقة عبر ChatUploader وتستدعي [consumeIncoming].
 *
 * ⚠️ الرفع إلى الدردشة لا يقرأ الـUri الوارد مباشرةً: إذن قراءة `content://`
 * القادم مع `ACTION_SEND` مؤقّت ومربوط بحياة النشاط ولا يقبل التثبيت، ورفع
 * ChatUploader يجري في نطاق على مستوى العمليّة يتجاوز الشاشة — فلو خرج
 * المستخدم أثناء رفع ملفّ كبير سقط الإذن وفشل الرفع بصمت. لذلك نَنسخ أوّلاً.
 */
object ShareIntake {
    // نطاق مستقلّ عن أيّ شاشة: النسخ يجب ألّا يُلغى بتدوير الشاشة أو بمغادرة
    // الورقة. الاستدعاءات الراجعة تصل على الخيط الرئيسي (تنقّل وتنبيهات)،
    // والنسخ نفسه يجري على Dispatchers.IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _incoming = MutableStateFlow<List<PickedFile>>(emptyList())

    /** ملفّات وصلت بالمشاركة ولم تُختَر وجهتها بعد (تُظهر ورقة الخيارات). */
    val incoming: StateFlow<List<PickedFile>> = _incoming

    private val _pending = MutableStateFlow<List<PickedFile>>(emptyList())

    /** طابور نموذج «إضافة درس» — يمتلئ فقط بعد اختيار المستخدم. */
    val pending: StateFlow<List<PickedFile>> = _pending

    private val _preparing = MutableStateFlow(false)

    /** نسخ الوارد إلى الكاش جارٍ — تعرض الورقة «جارٍ التجهيز…» وتُعطّل خياراتها. */
    val preparing: StateFlow<Boolean> = _preparing

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

    /**
     * ينسخ ملفّات المشاركة إلى كاش التطبيق قبل تسليمها لـChatUploader.
     * [onReady] لا تُستدعى إلّا بنسخة واحدة على الأقلّ، و[onFailure] برسالة
     * عربية حين يتعذّر تجهيز أيّ ملفّ. كلتاهما على الخيط الرئيسي.
     */
    fun prepareForChat(
        context: Context,
        files: List<PickedFile>,
        onReady: (List<PreparedShare>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (files.isEmpty() || _preparing.value) return
        val app = context.applicationContext
        _preparing.value = true
        scope.launch {
            // أيّ عطب غير متوقّع يجب أن يُرجِع [preparing] إلى false، وإلّا بقيت
            // الورقة معطّلة إلى الأبد. (النطاق لا يُلغى، فلا خطر ابتلاع إلغاء.)
            val prepared = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "shared_intake").apply { mkdirs() }
                    // نسخ سابقة لم يكتمل رفعها (قُتلت العمليّة مثلاً) تُحذف بعد
                    // يوم كي لا يتضخّم الكاش.
                    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                    runCatching {
                        dir.listFiles()?.forEach { old ->
                            if (old.lastModified() < cutoff) old.delete()
                        }
                    }
                    files.mapNotNull { picked ->
                        val safe = picked.name
                            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                            .takeLast(80)
                        val target = File(dir, "${System.nanoTime()}_$safe")
                        runCatching {
                            app.contentResolver.openInputStream(picked.uri)?.use { input ->
                                target.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                            } ?: error("تعذّرت قراءة الملفّ المشترَك.")
                            PreparedShare(
                                source = picked,
                                file = picked.copy(
                                    uri = Uri.fromFile(target),
                                    size = target.length(),
                                ),
                                cached = target,
                            )
                        }.getOrElse {
                            runCatching { target.delete() }
                            null
                        }
                    }
                }
            }.getOrDefault(emptyList())
            _preparing.value = false
            if (prepared.isEmpty()) {
                onFailure("تعذّر تجهيز الملفّ المشترَك للإرسال. أعد المشاركة من التطبيق المصدر.")
            } else {
                onReady(prepared)
            }
        }
    }

    /** عند صرف ورقة الخيارات دون اختيار وجهة. */
    fun clearIncoming() {
        _incoming.value = emptyList()
    }

    /**
     * بعد تسليم دفعة إلى الدردشة: يزيل ما جرت محاولته فقط — قد تصل مشاركة
     * جديدة أثناء التجهيز، ومسح القائمة كلّها كان يبتلعها.
     */
    fun consumeIncoming(files: List<PickedFile>) {
        if (files.isEmpty()) return
        val done = files.map { it.uri.toString() }.toSet()
        _incoming.value = _incoming.value.filter { it.uri.toString() !in done }
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
