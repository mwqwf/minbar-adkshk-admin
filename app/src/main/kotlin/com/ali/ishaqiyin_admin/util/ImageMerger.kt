package com.ali.ishaqiyin_admin.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
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

    /** يدمج الصور بترتيبها ويعيد Uri لملف الناتج في كاش التطبيق. */
    suspend fun mergeVertically(context: Context, uris: List<Uri>): Uri =
        withContext(Dispatchers.IO) {
            require(uris.size in 2..MAX_MERGE_IMAGES) { "اختر صورتين إلى أربع صور للدمج." }
            val bitmaps = uris.map { decodeScaled(context, it) }
            try {
                val width = TARGET_WIDTH
                val heights = bitmaps.map { bmp ->
                    (bmp.height.toLong() * width / bmp.width).toInt().coerceAtLeast(1)
                }
                val totalHeight = heights.sum()
                require(totalHeight <= MAX_TOTAL_HEIGHT) {
                    "الصور طويلة جداً للدمج في صورة واحدة — قصّها أولاً أو أرسلها منفصلة."
                }
                val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(merged)
                canvas.drawColor(Color.WHITE)
                var top = 0
                bitmaps.forEachIndexed { index, bmp ->
                    val h = heights[index]
                    canvas.drawBitmap(bmp, null, Rect(0, top, width, top + h), null)
                    top += h
                }
                val dir = File(context.cacheDir, "merged_pages").apply { mkdirs() }
                // نظافة الكاش: نواتج دمج قديمة لم تُستعمل تُحذف بعد يوم.
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
                val out = File(dir, "pages_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { stream ->
                    merged.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                }
                merged.recycle()
                Uri.fromFile(out)
            } finally {
                bitmaps.forEach { runCatching(it::recycle) }
            }
        }

    /** فكّ ترميز بعيّنة تقريبية أولاً كي لا تنفجر الذاكرة بصور الكاميرا الضخمة. */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return requireNotNull(bitmap) { "تعذّرت قراءة إحدى الصور." }
    }
}
