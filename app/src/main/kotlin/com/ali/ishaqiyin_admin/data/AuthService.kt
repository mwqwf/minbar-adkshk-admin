package com.ali.ishaqiyin_admin.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.ali.ishaqiyin_admin.core.AppConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * نتيجة صلاحية الدخول — مطابقة تماماً لتدفّق لوحة نبراس:
 * Google فقط، Owner Bypass فوري، ومشرف جديد يحتاج رمز اعتماد (needsOwnerCode).
 */
enum class AccessState { SignedOut, Owner, Supervisor, NeedsOwnerCode, Blocked }

class AccessVerificationException(override val message: String) : Exception(message)

/**
 * نتيجة طلب/تحقّق رمز الاعتماد (نظير /api/auth/request-code و
 * /api/auth/verify-code في نبراس).
 */
data class OwnerCodeResult(
    val ok: Boolean,
    val reason: String? = null,
    val retryAfterSec: Int? = null,
)

/**
 * مصادقة المشرف — مطابقة لنمط لوحة نبراس: تسجيل دخول بـ Google حصراً
 * (لا بريد/كلمة مرور). الصلاحية تُحدَّد بعد الدخول:
 *   • المالك ([AppConfig.OWNER_EMAIL]) → Owner Bypass فوري بلا رمز.
 *   • مستخدم معتمَد مسبقاً في `dashboard_admins` → مشرف (ما لم يكن محظوراً).
 *   • مستخدم جديد → يحتاج رمز اعتماد يطلبه فيراه المالك حيّاً داخل تطبيقه
 *     (بديل البريد الإلكتروني في نبراس) ثم يُبلَّغ به المرشّح يدوياً.
 * كل صلاحيات الكتابة مفروضة في قواعد Firestore على الخادم.
 */
object AuthService {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentUser: FirebaseUser? get() = auth.currentUser
    val isLoggedIn: Boolean get() = auth.currentUser != null

    /** بثّ حالة تسجيل الدخول — نظير authStateChanges في Flutter. */
    fun authState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun isOwnerEmail(email: String?): Boolean =
        email.orEmpty().trim().lowercase() == AppConfig.OWNER_EMAIL.trim().lowercase()

    // ─── تسجيل الدخول بـ Google (المطابق للوحة نبراس) ───────────────
    /** يعيد رسالة خطأ أو null عند النجاح/الإلغاء. */
    suspend fun signInWithGoogle(context: Context): String? {
        if (!AppConfig.googleSignInConfigured) {
            return "تسجيل الدخول بـ Google غير مُهيّأ بعد (يلزم Web client id لمشروع mxqp-8d1e8)."
        }
        return try {
            val option = GetSignInWithGoogleOption
                .Builder(AppConfig.GOOGLE_SERVER_CLIENT_ID)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(context).getCredential(context, request)
            val credential = response.credential
            if (
                credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential =
                    GoogleAuthProvider.getCredential(googleCredential.idToken, null)
                auth.signInWithCredential(firebaseCredential).await()
                null // الصلاحية تُحدَّد لاحقاً عبر resolveAccess.
            } else {
                "تعذّر قراءة هويّة Google. حاول مجدّداً."
            }
        } catch (_: GetCredentialCancellationException) {
            null // ألغى المستخدم.
        } catch (_: NoCredentialException) {
            "لا يوجد حساب Google على هذا الجهاز. أضف حساباً ثم أعد المحاولة."
        } catch (e: GetCredentialException) {
            "فشل تسجيل الدخول بـ Google: ${e.message ?: e.type}"
        } catch (e: FirebaseAuthException) {
            authMessage(e.errorCode)
        } catch (e: Exception) {
            "فشل تسجيل الدخول بـ Google: ${e.message ?: e}"
        }
    }

    /** يحدّد صلاحية المستخدم الحاليّ بعد الدخول (نظير /api/auth/check في نبراس). */
    suspend fun resolveAccess(): AccessState {
        val user = auth.currentUser ?: return AccessState.SignedOut
        val email = user.email.orEmpty().trim().lowercase()
        if (email.isEmpty()) return AccessState.NeedsOwnerCode

        // بريد المالك وحده يملك bypass — والصلاحية تُشتق من ثابت البريد نفسه،
        // فلا يجوز حجب المالك خلف كتابة توثيقية قد تفشل مؤقتاً (إقلاع خلف قفل
        // الشاشة يخنق الشبكة فتظهر شاشة خطأ توحي بانهيار). القواعد على الخادم
        // تظل الحكم النهائي لأيّ عملية كتابة لاحقة.
        if (isOwnerEmail(email)) {
            runCatching {
                AdminRepository.upsertOwnerRecord(
                    email,
                    displayName = user.displayName.orEmpty(),
                    photoURL = user.photoUrl?.toString().orEmpty(),
                )
            }
            return AccessState.Owner
        }

        try {
            val ref = FirebaseFirestore.getInstance()
                .collection("dashboard_admins").document(email)
            // مسار سريع للشبكات الضعيفة: إن كانت صلاحيتي محفوظة في الكاش
            // ادخل فوراً وحدِّث من الخادم لاحقاً — بلا هذا ينتظر المشرف ردّ
            // الخادم بينما يمرّ المالك بفحص رمز محض فيدخل فوراً.
            val cached = runCatching { ref.get(Source.CACHE).await() }.getOrNull()
            val cachedData = cached?.takeIf { it.exists() }?.dataMap()
            if (cachedData != null && cachedData["blocked"] != true &&
                str(cachedData["role"]) == "supervisor"
            ) {
                AdminRepository.touchLastSignedIn(email)
                // الكاش يمنح دخولاً فوريّاً، لكنّه لا يجوز أن يمنح صلاحية
                // دائمة: نعيد التحقّق من الخادم في الخلفية، وإن كان الحساب
                // قد حُظر أو حُذف نُخرجه فوراً (بوّابة المصادقة تلتقط تغيّر
                // حالة الدخول فتعيده إلى شاشة الدخول).
                revalidateInBackground(ref)
                return AccessState.Supervisor
            }
            val doc = ref.get().await()
            if (!doc.exists()) return AccessState.NeedsOwnerCode
            val data = doc.dataMap()
            if (data["blocked"] == true) return AccessState.Blocked
            // لا تكفي مجرد وثيقة موجودة: يجب أن يكون الدور المعتمد صريحاً.
            if (str(data["role"]) != "supervisor") return AccessState.Blocked
            // مصرَّح له بالفعل — حدّث وقت آخر دخول (لا يُفشل تسجيل الدخول إن تعذّر).
            runCatching { AdminRepository.touchLastSignedIn(email) }
            return AccessState.Supervisor
        } catch (_: Exception) {
            throw AccessVerificationException(
                "تعذّر الاتصال بالخادم للتحقق من الدور والحظر. لم تُمنح أيّ صلاحية مؤقتة.",
            )
        }
    }

    /**
     * إعادة تحقّق صامتة من الخادم بعد منح دخول فوري من الكاش. لا تُبطئ
     * الواجهة، وتُنهي الجلسة إن تبيّن أنّ الحساب حُظر أو أُلغي اعتماده.
     */
    private fun revalidateInBackground(ref: DocumentReference) {
        backgroundScope.launch {
            runCatching {
                val fresh = ref.get(Source.SERVER).await()
                val data = fresh.takeIf { it.exists() }?.dataMap()
                val stillAllowed = data != null &&
                    data["blocked"] != true &&
                    str(data["role"]) == "supervisor"
                if (!stillAllowed) auth.signOut()
            }
        }
    }

    // ─── رمز اعتماد المشرف (نظير /api/auth/request-code و verify-code) ───
    // لا تتوفّر صلاحية استدعاء عامّة لدوالّ onCall في هذا المشروع، فالتنفيذ
    // عبر "طلب بوثيقة": نكتب وثيقة بمعرّف = بريدنا، ومُشغِّل Firestore على
    // الخادم يكتب النتيجة (result) رجوعاً في نفس الوثيقة، ونحذفها بعد القراءة.
    private suspend fun requestResponse(
        ref: DocumentReference,
        payload: Map<String, Any?>,
    ): Map<String, Any?> {
        runCatching { ref.delete().await() }
        ref.set(payload).await()
        try {
            val snapshot = withTimeout(20_000) {
                ref.docSnapshots()
                    .filter { it.exists() && it.dataMap()["result"] != null }
                    .first()
            }
            return snapshot.dataMap()
        } finally {
            runCatching { ref.delete().await() }
        }
    }

    suspend fun requestOwnerCode(): OwnerCodeResult {
        val user = auth.currentUser
        val email = user?.email.orEmpty().trim().lowercase()
        if (user == null || email.isEmpty()) {
            return OwnerCodeResult(ok = false, reason = "send_failed")
        }
        return try {
            val ref = FirebaseFirestore.getInstance()
                .collection("dashboard_code_requests").document(email)
            val data = requestResponse(
                ref,
                mapOf(
                    "uid" to user.uid,
                    "name" to user.displayName.orEmpty(),
                    "photoURL" to user.photoUrl?.toString().orEmpty(),
                    "requestedAt" to nowIso(),
                ),
            )
            val result = data["result"]?.toString()
            if (result == "ok") {
                OwnerCodeResult(ok = true)
            } else {
                OwnerCodeResult(
                    ok = false,
                    reason = result ?: "send_failed",
                    retryAfterSec = (data["retryAfterSec"] as? Number)?.toInt(),
                )
            }
        } catch (_: Exception) {
            OwnerCodeResult(ok = false, reason = "send_failed")
        }
    }

    suspend fun verifyOwnerCode(code: String): OwnerCodeResult {
        val user = auth.currentUser
        val email = user?.email.orEmpty().trim().lowercase()
        if (user == null || email.isEmpty()) {
            return OwnerCodeResult(ok = false, reason = "server")
        }
        return try {
            val ref = FirebaseFirestore.getInstance()
                .collection("dashboard_code_verify").document(email)
            val data = requestResponse(ref, mapOf("code" to code.trim()))
            val result = data["result"]?.toString()
            if (result == "ok") {
                OwnerCodeResult(ok = true)
            } else {
                OwnerCodeResult(ok = false, reason = result ?: "server")
            }
        } catch (_: Exception) {
            OwnerCodeResult(ok = false, reason = "server")
        }
    }

    suspend fun signOut(context: Context) {
        AdminNotificationService.unregisterCurrentDevice()
        runCatching {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        }
        auth.signOut()
    }

    private fun authMessage(code: String): String = when (code) {
        "ERROR_INVALID_EMAIL" -> "صيغة البريد غير صحيحة."
        "ERROR_USER_DISABLED" -> "هذا الحساب معطّل."
        "ERROR_USER_NOT_FOUND", "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" ->
            "بيانات الدخول غير صحيحة."
        "ERROR_OPERATION_NOT_ALLOWED" -> "طريقة الدخول غير مُفعّلة في Firebase. فعّلها من Console."
        "ERROR_NETWORK_REQUEST_FAILED" -> "لا يوجد اتصال بالإنترنت."
        else -> "تعذّر تسجيل الدخول ($code)."
    }
}
