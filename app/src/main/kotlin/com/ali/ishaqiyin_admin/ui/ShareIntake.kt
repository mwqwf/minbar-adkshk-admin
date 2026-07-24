package com.ali.ishaqiyin_admin.ui

import com.ali.ishaqiyin_admin.util.PickedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 📤 «المشاركة إلى إدارة منبر»: ملفّات صوتيّة تصل من تطبيقات أخرى — تفتح
 * اللوحة نموذج «إضافة درس» معبّأً بكلّ ملفّ على التوالي (نفس سلوك
 * ShareIntakeService في نسخة Flutter، بلا نسخ إلى cache لأنّ الرفع يقرأ
 * الـUri مباشرةً).
 */
object ShareIntake {
    private val _pending = MutableStateFlow<List<PickedFile>>(emptyList())
    val pending: StateFlow<List<PickedFile>> = _pending

    fun add(files: List<PickedFile>) {
        if (files.isEmpty()) return
        val known = _pending.value.map { it.uri.toString() }.toSet()
        _pending.value = _pending.value + files.filter { it.uri.toString() !in known }
    }

    fun peek(): PickedFile? = _pending.value.firstOrNull()

    /** يؤكّد انتهاء النموذج من الملفّ الأوّل (رُفع أو أُلغي). */
    fun consumeFirst() {
        _pending.value = _pending.value.drop(1)
    }

    fun clear() {
        _pending.value = emptyList()
    }
}
