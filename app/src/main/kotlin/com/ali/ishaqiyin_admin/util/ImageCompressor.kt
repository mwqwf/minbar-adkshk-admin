package com.ali.ishaqiyin_admin.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
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
            out.outputStream().use { stream ->
                rotated.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
            }
            if (rotated !== decoded) decoded.recycle()
            rotated.recycle()

            // لا فائدة إن لم يصغر الحجم فعلاً.
            if (file.size in 1..out.length()) {
                out.delete()
                return@withContext Prepared(file, null)
            }
            val name = file.name.substringBeforeLast('.', file.name) + ".jpg"
            Prepared(
                PickedFile(uri = Uri.fromFile(out), name = name, size = out.length()),
                out,
            )
        }.getOrElse { Prepared(file, null) }
    }

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
