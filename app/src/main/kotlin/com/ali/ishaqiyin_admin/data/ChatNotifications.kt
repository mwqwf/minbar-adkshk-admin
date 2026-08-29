package com.ali.ishaqiyin_admin.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * إشعارات دردشة الإدارة. يسجَّل تفضيل الكتم في رمز جهاز الإدارة نفسه،
 * فتدفع الدالة السحابية الرسالة إلى الأجهزة المعتمدة فقط وتستبعد المرسل.
 * نلغي اشتراك الموضوع القديم لأن موضوعات FCM العامة ليست قناة مناسبة
 * لمحتوى مجموعة خاصة.
 */
object ChatNotifications {
    const val TOPIC = "menbar_admin_chat"

    /** هل شاشة الدردشة الجماعيّة معروضة الآن؟ */
    @Volatile
    var isChatOpen: Boolean = false

    /** معرّف المحادثة الخاصّة المفتوحة الآن (لكتم إشعارها وحدها). */
    @Volatile
    var openDmThreadId: String = ""

    val isMuted: Boolean get() = AppPrefs.chatMuted

    /**
     * مزامنة الاشتراك مع تفضيل الكتم — تُستدعى بعد نجاح التحقق من الدور
     * وعند تبديل مفتاح «إشعارات المجموعة».
     */
    suspend fun syncSubscription() {
        val muted = isMuted
        // عمليتان مستقلّتان: فشل إلغاء الاشتراك يجب ألّا يمنع كتابة علم الكتم.
        // إلغاء اشتراك الموضوع القديم نداء شبكيّ لا يلزم إلّا مرّة لكلّ
        // تثبيت — تكراره في كلّ إقلاع وكلّ تبديل للكتم كان هدراً محضاً.
        if (!AppPrefs.legacyTopicUnsubscribed) {
            runCatching { FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC).await() }
                .onSuccess { AppPrefs.legacyTopicUnsubscribed = true }
        }
        val user = FirebaseAuth.getInstance().currentUser
        // 💸 اكتب فقط إن تغيّرت القيمة: تُستدعى المزامنة عند كلّ فتح للوحة،
        // والقيمة لا تتبدّل إلا بتبديل مفتاح «إشعارات المجموعة» نفسه.
        // (AdminNotificationService يكتبها ضمن بصمة الرمز عند تغيّرها أيضاً.)
        val written = "$muted|${user?.uid.orEmpty()}"
        if (user != null && written != AppPrefs.lastChatMutedWritten) {
            // بلا await: تُجدوَل الكتابة محليّاً وتصل عند عودة الشبكة.
            runCatching {
                FirebaseFirestore.getInstance()
                    .collection("admin_device_tokens").document(user.uid)
                    .set(mapOf("chatMuted" to muted), SetOptions.merge())
                AppPrefs.lastChatMutedWritten = written
            }
        }
    }

    suspend fun setMuted(muted: Boolean) {
        AppPrefs.chatMuted = muted
        syncSubscription()
    }

    suspend fun unsubscribe() {
        runCatching { FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC).await() }
    }
}
