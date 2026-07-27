package com.ali.ishaqiyin_admin.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 🎙️ تسجيل صوتي مباشر بصيغة m4a/AAC.
 *
 * ## السبب الجذري لعطل «الرسائل الصوتيّة لا تعمل عند بعض المشرفين»
 * `MediaRecorder.stop()` يرمي استثناءً حين لا تُلتقط بيانات كافية — وهو
 * سلوك **يختلف باختلاف الجهاز** (طرازات ومعالجات تتساهل وأخرى ترمي فوراً).
 * حين يرمي، لا تُكتب حزمة `moov` في حاوية MP4 إطلاقاً، فيبقى الملفّ موجوداً
 * وبحجم > 0 (بيانات `mdat` خام) لكنّه **غير قابل للتشغيل بتاتاً**.
 *
 * النسخة السابقة كانت تعيد هذا الملفّ بالذات عند الفشل
 * (`outputFile?.takeIf { it.length() > 0 }`)، فيُرفع ويُرسَل رسالةً صوتيّة
 * لا تعمل عند أحد — ولهذا كان العطل يظهر عند بعض المشرفين دون بعض،
 * وبحسب سرعة رفع الإصبع عن زرّ التسجيل.
 *
 * العلاج هنا مزدوج:
 *  1. فشل `stop()` = تسجيل تالف ⇒ يُحذف ولا يُرسَل أبداً.
 *  2. حتى عند نجاح `stop()` يُتحقّق أنّ الناتج **يُفكّ ترميزه فعلاً**
 *     (مسار صوتي + مدّة > 0) قبل تسليمه، فلا يمرّ ملفّ معطوب مهما كان
 *     سبب العطب أو طراز الجهاز.
 *  3. إعدادات الترميز تسقط تدريجيّاً إلى ما تدعمه الأجهزة الأضعف بدل أن
 *     يفشل التسجيل من أصله.
 */
class AudioRecorderController {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /**
     * إعدادات مرتَّبة من الأفضل إلى الأكثر توافقاً. بعض المرمِّزات (خاصّة
     * على أجهزة MediaTek الاقتصاديّة) ترفض 44.1kHz أو معدّل البتّ العالي،
     * فبدل أن يفشل التسجيل نهبط درجة ونعيد المحاولة.
     */
    private data class Profile(val sampleRate: Int, val bitRate: Int)

    private val profiles = listOf(
        Profile(44_100, 96_000),
        Profile(48_000, 96_000),
        Profile(44_100, 64_000),
        Profile(16_000, 32_000),
    )

    fun start(context: Context, prefix: String): File {
        stopQuietly()
        val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.m4a")
        var lastError: Throwable? = null

        for (profile in profiles) {
            runCatching { file.delete() }
            val r = newRecorder(context)
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // أحاديّ القناة صراحةً: الافتراضي يختلف بين الأجهزة، ومقطع
                // الكلام لا يستفيد من الاستريو إلا بمضاعفة الحجم.
                r.setAudioChannels(1)
                r.setAudioSamplingRate(profile.sampleRate)
                r.setAudioEncodingBitRate(profile.bitRate)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                outputFile = file
                return file
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "recorder profile ${profile.sampleRate}/${profile.bitRate} failed: $e")
                runCatching { r.reset() }
                runCatching { r.release() }
            }
        }
        runCatching { file.delete() }
        throw lastError ?: IllegalStateException("تعذّر تشغيل المسجّل على هذا الجهاز.")
    }

    private fun newRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /** إيقاف مؤقّت (أندرويد 7+). */
    fun pause(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.pause()
            true
        } else {
            false
        }
    }.getOrDefault(false)

    fun resume(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.resume()
            true
        } else {
            false
        }
    }.getOrDefault(false)

    /**
     * ينهي التسجيل ويعيد الملفّ **إن كان صالحاً للتشغيل فقط**.
     * يعيد `null` لأيّ تسجيل تالف — فلا تُرسَل رسالة صوتيّة ميّتة.
     */
    fun stop(): File? {
        val r = recorder
        val file = outputFile
        recorder = null
        if (r == null) return file?.takeIf { isPlayable(it) }

        val stoppedCleanly = try {
            r.stop()
            true
        } catch (e: Exception) {
            // فشل الإيقاف ⇒ حاوية MP4 بلا moov ⇒ الملفّ غير قابل للتشغيل
            // مهما بدا حجمه معقولاً. هذا هو مصدر «الصوتيات لا تعمل».
            Log.w(TAG, "stop() failed — recording is unusable: $e")
            false
        } finally {
            runCatching { r.release() }
        }

        if (!stoppedCleanly) {
            runCatching { file?.delete() }
            outputFile = null
            return null
        }
        // حزام أمان ثانٍ: نتحقّق أنّ الناتج يُفكّ ترميزه فعلاً قبل تسليمه.
        if (file == null || !isPlayable(file)) {
            Log.w(TAG, "recording produced an unplayable file — discarded")
            runCatching { file?.delete() }
            outputFile = null
            return null
        }
        return file
    }

    /** إلغاء وحذف الملفّ. */
    fun cancel() {
        stopQuietly()
        runCatching { outputFile?.delete() }
        outputFile = null
    }

    private fun stopQuietly() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    fun release() = stopQuietly()

    companion object {
        private const val TAG = "AudioRecorder"

        /**
         * هل هذا الملفّ الصوتي قابل للتشغيل فعلاً؟ يفحص وجود مسار صوتي
         * ومدّة موجبة — وهو ما يسقط عليه الملفّ ناقص الحاوية.
         */
        fun isPlayable(file: File): Boolean {
            if (!file.exists() || file.length() < 1024) return false
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(file.absolutePath)
                val duration = mmr
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val hasAudio = mmr
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                duration > 0L && hasAudio
            } catch (_: Exception) {
                false
            } finally {
                runCatching { mmr.release() }
            }
        }
    }
}
