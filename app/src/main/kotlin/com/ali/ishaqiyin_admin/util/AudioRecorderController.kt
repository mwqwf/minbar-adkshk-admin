package com.ali.ishaqiyin_admin.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * تسجيل صوتي مباشر بصيغة m4a/AAC — بديل حزمة `record` في نسخة Flutter
 * (نفس الترميز: AAC-LC داخل حاوية MPEG-4).
 */
class AudioRecorderController {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(context: Context, prefix: String, bitRate: Int = 128_000): File {
        stopQuietly()
        val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(bitRate)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        outputFile = file
        return file
    }

    /** إيقاف مؤقّت (أندرويد 7+ فقط — كما في الأصل). */
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

    /** ينهي التسجيل ويعيد الملفّ الناتج (أو null إن فشل). */
    fun stop(): File? {
        val r = recorder ?: return outputFile
        return try {
            r.stop()
            r.release()
            recorder = null
            outputFile
        } catch (_: Exception) {
            runCatching { r.release() }
            recorder = null
            outputFile?.takeIf { it.exists() && it.length() > 0 }
        }
    }

    /** إلغاء وحذف الملفّ. */
    fun cancel() {
        stopQuietly()
        runCatching { outputFile?.delete() }
        outputFile = null
    }

    private fun stopQuietly() {
        runCatching {
            recorder?.stop()
        }
        runCatching { recorder?.release() }
        recorder = null
    }

    fun release() = stopQuietly()
}
