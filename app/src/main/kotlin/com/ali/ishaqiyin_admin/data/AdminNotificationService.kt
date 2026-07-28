package com.ali.ishaqiyin_admin.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * يسجّل جهاز المالك/المشرف في مجموعة خاصة ليصله دفع الإشعارات.
 * لا يُستدعى التسجيل إلا بعد نجاح التحقق من الدور والحظر.
 */
object AdminNotificationService {
    private const val TAG = "AdminNotifications"

    suspend fun registerCurrentDevice(isOwner: Boolean) {
        try {
            val user = FirebaseAuth.getInstance().currentUser
            val email = user?.email.orEmpty().trim().lowercase()
            if (user == null || email.isEmpty()) return

            val token = FirebaseMessaging.getInstance().token.await()
            if (!token.isNullOrEmpty()) saveToken(user, email, token, isOwner)
            ChatNotifications.syncSubscription()
        } catch (e: Exception) {
            // لا نعطّل لوحة الإدارة إن رفض النظام إذن الإشعارات أو انقطعت الشبكة.
            Log.d(TAG, "Admin notifications registration failed: $e")
        }
    }

    /** يُستدعى من خدمة الرسائل عند تجديد الرمز. */
    suspend fun onTokenRefreshed(token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email.orEmpty().trim().lowercase()
        if (email.isEmpty()) return
        // الدور يُشتق من البريد لا من حالة مخبّأة: التجديد قد يقع في عملية
        // خلفية باردة لم تمرّ بالتسجيل، فتهبط رتبة المالك إلى supervisor.
        val owner = AuthService.isOwnerEmail(email)
        runCatching { saveToken(user, email, token, owner) }
            .onFailure { Log.d(TAG, "FCM token refresh failed: $it") }
    }

    private suspend fun saveToken(
        user: FirebaseUser,
        email: String,
        token: String,
        isOwner: Boolean,
    ) {
        FirebaseFirestore.getInstance()
            .collection("admin_device_tokens").document(user.uid)
            .set(
                mapOf(
                    "uid" to user.uid,
                    "email" to email,
                    "role" to if (isOwner) "owner" else "supervisor",
                    "token" to token,
                    "platform" to "android",
                    "chatMuted" to ChatNotifications.isMuted,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            ).await()
    }

    suspend fun unregisterCurrentDevice() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            // تسجيل الخروج يجب ألا يُحتجز بسبب تعذّر تنظيف الرمز.
            runCatching {
                FirebaseFirestore.getInstance()
                    .collection("admin_device_tokens").document(user.uid).delete().await()
            }
        }
        ChatNotifications.unsubscribe()
    }
}
