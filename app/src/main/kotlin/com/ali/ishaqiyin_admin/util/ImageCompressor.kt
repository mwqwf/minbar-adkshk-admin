package com.ali.ishaqiyin_admin.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🗜️ ضغط الصور قبل الرفع — أهمّ توفير للبيانات على الشبكات الضعيفة:
 * صورة الهاتف الواحدة تتجاوز 4 م.ب عادةً، وبعد الضغط تصير مئات الكيلوبايت
 * بجودة كافية تماماً للعرض في الدردشة. النتيجة: رفع أسرع بكثير، وتنزيل
 * أسرع عند بقيّة المشرفين، وفشل أقلّ عند انقطاع الشبكة.
 */
object ImageCompressor {
    private const val MAX_DIMENSION = 1600
    private const val QUALITY = 80

    /** لا نضغط ما هو صغير أصلاً. */
    private const val SKIP_BELOW_BYTES = 400L * 1024

    data class Prepared(val file: PickedFile, val temp: File?)

    /**
     * يعيد ملفاً جاهزاً للرفع: نسخة مضغوطة إن كان المرفق صورة كبيرة، وإلّا
     * الملفّ الأصلي كما هو. [Prepared.temp] يُحذف بعد الرفع.
     */
    suspend fun prepare(
        context: Context,
        file: PickedFile,
        contentType: String,
    ): Prepared = withContext(Dispatchers.IO) {
        val compressible = contentType.startsWith("image/") &&
            !contentType.contains("gif") &&
            (file.size == 0L || file.size > SKIP_BELOW_BYTES)
        if (!compressible) return@withContext Prepared(file, null)

        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(file.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return@withContext Prepared(file, null)
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            if (largest <= 0) return@withContext Prepared(file, null)

            var sample = 1
            while (largest / sample > MAX_DIMENSION * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(file.uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@withContext Prepared(file, null)

            val scaled = scaleDown(decoded)
            val rotated = applyExifRotation(context, file.uri, scaled)
            val out = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
            // 🛡️ قيمة `compress` المنطقيّة كانت مُهمَلة: فشل الترميز (ذاكرة/
            // قرص ممتلئ) كان يمرّ صامتاً فيُرفع ملفّ ناقص أو صفريّ مكان الصورة.
            val compressed = out.outputStream().use { stream ->
                rotated.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
            }
            if (rotated !== decoded) decoded.recycle()
            // الوسيطة `scaled` كانت تُنسى حين تكون كائناً ثالثاً مستقلاً.
            if (scaled !== decoded && scaled !== rotated) scaled.recycle()
            rotated.recycle()

            // لا فائدة إن لم يصغر الحجم فعلاً.
            // ⚠️ الحارس القديم `file.size in 1..out.length()` كان يمرّ سالباً
            // حين `out.length() == 0` (المدى فارغ)، فيُرفع مرفقٌ صفريّ تالف.
            // لذا يُشترط الآن نجاح الضغط وحجمٌ موجب صراحةً.
            val outLength = out.length()
            if (!compressed || outLength <= 0L || (file.size in 1..outLength)) {
                out.delete()
                return@withContext Prepared(file, null)
            }
            val name = file.name.substringBeforeLast('.', file.name) + ".jpg"
            Prepared(
                PickedFile(uri = Uri.fromFile(out), name = name, size = outLength),
                out,
            )
        }.getOrElse { Prepared(file, null) }
    }

    // ─── صور «النص المشروح» ─────────────────────────────────────────
    // حدّ أعلى (2400px) وجودة أعلى (85) من صور الدردشة: صفحة كتاب يجب أن
    // يبقى نصّها مقروءاً تماماً بعد الضغط.
    private const val TRANSCRIPT_MAX_DIMENSION = 2400
    private const val TRANSCRIPT_QUALITY = 85

    /**
     * ضغط صورة صفحة كتاب قبل رفعها (JPEG، أطول بُعد 2400px، جودة 85).
     * يعيد الأصل نفسه (بالهويّة `===`) إن تعذّر الضغط أو لم يُصغِّر الحجم —
     * فالمستدعي يعرف هل صارت JPEG فعلاً.
     */
    fun compressTranscriptImage(bytes: ByteArray): ByteArray = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return bytes

        var sample = 1
        while (largest / sample > TRANSCRIPT_MAX_DIMENSION * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return bytes

        val scaled = scaleDownTo(decoded, TRANSCRIPT_MAX_DIMENSION)
        val rotated = applyExifRotationBytes(bytes, scaled)
        val out = java.io.ByteArrayOutputStream()
        val compressed = rotated.compress(Bitmap.CompressFormat.JPEG, TRANSCRIPT_QUALITY, out)
        if (rotated !== decoded) decoded.recycle()
        if (scaled !== decoded && scaled !== rotated) scaled.recycle()
        rotated.recycle()

        val result = out.toByteArray()
        // لا فائدة إن فشل الترميز أو لم يصغر الحجم فعلاً.
        if (!compressed || result.isEmpty() || result.size >= bytes.size) bytes else result
    }.getOrDefault(bytes)

    private fun scaleDownTo(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** نظير [applyExifRotation] لصورة في الذاكرة (بلا Uri). */
    private fun applyExifRotationBytes(bytes: ByteArray, bitmap: Bitmap): Bitmap =
        runCatching {
            val orientation = java.io.ByteArrayInputStream(bytes).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) {
                bitmap
            } else {
                val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }.getOrDefault(bitmap)

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** الصور الملتقطة بالهاتف تحمل دوراناً في EXIF — نطبّقه كي لا تُقلب. */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap =
        runCatching {
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) {
                bitmap
            } else {
                val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }.getOrDefault(bitmap)
}
