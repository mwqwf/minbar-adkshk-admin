package com.ali.ishaqiyin_admin.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🖼️ دمج صور صفحات الكتاب عموديّاً في صورة واحدة — نظير AudioMerger
 * للصوتيات: الترتيب الذي اختاره المستخدم (أيّها أولاً) هو ترتيب اللصق من
 * الأعلى للأسفل. تُوحَّد الأعراض وتُضغط النتيجة JPEG في كاش التطبيق.
 */
object ImageMerger {
    const val MAX_MERGE_IMAGES = 4
    private const val TARGET_WIDTH = 1440
    private const val MAX_TOTAL_HEIGHT = 14_000

    /** أبعاد صورة مصدر **بعد** دوران EXIF، تُقاس قبل أي فكّ ترميز كامل. */
    private class Source(
        val uri: Uri,
        val degrees: Int,
        val width: Int,
        val height: Int,
    )

    /** يدمج الصور بترتيبها ويعيد Uri لملف الناتج في كاش التطبيق. */
    suspend fun mergeVertically(context: Context, uris: List<Uri>): Uri =
        withContext(Dispatchers.IO) {
            require(uris.size in 2..MAX_MERGE_IMAGES) { "اختر صورتين إلى أربع صور للدمج." }
            // مسح الأبعاد أوّلاً بلا فكّ ترميز: كان فكّ الصور الأربع دفعةً واحدة
            // يُبقيها كلّها حيّة تحت الناتج الضخم، وكان حدّ الطول يُفحص بعد
            // الفكّ فلا يحمي الذروة أصلاً.
            val sources = uris.map { readSource(context, it) }
            val width = TARGET_WIDTH
            val heights = sources.map { source ->
                (source.height.toLong() * width / source.width).toInt().coerceAtLeast(1)
            }
            val totalHeight = heights.sum()
            require(totalHeight <= MAX_TOTAL_HEIGHT) {
                "الصور طويلة جداً للدمج في صورة واحدة — قصّها أولاً أو أرسلها منفصلة."
            }
            val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(merged)
                canvas.drawColor(Color.WHITE)
                var top = 0
                sources.forEachIndexed { index, source ->
                    val h = heights[index]
                    // صورة مصدر واحدة حيّة في كل لحظة: الذروة = الناتج + واحدة.
                    val bitmap = decodeScaled(context, source)
                    canvas.drawBitmap(bitmap, null, Rect(0, top, width, top + h), null)
                    bitmap.recycle()
                    top += h
                }
                val dir = File(context.cacheDir, "merged_pages").apply { mkdirs() }
                // نظافة الكاش: نواتج دمج قديمة لم تُستعمل تُحذف بعد يوم.
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
                val out = File(dir, "pages_${System.currentTimeMillis()}.jpg")
                val written = out.outputStream().use { stream ->
                    merged.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                }
                check(written) { "تعذّر حفظ الصورة المدموجة." }
                Uri.fromFile(out)
            } finally {
                // الناتج أضخم بتماب في العمليّة؛ كان يبقى محجوزاً لو فشل الحفظ.
                merged.recycle()
            }
        }

    /** يقرأ الأبعاد ودوران EXIF فقط — رخيص جدّاً ولا يحجز ذاكرة صورة. */
    private fun readSource(context: Context, uri: Uri): Source {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "تعذّرت قراءة إحدى الصور." }
        val degrees = readExifDegrees(context, uri)
        // دوران الربع يقلب العرض بالطول، والحساب يجب أن يجري على الأبعاد المرئيّة.
        val swapped = degrees == 90 || degrees == 270
        return Source(
            uri = uri,
            degrees = degrees,
            width = if (swapped) bounds.outHeight else bounds.outWidth,
            height = if (swapped) bounds.outWidth else bounds.outHeight,
        )
    }

    /**
     * الصور الملتقطة بالهاتف تحمل دورانها في EXIF — والمدموجة تُحفظ بلا EXIF
     * فلا يصحّحها عارض لاحقاً؛ بلا تطبيقه هنا تُلصق الصفحة مقلوبة.
     */
    private fun readExifDegrees(context: Context, uri: Uri): Int = runCatching {
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** فكّ ترميز بعيّنة تقريبية أولاً كي لا تنفجر الذاكرة بصور الكاميرا الضخمة. */
    private fun decodeScaled(context: Context, source: Source): Bitmap {
        var sample = 1
        while (source.width / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        requireNotNull(bitmap) { "تعذّرت قراءة إحدى الصور." }
        if (source.degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(source.degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
