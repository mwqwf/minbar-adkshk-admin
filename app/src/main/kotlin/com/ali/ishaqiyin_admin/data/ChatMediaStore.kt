package com.ali.ishaqiyin_admin.data

import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** حالة مرفق على هذا الجهاز (نمط واتساب). */
enum class MediaState { NotDownloaded, Downloading, Downloaded, Failed }

/**
 * حالة تنزيل مرفق واحد — تراقبها الفقاعة فتعرض زرّ التنزيل أو التقدّم أو
 * المشغّل.
 */
data class MediaStatus(
    val state: MediaState,
    val progress: Double = 0.0, // 0..100
    val file: File? = null,
    val error: String? = null,
) {
    val isReady: Boolean get() = state == MediaState.Downloaded && file != null

    companion object {
        val idle = MediaStatus(MediaState.NotDownloaded)
    }
}

/**
 * 📥 مخزن وسائط الدردشة — **لا تُشغَّل الوسائط بثّاً من الشبكة إطلاقاً**؛
 * تُنزَّل أولاً إلى الجهاز ثم تُفتح من الملفّ المحلّي (تماماً كواتساب).
 *
 * لماذا هذا هو الحلّ الصحيح لا مجرّد تحسين:
 *  • **يصلح «الصوتيات لا تفتح عند بعض المشرفين»**: التنزيل يتمّ عبر
 *    Firebase Storage SDK بهويّة المستخدم الموثّقة، فيمرّ عبر قواعد Storage
 *    مباشرةً. أمّا التشغيل السابق فكان بثّاً خامّاً عبر رابط التنزيل
 *    (HTTP + إعادة توجيه + طلبات Range) وهو ما يفشل على بعض الأجهزة.
 *  • **يعمل دون إنترنت**: ما نُزّل مرّة يبقى ويُشغَّل لاحقاً بلا شبكة.
 *  • **يوفّر البيانات**: لا يُنزَّل شيء إلّا بطلب المستخدم (عدا ما هو صغير
 *    جدّاً كالرسائل الصوتيّة، مثل واتساب تماماً).
 */
object ChatMediaStore {
    /** أقصى حجم يُنزَّل تلقائيّاً (رسائل صوتيّة وصور صغيرة) — كواتساب. */
    const val AUTO_DOWNLOAD_MAX_BYTES = 3L * 1024 * 1024

    private val flows = ConcurrentHashMap<String, MutableStateFlow<MediaStatus>>()
    private val running = Collections.synchronizedSet(mutableSetOf<String>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** مفتاح ثابت للمرفق: مسار Storage إن وُجد، وإلّا الرابط (للرسائل القديمة). */
    fun keyOf(att: ChatAttachment): String = att.path.ifEmpty { att.url }

    private fun mediaDir(): File {
        val dir = File(AppPrefs.context.filesDir, "chat_media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** اسم ملفّ محلّي فريد ومستقرّ: بصمة المفتاح + الامتداد الأصلي. */
    private fun localName(att: ChatAttachment): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(keyOf(att).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val name = att.name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 6) {
            name.substring(dot).lowercase()
        } else {
            extFromMime(att.contentType)
        }
        return "$digest$ext"
    }

    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "video/mp4" -> ".mp4"
        "video/quicktime" -> ".mov"
        "audio/mpeg" -> ".mp3"
        "audio/mp4" -> ".m4a"
        "audio/aac" -> ".aac"
        "audio/ogg" -> ".ogg"
        "audio/wav" -> ".wav"
        "application/pdf" -> ".pdf"
        else -> ".bin"
    }

    fun localFile(att: ChatAttachment): File = File(mediaDir(), localName(att))

    fun statusOf(att: ChatAttachment): StateFlow<MediaStatus> {
        val key = keyOf(att)
        flows[key]?.let { return it }
        val flow = MutableStateFlow(MediaStatus.idle)
        flows[key] = flow
        scope.launch { hydrate(att, flow) }
        return flow
    }

    private suspend fun hydrate(att: ChatAttachment, flow: MutableStateFlow<MediaStatus>) {
        runCatching {
            val f = localFile(att)
            if (f.exists() && f.length() > 0) {
                flow.value = MediaStatus(MediaState.Downloaded, file = f)
                return
            }
            // تنزيل تلقائي للملفّات الصغيرة جدّاً (رسائل صوتيّة/صور) إن كان مُفعّلاً.
            if (att.size in 1..AUTO_DOWNLOAD_MAX_BYTES && AppPrefs.autoDownloadMedia) {
                download(att)
            }
        }
    }

    /** تنزيل المرفق إلى الجهاز. آمن للاستدعاء المتكرّر (لا يبدأ تنزيلين). */
    suspend fun download(att: ChatAttachment): File? {
        val key = keyOf(att)
        val flow = statusOf(att) as MutableStateFlow<MediaStatus>
        if (flow.value.isReady) return flow.value.file
        if (!running.add(key)) return null
        flow.value = MediaStatus(MediaState.Downloading)

        var target: File? = null
        return try {
            val f = localFile(att)
            // ملفّ مؤقّت ثم إعادة تسمية — يمنع بقاء ملفّ نصف منزَّل يبدو سليماً.
            val tmp = File("${f.path}.part")
            if (tmp.exists()) tmp.delete()
            target = tmp

            val storage = FirebaseStorage.getInstance()
            val ref = if (att.path.isNotEmpty()) {
                storage.reference.child(att.path)
            } else {
                storage.getReferenceFromUrl(att.url)
            }

            val task = ref.getFile(tmp)
            task.addOnProgressListener { s ->
                val total = if (s.totalByteCount > 0) s.totalByteCount else att.size
                if (total > 0) {
                    flow.value = MediaStatus(
                        MediaState.Downloading,
                        progress = s.bytesTransferred.toDouble() / total * 100,
                    )
                }
            }
            task.await()

            if (f.exists()) f.delete()
            tmp.renameTo(f)
            flow.value = MediaStatus(MediaState.Downloaded, file = f)
            f
        } catch (e: Exception) {
            runCatching { if (target?.exists() == true) target.delete() }
            flow.value = MediaStatus(MediaState.Failed, error = friendlyError(e))
            null
        } finally {
            running.remove(key)
        }
    }

    private fun friendlyError(e: Throwable): String {
        val s = e.toString()
        return when {
            s.contains("object-not-found") || s.contains("404") ->
                "الملفّ لم يعد موجوداً على الخادم."
            s.contains("unauthorized") || s.contains("403") ->
                "لا تملك صلاحيّة تنزيل هذا الملفّ."
            s.contains("retry-limit") || s.contains("network") ->
                "انقطع الاتصال أثناء التنزيل."
            else -> "تعذّر التنزيل."
        }
    }

    /** حذف النسخة المحليّة (تحرير مساحة) — تعود الحالة إلى «غير منزَّل». */
    fun deleteLocal(att: ChatAttachment) {
        runCatching {
            val f = localFile(att)
            if (f.exists()) f.delete()
        }
        (statusOf(att) as MutableStateFlow<MediaStatus>).value = MediaStatus.idle
    }

    /** إجمالي حجم الوسائط المخزَّنة على الجهاز (لشاشة معلومات المجموعة). */
    fun totalBytes(): Long = runCatching {
        mediaDir().listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** مسح كلّ وسائط الدردشة المخزَّنة. */
    fun clearAll() {
        runCatching { mediaDir().listFiles()?.forEach { if (it.isFile) it.delete() } }
        flows.values.forEach { it.value = MediaStatus.idle }
    }
}
