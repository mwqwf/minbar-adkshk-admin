package com.ali.ishaqiyin_admin.data

import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** حالة مرفق على هذا الجهاز (نمط واتساب). */
enum class MediaState { NotDownloaded, Downloading, Downloaded, Failed }

/**
 * حالة تنزيل مرفق واحد — تراقبها الفقاعة فتعرض زرّ التنزيل أو التقدّم أو
 * المشغّل. [waitingForNetwork] تعني: الملفّ الجزئي محفوظ وسنُكمل تلقائياً
 * فور عودة الاتصال (لا يفقد المستخدم ما نُزّل).
 */
data class MediaStatus(
    val state: MediaState,
    val progress: Double = 0.0, // 0..100
    val file: File? = null,
    val error: String? = null,
    val waitingForNetwork: Boolean = false,
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
 * **الإصلاح الجذري لمشكلة «الصوتيات لا تُفتح عند أغلب المشرفين»:**
 * التنزيل يتمّ الآن عبر **رابط التنزيل المُوقَّع** (`att.url` وفيه رمز
 * الوصول) بطلب HTTP عادي يدعم الاستئناف، وليس عبر Firebase Storage SDK.
 * السبب: قاعدة مجلّد `admin_chat` في storage.rules تمنح القراءة عبر
 * `isSupervisor()` التي تنفّذ نداءً **عابراً للخدمات** إلى Firestore
 * (`firestore.exists/get` على `dashboard_admins`). هذا النداء ينجح للمالك
 * (لأن `isOwner()` فحص رمز محض بلا Firestore) ويفشل أو يتأخّر لكثير من
 * المشرفين (صلاحية IAM لحساب خدمة التخزين، تأخّر الانتشار، اختلاف حالة
 * الأحرف في معرّف الوثيقة، أو ضعف الشبكة أثناء تقييم القاعدة) — فينتهي
 * الأمر بـ403 وبرسالة «تعذّر التنزيل» عندهم وحدهم.
 * رابط التنزيل المُوقَّع لا يمرّ بتلك القاعدة، والحماية تبقى قائمة لأن
 * الرابط نفسه لا يُقرأ إلا من وثيقة رسالة تحميها قواعد Firestore.
 * وإن غاب الرابط (رسائل قديمة) نسقط تلقائياً إلى SDK.
 *
 * وللشبكات الضعيفة: استئناف من حيث توقّف (Range)، وثلاث محاولات بمهلة
 * متصاعدة، والاحتفاظ بالملفّ الجزئي، واستئناف تلقائي فور عودة الشبكة.
 */
object ChatMediaStore {
    private const val TAG = "ChatMediaStore"

    /** أقصى حجم يُنزَّل تلقائيّاً (رسائل صوتيّة وصور صغيرة) — كواتساب. */
    const val AUTO_DOWNLOAD_MAX_BYTES = 3L * 1024 * 1024

    private val flows = ConcurrentHashMap<String, MutableStateFlow<MediaStatus>>()
    private val jobs = ConcurrentHashMap<String, Deferred<File?>>()

    /** مرفقات توقّفت لانقطاع الشبكة — تُستأنف تلقائياً عند عودتها. */
    private val pending = ConcurrentHashMap<String, ChatAttachment>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var watchingNetwork = false

    @Volatile
    private var sweptStale = false

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
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
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

    private fun flowOf(key: String): MutableStateFlow<MediaStatus> =
        flows.getOrPut(key) { MutableStateFlow(MediaStatus.idle) }

    fun statusOf(att: ChatAttachment): StateFlow<MediaStatus> {
        val key = keyOf(att)
        val existing = flows[key]
        if (existing != null) return existing
        val flow = flowOf(key)
        scope.launch { hydrate(att, flow) }
        watchNetwork()
        sweepStaleTemp()
        return flow
    }

    /**
     * 🧹 كنس بقايا التنزيلات المهجورة مرّة لكلّ تشغيل: ملفّات `.part`/`.sdk`
     * لم تُلمس منذ أسبوع لن تُستأنف (رسالتها غالباً خرجت من آخر 60 رسالة)
     * وكانت تتراكم بلا حدّ في مجلّد الوسائط.
     */
    private fun sweepStaleTemp() {
        if (sweptStale) return
        sweptStale = true
        scope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                mediaDir().listFiles()?.forEach { f ->
                    val temp = f.name.endsWith(".part") || f.name.endsWith(".sdk")
                    if (f.isFile && temp && f.lastModified() < cutoff) {
                        runCatching { f.delete() }
                    }
                }
            }
        }
    }

    /** استئناف تلقائي لما توقّف بسبب انقطاع الشبكة. */
    private fun watchNetwork() {
        if (watchingNetwork) return
        watchingNetwork = true
        scope.launch {
            NetworkMonitor.online.collect { online ->
                if (!online) return@collect
                pending.values.toList().forEach { att -> launch { download(att) } }
            }
        }
    }

    private suspend fun hydrate(att: ChatAttachment, flow: MutableStateFlow<MediaStatus>) {
        runCatching {
            val f = localFile(att)
            if (f.exists() && f.length() > 0) {
                flow.value = MediaStatus(MediaState.Downloaded, file = f)
                return
            }
            // تنزيل تلقائي للملفّات الصغيرة جدّاً (رسائل صوتيّة/صور) إن كان مُفعّلاً.
            // الحجم المجهول (0) يُعامل كصغير للرسائل الصوتيّة تحديداً كي تُفتح فوراً.
            val small = att.size in 1..AUTO_DOWNLOAD_MAX_BYTES ||
                (att.size == 0L && att.contentType.startsWith("audio/"))
            if (small && AppPrefs.autoDownloadMedia) download(att)
        }
    }

    /**
     * تنزيل المرفق إلى الجهاز. آمن للاستدعاء المتكرّر: النداء المتزامن
     * ينضمّ إلى نفس المهمّة بدل أن يعود فارغاً (كان زرّ التشغيل «لا يفعل
     * شيئاً» عند الضغط أثناء تنزيل جارٍ).
     *
     * التسجيل ذرّي (`putIfAbsent` على مهمّة كسولة): الفحص ثمّ الإسناد
     * المنفصلان كانا يسمحان لخيطين بكتابة نفس ملفّ `.part` معاً فيفسد.
     */
    suspend fun download(att: ChatAttachment): File? {
        val key = keyOf(att)
        val flow = flowOf(key)
        flow.value.file?.takeIf { it.exists() && it.length() > 0 }?.let { return it }

        val fresh = scope.async(start = CoroutineStart.LAZY) {
            try {
                downloadInternal(att, flow)
            } finally {
                jobs.remove(key)
            }
        }
        val running = jobs.putIfAbsent(key, fresh)
        // خسر السباق: ألغِ النسخة الكسولة (لم تبدأ فلا يعمل جسمها) وانضمّ للجارية.
        if (running != null) fresh.cancel()
        val job = running ?: fresh
        job.start()
        return runCatching { job.await() }.getOrNull()
    }

    /** إعادة محاولة صريحة بعد فشل (زرّ «إعادة المحاولة» في الفقاعة). */
    suspend fun retry(att: ChatAttachment): File? {
        flowOf(keyOf(att)).value = MediaStatus.idle
        return download(att)
    }

    private suspend fun downloadInternal(
        att: ChatAttachment,
        flow: MutableStateFlow<MediaStatus>,
    ): File? = withContext(Dispatchers.IO) {
        val target = localFile(att)
        val partial = File("${target.path}.part")
        flow.value = MediaStatus(MediaState.Downloading, progress = flow.value.progress)

        var lastError: Throwable? = null
        // ثلاث محاولات بمهلة متصاعدة — الشبكات الضعيفة تقطع كثيراً وتعود.
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(1500L * attempt)
            try {
                val file = if (att.url.isNotEmpty()) {
                    downloadOverHttp(att, target, partial, flow)
                } else {
                    downloadViaSdk(att, target, flow)
                }
                pending.remove(keyOf(att))
                flow.value = MediaStatus(MediaState.Downloaded, file = file)
                return@withContext file
            } catch (io: IOException) {
                // انقطاع/بطء: نُبقي الملفّ الجزئي ونستأنف لاحقاً.
                lastError = io
                flow.value = MediaStatus(
                    MediaState.Downloading,
                    progress = flow.value.progress,
                    waitingForNetwork = true,
                )
            } catch (e: Exception) {
                lastError = e
                // 403/404 من الرابط المُوقَّع: جرّب SDK مرّة واحدة (رسائل قديمة
                // أو رمز تنزيل أُبطل)، فإن فشل أيضاً أظهر الخطأ.
                if (att.path.isNotEmpty() && e is HttpStatusException && e.code in listOf(401, 403, 404)) {
                    runCatching {
                        val file = downloadViaSdk(att, target, flow)
                        pending.remove(keyOf(att))
                        flow.value = MediaStatus(MediaState.Downloaded, file = file)
                        return@withContext file
                    }.onFailure { lastError = it }
                }
                break
            }
        }

        Log.d(TAG, "download failed for ${att.name}: $lastError")
        val retryable = lastError is IOException
        if (retryable) pending[keyOf(att)] = att else runCatching { partial.delete() }
        flow.value = MediaStatus(
            state = MediaState.Failed,
            progress = flow.value.progress,
            error = friendlyError(lastError),
            waitingForNetwork = retryable,
        )
        null
    }

    private class HttpStatusException(val code: Int) : Exception("HTTP $code")

    /**
     * تنزيل عبر رابط Firebase المُوقَّع مع استئناف Range — يعمل لكلّ مشرف
     * (لا يمرّ بقاعدة تخزين تعتمد على نداء Firestore عابر للخدمات).
     */
    private fun downloadOverHttp(
        att: ChatAttachment,
        target: File,
        partial: File,
        flow: MutableStateFlow<MediaStatus>,
    ): File {
        var connection: HttpURLConnection? = null
        try {
            // جزئيّ أكبر من حجم المرفق المعلَن = بقايا فاسدة (استُبدل الملفّ
            // أو تشوّهت كتابة سابقة) — استئنافه يُنتج ملفاً تالفاً، فيُهمل.
            if (att.size > 0L && partial.length() > att.size) partial.delete()
            val resumeFrom = partial.length().takeIf { it > 0L } ?: 0L
            connection = (URL(att.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                // مهلة قراءة سخيّة: الشبكات الضعيفة تتوقّف ثوانيَ ثم تستأنف.
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
                connect()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            // 206 = الخادم قبل الاستئناف؛ 200 مع طلب مدى = لا يدعمه فنبدأ من الصفر.
            val resuming = resumeFrom > 0L && code == 206
            if (resumeFrom > 0L && code == 200) partial.delete()
            val remaining = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            val alreadyHave = if (resuming) resumeFrom else 0L
            val total = when {
                remaining > 0L -> remaining + alreadyHave
                att.size > 0L -> att.size
                else -> 0L
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = alreadyHave
                    var lastPublished = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        // تحديث الواجهة كل 200 ك.ب بدل كل قطعة (توفير معالجة).
                        if (received - lastPublished > 200 * 1024 || total in 1..received) {
                            lastPublished = received
                            flow.value = MediaStatus(
                                MediaState.Downloading,
                                progress = if (total > 0L) received * 100.0 / total else 0.0,
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() > 0L) { "الملفّ المُنزَّل فارغ." }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "تعذّر تثبيت الملفّ المُنزَّل." }
            return target
        } finally {
            connection?.disconnect()
        }
    }

    /** المسار البديل: Firebase Storage SDK (رسائل بلا رابط، أو رمز مُبطَل). */
    private suspend fun downloadViaSdk(
        att: ChatAttachment,
        target: File,
        flow: MutableStateFlow<MediaStatus>,
    ): File {
        val tmp = File("${target.path}.sdk")
        if (tmp.exists()) tmp.delete()
        val storage = FirebaseStorage.getInstance()
        val ref = if (att.path.isNotEmpty()) {
            storage.reference.child(att.path)
        } else {
            storage.getReferenceFromUrl(att.url)
        }
        val task = ref.getFile(tmp)
        task.addOnProgressListener { snapshot ->
            val total = if (snapshot.totalByteCount > 0) snapshot.totalByteCount else att.size
            if (total > 0) {
                flow.value = MediaStatus(
                    MediaState.Downloading,
                    progress = snapshot.bytesTransferred * 100.0 / total,
                )
            }
        }
        task.await()
        check(tmp.length() > 0L) { "الملفّ المُنزَّل فارغ." }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "تعذّر تثبيت الملفّ المُنزَّل." }
        return target
    }

    private fun friendlyError(e: Throwable?): String {
        val s = e?.toString().orEmpty()
        return when {
            e is IOException -> "انقطع الاتصال — سيُستأنف التنزيل تلقائياً عند عودة الشبكة."
            s.contains("object-not-found") || s.contains("404") ->
                "الملفّ لم يعد موجوداً على الخادم."
            s.contains("unauthorized") || s.contains("403") || s.contains("401") ->
                "لا تملك صلاحيّة تنزيل هذا الملفّ."
            else -> "تعذّر التنزيل — اضغط لإعادة المحاولة."
        }
    }

    /** حذف النسخة المحليّة (تحرير مساحة) — تعود الحالة إلى «غير منزَّل». */
    fun deleteLocal(att: ChatAttachment) {
        runCatching {
            val f = localFile(att)
            if (f.exists()) f.delete()
            File("${f.path}.part").delete()
        }
        flowOf(keyOf(att)).value = MediaStatus.idle
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
