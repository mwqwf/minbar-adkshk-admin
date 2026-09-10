package com.ali.ishaqiyin_admin.core

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * ☁️ عميل واجهة الإدارة في `minbar-api` (Cloudflare Workers + D1 + R2) — بديل
 * Firestore وFunctions وStorage في اللوحة (قرار المالك 2026-09-10).
 *
 * **الهوية**: رمز Firebase Auth الحالي يُرسَل كما هو (`Bearer`)، والخادم يتحقّق
 * من توقيعه محلّياً ويطابق البريد بجدول المشرفين — فلا يتغيّر تسجيل دخول
 * اللوحة اليوم، ويُستبدل برمز Google مباشرةً حين نطفئ Firebase كلّه.
 *
 * **بلا أسرار في اللوحة**: لا مفاتيح R2 ولا مفتاح المالك — الرفع يمرّ عبر
 * الـWorker إلى الدلو، والصلاحية من الهوية وحدها.
 */
object MinbarAdminApi {
    const val BASE = "https://minbar-api.mushafak.workers.dev"

    /** رابط قراءة عام لكائن وسيط (صور النصوص المشروحة) بمفتاحه في R2. */
    fun mediaUrl(key: String): String = "$BASE/media/$key"

    class ApiException(val code: Int, message: String) : IOException(message)

    private suspend fun bearer(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw ApiException(401, "انتهت جلسة الدخول. سجّل الخروج ثم ادخل بحساب Google مجدّداً.")
        val token = user.getIdToken(false).await().token
            ?: throw ApiException(401, "تعذّر الحصول على رمز الجلسة. أعد تسجيل الدخول.")
        return "Bearer $token"
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = (if (connection.responseCode >= 400) connection.errorStream else connection.inputStream)
            ?: return ""
        val raw = if (connection.contentEncoding == "gzip") GZIPInputStream(stream) else stream
        return raw.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun arabicError(code: Int, body: String): String {
        val server = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return when (code) {
            401 -> "غير مصرَّح: هذا الحساب ليس في قائمة المشرفين أو انتهت جلسته."
            403 -> "هذه العملية للمالك فقط."
            404 -> server.ifBlank { "العنصر المطلوب غير موجود." }
            409 -> server.ifBlank { "تعارض في الحالة على الخادم." }
            else -> server.ifBlank { "خطأ من خادم منبر ($code)." }
        }
    }

    private suspend fun request(
        path: String,
        method: String,
        body: String? = null,
        auth: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            if (auth) setRequestProperty("Authorization", bearer())
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = readBody(connection)
            if (code !in 200..299) throw ApiException(code, arabicError(code, text))
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun get(path: String, auth: Boolean = true): JSONObject = request(path, "GET", auth = auth)
    suspend fun put(path: String, body: JSONObject): JSONObject = request(path, "PUT", body.toString())
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = request(path, "POST", body.toString())
    suspend fun delete(path: String): JSONObject = request(path, "DELETE")

    /**
     * رفع ملفّ إلى R2 عبر الـWorker (`PUT /admin/upload/{originals|images}/…`)
     * ببثّ ثابت الطول وتبليغ نسبة التقدّم. لا استئناف: حدّ الطلب 100 م.ب
     * والدروس دونه بكثير، والإعادة من الصفر أرخص من جلسة قابلة للاستئناف.
     * يعيد `{key, sha256, sizeBytes}` كما حسبها الخادم من البايتات الواصلة.
     */
    suspend fun upload(
        path: String,
        file: File,
        contentType: String,
        onProgress: (Int) -> Unit = {},
    ): JSONObject = withContext(Dispatchers.IO) {
        val total = file.length()
        require(total > 0L) { "الملفّ فارغ." }
        require(total <= MAX_UPLOAD_BYTES) { "حجم الملفّ يتجاوز الحدّ (95 م.ب)." }
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "PUT"
            doOutput = true
            setFixedLengthStreamingMode(total)
            setRequestProperty("Authorization", bearer())
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastPercent = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        val percent = ((sent * 100L) / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            val code = connection.responseCode
            val text = readBody(connection)
            if (code !in 200..299) throw ApiException(code, arabicError(code, text))
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_UPLOAD_BYTES = 95L * 1024 * 1024
}
