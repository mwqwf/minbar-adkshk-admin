package com.ali.ishaqiyin_admin.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.sqrt

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
 *
 * ## 🌊 عيّنات السعة (شكل الموجة بنمط واتساب)
 * أثناء التسجيل تُؤخذ عيّنة من `maxAmplitude` كلّ 100ms على مؤقّت الواجهة:
 * [amplitudes] للعرض الحيّ أثناء التسجيل، و[waveform] بعد `stop()` للإرسال
 * مع المرفق. المؤقّت يتوقّف في **كلّ** مسارات الخروج فلا يتسرّب Handler.
 */
class AudioRecorderController {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    // ─── عيّنات السعة ────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private val samples = mutableListOf<Int>()

    // قد يُوقفها خيط غير خيط الواجهة (stop من كوروتين الإرسال).
    @Volatile
    private var sampling = false

    private val _amplitudes = MutableStateFlow<List<Int>>(emptyList())

    /** آخر [LIVE_WINDOW] عيّنة (0..100) — للموجة الحيّة أثناء التسجيل. */
    val amplitudes: StateFlow<List<Int>> = _amplitudes

    private val sampleTick = object : Runnable {
        override fun run() {
            if (!sampling) return
            // maxAmplitude يرمي على بعض الأجهزة إن لم يكن المسجّل نشطاً.
            val raw = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            val level = normalizeAmplitude(raw)
            synchronized(samples) {
                // حلقة دائريّة لا سقف صلب: بلوغ السقف كان يوقف الإضافة
                // فتتجمّد الموجة الحيّة بينما التسجيل مستمرّ.
                if (samples.size >= MAX_SAMPLES) samples.removeAt(0)
                samples.add(level)
                _amplitudes.value = samples.takeLast(LIVE_WINDOW)
            }
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private fun startSampling() {
        if (sampling) return
        sampling = true
        handler.postDelayed(sampleTick, SAMPLE_INTERVAL_MS)
    }

    private fun stopSampling() {
        sampling = false
        handler.removeCallbacks(sampleTick)
    }

    /**
     * شكل الموجة النهائي: 48 قيمة (0..100). نمزج المتوسط مع القمّة في كل
     * دلو كي تبقى حروف الكلام ظاهرة ولا تتحول الموجة إلى شريط شبه مستوٍ.
     * يبقى صالحاً بعد `stop()` (يُستدعى قبل الإرسال)،
     * ويعود فارغاً إن لم تُلتقط أيّ عيّنة (تسجيل أقصر من دورة واحدة).
     */
    fun waveform(): List<Int> {
        val s = synchronized(samples) { samples.toList() }
        if (s.isEmpty()) return emptyList()
        return List(WAVE_BARS) { i ->
            val from = i * s.size / WAVE_BARS
            val to = (i + 1) * s.size / WAVE_BARS
            val value = if (to > from) {
                val bucket = s.subList(from, to)
                val average = bucket.sum() / bucket.size
                val peak = bucket.maxOrNull() ?: average
                (average * 3 + peak * 2) / 5
            } else {
                // تسجيل قصير (عيّنات أقلّ من الأعمدة): يُمدَّد بأقرب عيّنة.
                s[from.coerceAtMost(s.size - 1)]
            }
            value.coerceIn(0, 100)
        }
    }

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
        clearSamples()
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
                startSampling()
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

    /** إيقاف مؤقّت (أندرويد 7+) — يعلّق أخذ العيّنات أيضاً فلا تُسجَّل صمتاً. */
    fun pause(): Boolean = runCatching {
        // ⚠️ `recorder?.pause()` آمنٌ من العدم، فكانت الدالّة تعيد `true` ولو لم
        // يكن هناك تسجيل جارٍ أصلاً — إشارة نجاح كاذبة تجعل الواجهة تعرض
        // «موقوف مؤقّتاً» لتسجيل غير موجود. الآن العدم = فشل صريح.
        val active = recorder ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            active.pause()
            stopSampling()
            true
        } else {
            false
        }
    }.getOrDefault(false)

    fun resume(): Boolean = runCatching {
        // نفس علّة [pause]: لا استئناف بلا مسجّل قائم.
        val active = recorder ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            active.resume()
            startSampling()
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
        // العيّنات المجمَّعة تبقى كما هي كي يعمل [waveform] بعد الإيقاف —
        // الذي يُلغى هنا هو المؤقّت والموجة الحيّة فقط.
        stopSampling()
        _amplitudes.value = emptyList()
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

    /** إلغاء وحذف الملفّ (وإسقاط العيّنات — لا موجة لتسجيل ملغى). */
    fun cancel() {
        stopQuietly()
        clearSamples()
        runCatching { outputFile?.delete() }
        outputFile = null
    }

    private fun stopQuietly() {
        stopSampling()
        _amplitudes.value = emptyList()
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    private fun clearSamples() {
        synchronized(samples) { samples.clear() }
        _amplitudes.value = emptyList()
    }

    fun release() = stopQuietly()

    /** تطبيع سعة `MediaRecorder` إلى 0..100 — بجذر النسبة لتوزيع بصري أوضح. */
    private fun normalizeAmplitude(raw: Int): Int {
        if (raw <= 0) return 0
        val ratio = raw.coerceAtMost(MAX_AMPLITUDE).toDouble() / MAX_AMPLITUDE
        return (sqrt(ratio) * 100).toInt().coerceIn(0, 100)
    }

    companion object {
        private const val TAG = "AudioRecorder"

        /** دورة أخذ العيّنات. */
        private const val SAMPLE_INTERVAL_MS = 100L

        /** أقصى قيمة يعيدها `MediaRecorder.maxAmplitude` (16-bit PCM). */
        private const val MAX_AMPLITUDE = 32_767

        /** عدد أعمدة الموجة المرسَلة مع المرفق. */
        private const val WAVE_BARS = 48

        /** ما يُعرض حيّاً أثناء التسجيل (آخر 6 ثوانٍ). */
        private const val LIVE_WINDOW = 60

        /** سقف أمان للعيّنات المجمَّعة (≈10 دقائق) فلا تنمو بلا حدّ. */
        private const val MAX_SAMPLES = 6_000

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
