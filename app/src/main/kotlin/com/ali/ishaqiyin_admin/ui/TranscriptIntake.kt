package com.ali.ishaqiyin_admin.ui

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 📖 حمولة «النص المشروح» الواردة بالمشاركة الخارجية (نصّ أو صور صفحات):
 * تنتظر هنا حتى تفتح شاشة اختيار الدرس فتلتقطها وتعبّئ المحرر بها.
 */
object TranscriptIntake {
    data class Payload(
        val text: String = "",
        val images: List<Uri> = emptyList(),
    )

    private val _payload = MutableStateFlow<Payload?>(null)
    val payload: StateFlow<Payload?> = _payload

    fun set(text: String, images: List<Uri>) {
        if (text.isBlank() && images.isEmpty()) return
        _payload.value = Payload(text, images)
    }

    /** تلتقطها الشاشة مرة واحدة عند فتح المحرر. */
    fun consume(): Payload? {
        val current = _payload.value
        _payload.value = null
        return current
    }

    fun clear() {
        _payload.value = null
    }
}
