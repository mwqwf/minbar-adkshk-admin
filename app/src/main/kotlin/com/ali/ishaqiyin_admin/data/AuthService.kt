package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import com.ali.ishaqiyin_admin.core.MinbarAdminApi

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
            // ⚠️ كانت الرسالة تسرّب مصطلحاً تقنيّاً ومعرّف المشروع إلى شاشة
            // يراها المشرف — لا يفيده ولا يعرف ما يصنع به.
            return "تسجيل الدخول بـ Google غير مُهيّأ في هذه النسخة. راجع المطوّر."
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
            // السبب الأشيع لفشل الدخول في نسخة **المتجر** تحديداً: Play يُعيد
            // توقيع الحزمة بمفتاحه، فبصمة التوقيع وقت التشغيل ليست بصمة مفتاح
            // الرفع، وما لم تُسجَّل في Firebase يرفض Google إصدار رمز الهويّة.
            // الرسالة الخام هنا مبهمة («Developer console is not set up
            // correctly» أو رقم خطأ)، فنُسمّي السبب صراحةً.
            // ⚠️ كان النصّ الخام الإنجليزيّ يُلحق بالرسالة العربيّة — لا يفهمه
            // المشرف ويكشف تفاصيل داخليّة. صار يُسجَّل في السجلّ فقط.
            val raw = e.message ?: e.type
            Log.w("AuthService", "google sign-in failed: $raw")
            val looksLikeSignatureMismatch = raw.contains("10", ignoreCase = true) ||
                raw.contains("developer", ignoreCase = true) ||
                raw.contains("console", ignoreCase = true) ||
                raw.contains("no credentials", ignoreCase = true)
            if (looksLikeSignatureMismatch) {
                "تعذّر تسجيل الدخول: هذه النسخة من التطبيق غير معتمدة لدى " +
                    "خدمة الدخول. حمّل النسخة الرسميّة من المتجر، وإن تكرّر " +
                    "العطل فراجع المطوّر."
            } else {
                "تعذّر تسجيل الدخول بـ Google. تحقّق من الاتصال ثم أعد المحاولة."
            }
        } catch (e: FirebaseAuthException) {
            authMessage(e.errorCode)
        } catch (e: Exception) {
            // ⚠️ `e.message` نصّ إنجليزيّ خام كان يصل المشرف كما هو.
            "تعذّر تسجيل الدخول بـ Google. تحقّق من الاتصال ثم أعد المحاولة."
        }
    }

    /** يحدّد صلاحية المستخدم الحاليّ بعد الدخول (نظير /api/auth/check في نبراس). */
    suspend fun resolveAccess(): AccessState {
        val user = auth.currentUser ?: return AccessState.SignedOut
        val email = user.email.orEmpty().trim().lowercase()
        if (email.isEmpty()) return AccessState.NeedsOwnerCode

        // بريد المالك وحده يملك bypass — الصلاحية تُشتق من ثابت البريد نفسه فلا
        // يُحجب المالك خلف شبكة ضعيفة. الخادم يبقى الحكم لأي كتابة لاحقة.
        if (isOwnerEmail(email)) return AccessState.Owner

        // الدور من `minbar-api`: الخادم يتحقّق من رمز الجلسة بتوقيعه ويطابق البريد
        // بجدول المشرفين — لا Firestore ولا كاش يُقدَّم على حكم الخادم.
        return try {
            val who = MinbarAdminApi.get("/admin/whoami")
            when (who.optString("role")) {
                "owner" -> AccessState.Owner
                "blocked" -> AccessState.Blocked
                "supervisor", "admin" -> AccessState.Supervisor
                else -> AccessState.NeedsOwnerCode
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MinbarAdminApi.ApiException) {
            if (e.code == 401 || e.code == 403) {
                AccessState.NeedsOwnerCode
            } else {
                throw AccessVerificationException(
                    "تعذّر الاتصال بالخادم للتحقق من الدور والحظر. لم تُمنح أيّ صلاحية مؤقتة.",
                )
            }
        } catch (_: Exception) {
            throw AccessVerificationException(
                "تعذّر الاتصال بالخادم للتحقق من الدور والحظر. لم تُمنح أيّ صلاحية مؤقتة.",
            )
        }
    }

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
    // المهلة تلفّ العملية كاملة (الحذف والكتابة والانتظار): بلا اتصال تعلّق
    // كتابة Firestore نفسها بلا نهاية فتُجمَّد الشاشة.
    private suspend fun requestResponse(
        ref: DocumentReference,
        payload: Map<String, Any?>,
    ): Map<String, Any?> = withTimeout(25_000) {
        runCatching { ref.delete().await() }
        ref.set(payload).await()
        try {
            ref.docSnapshots()
                .filter { it.exists() && it.dataMap()["result"] != null }
                .first()
                .dataMap()
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
        } catch (_: TimeoutCancellationException) {
            // انتهاء مهلة requestResponse = فشل عادي لا تعليق للشاشة.
            OwnerCodeResult(ok = false, reason = "send_failed")
        } catch (e: CancellationException) {
            // إلغاء الكوروتين ليس فشلَ خادم — يُعاد رميه (مهلة `withTimeout`
            // تُلتقط قبله صراحةً فلا تتأثّر).
            throw e
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
        } catch (_: TimeoutCancellationException) {
            // انتهاء مهلة requestResponse = فشل عادي لا تعليق للشاشة.
            OwnerCodeResult(ok = false, reason = "server")
        } catch (e: CancellationException) {
            // كما في [sendOwnerCode]: الإلغاء يُعاد رميه ولا يُترجم إلى فشل خادم.
            throw e
        } catch (_: Exception) {
            OwnerCodeResult(ok = false, reason = "server")
        }
    }

    suspend fun signOut(context: Context) {
        // دون اتصال لا يكتمل حذف الرمز أبداً (await لا يرمي بل ينتظر)،
        // فيُحتجز تسجيل الخروج — مهلة قصيرة تضمن الوصول إلى signOut.
        runCatching { withTimeout(3_000) { AdminNotificationService.unregisterCurrentDevice() } }
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
