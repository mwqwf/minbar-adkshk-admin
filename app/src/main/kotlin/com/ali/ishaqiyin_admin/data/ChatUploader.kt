package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.util.ImageCompressor
import com.ali.ishaqiyin_admin.util.PickedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** وجهة الرفع: مجموعة الإدارة أو محادثة خاصّة. */
sealed interface ChatUploadTarget {
    data object Group : ChatUploadTarget

    data class Dm(val threadId: String, val otherUid: String) : ChatUploadTarget
}

/** حالة عمليّة رفع واحدة — لكلّ عمليّة معرّفها ونسبتها وإلغاؤها المستقلّ. */
data class ChatUpload(
    val id: Long,
    val target: ChatUploadTarget,
    val name: String,
    val percent: Double = 0.0,
)

/**
 * 📤 مدير رفع مرفقات الدردشة — يعمل في نطاق مستقلّ عن أيّ شاشة، فلا يلغيه
 * تدوير الشاشة ولا الرجوع منها (كان الرفع سابقاً في `rememberCoroutineScope`
 * فيُلغى بصمت مع النموذج). يدير **قائمة عمليّات مستقلّة**: لكلّ عمليّة اسمها
 * ونسبتها وعلم إلغائها، فيمكن تسجيل رسالة صوتيّة وإرسالها أثناء رفع صورة
 * دون أن تتشوّه حالة الأخرى.
 */
object ChatUploader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1L)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val abortedIds: MutableSet<Long> =
        Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    private val _uploads = MutableStateFlow<List<ChatUpload>>(emptyList())

    /** عمليّات الرفع الجارية — تراقبها الشاشتان لعرض شريط لكلّ عمليّة. */
    val uploads: StateFlow<List<ChatUpload>> = _uploads

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** رسائل فشل الرفع — تعرضها الشاشة المفتوحة كتنبيه. */
    val errors: SharedFlow<String> = _errors

    /**
     * إضافة عمليّة رفع جديدة تُنفَّذ كاملة هنا: ضغط الصورة ← رفع وإرسال
     * الرسالة ← تحديث مؤشّر القراءة ← حذف الملفّات المؤقّتة.
     */
    fun enqueue(
        target: ChatUploadTarget,
        file: PickedFile,
        type: ChatMessageType,
        contentType: String,
        caption: String = "",
        durationMs: Long? = null,
        /** شكل موجة الرسالة الصوتيّة (0..100، حتى 40 قيمة) — للصوتيّات فقط. */
        waveform: List<Int>? = null,
        replyTo: ChatReplyRef? = null,
        fromGroup: ChatReplyRef? = null,
        deleteAfter: File? = null,
    ): Long {
        val id = nextId.getAndIncrement()
        _uploads.update { it + ChatUpload(id = id, target = target, name = file.name) }
        val job = scope.launch {
            var temp: File? = null
            try {
                // ضغط الصور قبل الرفع: أهمّ توفير للبيانات على شبكة ضعيفة.
                val prepared = ImageCompressor.prepare(AppPrefs.context, file, contentType)
                temp = prepared.temp
                val payload = prepared.file
                when (target) {
                    is ChatUploadTarget.Group -> {
                        ChatRepository.sendAttachment(
                            uri = payload.uri,
                            filename = payload.name,
                            contentType = contentType,
                            type = type,
                            caption = caption,
                            durationMs = durationMs,
                            waveform = waveform,
                            replyTo = replyTo,
                            onProgress = { setPercent(id, it) },
                            isAborted = { abortedIds.contains(id) },
                        )
                        ChatRepository.markRead()
                    }

                    is ChatUploadTarget.Dm -> {
                        DmRepository.sendAttachment(
                            threadId = target.threadId,
                            otherUid = target.otherUid,
                            uri = payload.uri,
                            filename = payload.name,
                            contentType = contentType,
                            type = type,
                            caption = caption,
                            durationMs = durationMs,
                            waveform = waveform,
                            replyTo = replyTo,
                            fromGroup = fromGroup,
                            onProgress = { setPercent(id, it) },
                            isAborted = { abortedIds.contains(id) },
                        )
                        DmRepository.markRead(target.threadId)
                    }
                }
            } catch (e: CancellationException) {
                // إلغاء المهمّة ليس فشل رفع — يجب أن يُعاد رميه دائماً.
                throw e
            } catch (e: Exception) {
                if (!abortedIds.contains(id)) {
                    _errors.tryEmit("فشل رفع \"${file.name}\": ${e.message ?: e}")
                }
            } finally {
                runCatching { deleteAfter?.delete() }
                runCatching { temp?.delete() }
                // علم الإلغاء يبقى: مهمّة Storage قد تكون ما زالت حيّة بعد
                // إلغاء الكوروتين، ومستمعها يقرأ العلم ليلغي نفسه.
                jobs.remove(id)
                _uploads.update { list -> list.filterNot { it.id == id } }
            }
        }
        jobs[id] = job
        return id
    }

    /**
     * إعادة توجيه رسالة. المرفق يُنسخ إلى مسار جديد (تنزيل ثمّ رفع) كي يملك
     * كلّ توجيه ملفّه فلا يُفقد بحذف الأصل — وهذه عمليّة طويلة، فتُنفَّذ هنا
     * لا في نطاق الشاشة، وإلّا ألغاها التدوير وخلّف ملفّاً يتيماً بلا رسالة.
     */
    fun enqueueForward(target: ChatUploadTarget, msg: ChatMessage): Long {
        val id = nextId.getAndIncrement()
        val label = msg.attachment?.name ?: chatTypeLabel(msg.type)
        _uploads.update { it + ChatUpload(id = id, target = target, name = label) }
        val job = scope.launch {
            try {
                when (target) {
                    is ChatUploadTarget.Group -> ChatRepository.forward(msg)
                    is ChatUploadTarget.Dm ->
                        DmRepository.forward(target.threadId, target.otherUid, msg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!abortedIds.contains(id)) {
                    _errors.tryEmit("تعذّرت إعادة التوجيه: ${e.message ?: e}")
                }
            } finally {
                jobs.remove(id)
                _uploads.update { list -> list.filterNot { it.id == id } }
            }
        }
        jobs[id] = job
        return id
    }

    /** إلغاء عمليّة واحدة دون المساس ببقيّة العمليّات الجارية. */
    fun cancel(id: Long) {
        abortedIds.add(id)
        jobs[id]?.cancel()
    }

    private fun setPercent(id: Long, percent: Double) {
        _uploads.update { list ->
            list.map { if (it.id == id) it.copy(percent = percent) else it }
        }
    }
}
