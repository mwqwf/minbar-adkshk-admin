package com.ali.ishaqiyin_admin.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ali.ishaqiyin_admin.data.guessContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** ملفّ اختاره المستخدم من محدّد النظام أو وصل عبر «المشاركة». */
data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
)

/**
 * يقرأ الاسم والحجم من ContentResolver (بديل file_picker).
 *
 * ⚠️ نداء تزامنيّ: `contentResolver.query` يعبر حدود العمليات إلى مزوّد
 * المحتوى، ومع اختيار خمسين ملفاً دفعةً واحدة كان استدعاؤه على خيط الواجهة
 * يجمّدها (خطر ANR). استعمل [pickedFileFromIo] من كوروتين حيثما أمكن؛ هذه
 * النسخة باقية لتوافق المستدعين في `ui/` فقط.
 */
fun Context.pickedFileFrom(uri: Uri): PickedFile {
    var name = ""
    var size = 0L
    runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }
    if (name.isEmpty()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    if (name.isEmpty()) name = "ملفّ"
    return PickedFile(uri = uri, name = name, size = size)
}

/** النسخة الآمنة: نفس القراءة لكن على [Dispatchers.IO] بعيداً عن خيط الواجهة. */
suspend fun Context.pickedFileFromIo(uri: Uri): PickedFile = withContext(Dispatchers.IO) {
    pickedFileFrom(uri)
}

/** النسخة الآمنة لقراءة دفعة كاملة: استعلام واحد لكل Uri على خيط خلفيّ. */
suspend fun Context.pickedFilesFromIo(uris: List<Uri>): List<PickedFile> =
    withContext(Dispatchers.IO) { uris.map { pickedFileFrom(it) } }

/** ينسخ محتوى Uri إلى ملفّ في cache (يلزم لدمج MP3 الذي يقرأ البايتات). */
suspend fun Context.copyUriToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
    val dir = File(cacheDir, "picked").apply { mkdirs() }
    val safeName = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(120)
    val out = File(dir, "${System.nanoTime()}_$safeName")
    contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
    } ?: error("تعذّرت قراءة الملفّ المحدَّد.")
    out
}

/** يفتح ملفاً محليّاً بتطبيق النظام المناسب (بديل open_filex). */
fun Context.openLocalFile(file: File, mimeType: String? = null) {
    // 🛡️ `getUriForFile` ترمي IllegalArgumentException لأيّ ملفّ خارج المسارات
    // المصرَّح بها في `file_paths.xml`، وكانت خارج `runCatching` الذي يلفّ
    // `startActivity` وحده ⇒ انهيار اللوحة بدل رسالة مفهومة.
    val opened = runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: guessContentType(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }.isSuccess
    if (!opened) {
        Toast.makeText(this, "تعذّر فتح الملفّ — لا تطبيق مناسب لهذا النوع.", Toast.LENGTH_SHORT)
            .show()
    }
}

/** يفتح رابطاً/بريداً/هاتفاً بتطبيق خارجي (بديل url_launcher). */
fun Context.openExternalUri(uri: Uri): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: Exception) {
    false
}

/**
 * يستخرج ملفّات الصوت المشتركة من نيّة SEND / SEND_MULTIPLE.
 *
 * ⚠️ تزامنيّة (استعلام ContentResolver لكل Uri) — انظر [pickedFileFrom].
 * الأفضل [sharedAudioFromIo] من كوروتين.
 */
fun Context.sharedAudioFrom(intent: Intent?): List<PickedFile> {
    if (intent == null) return emptyList()
    val uris = when (intent.action) {
        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION")
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            listOfNotNull(uri)
        }

        Intent.ACTION_SEND_MULTIPLE -> {
            @Suppress("DEPRECATION")
            val list = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            list.orEmpty().filterNotNull()
        }

        else -> emptyList()
    }
    return uris.map { pickedFileFrom(it) }
}

/** النسخة الآمنة: نفس الاستخراج لكن على [Dispatchers.IO] بعيداً عن خيط الواجهة. */
suspend fun Context.sharedAudioFromIo(intent: Intent?): List<PickedFile> =
    withContext(Dispatchers.IO) { sharedAudioFrom(intent) }
