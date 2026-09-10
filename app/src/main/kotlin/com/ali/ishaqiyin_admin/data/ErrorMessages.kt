package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import java.io.IOException

/**
 * ترجمة أخطاء الشبكة والخادم إلى عربيّة مفهومة — كلّ الشاشات تستعملها.
 *
 * منذ 2026-09-10 لم تعد هناك أخطاء Firestore/Functions/Storage: الخادم واحد
 * (`minbar-api`) ورسائله عربيّة أصلاً، فتُقدَّم كما هي؛ وما عداها يُصنَّف
 * برمز HTTP أو بكونه انقطاعَ شبكة.
 */
private const val CAUSE_DEPTH = 10
private const val SESSION_EXPIRED =
    "انتهت جلسة الدخول. سجّل الخروج ثم ادخل بحساب Google مجدّداً."
private const val NO_NETWORK =
    "لا يوجد اتصال بالإنترنت. تحقّق من الشبكة ثم أعد المحاولة."
private const val TIMED_OUT =
    "انتهت مهلة الاتصال بالخادم. أعد المحاولة."
private const val TOO_MANY =
    "طلبات كثيرة متتابعة. انتظر قليلاً ثم أعد المحاولة."
private const val CONFLICT =
    "تعارضت العملية مع تعديل آخر جارٍ. أعد المحاولة."
private const val GENERIC =
    "تعذّر إتمام العملية. أعد المحاولة بعد قليل."

fun Throwable.arabicReason(): String {
    var current: Throwable? = this
    var guard = 0
    while (guard < CAUSE_DEPTH) {
        val error = current ?: break
        if (error is MinbarAdminApi.ApiException) return error.arabicReason()
        val text = error.message.orEmpty().trim()
        if (text.isNotEmpty() && isArabicText(text)) return text
        current = error.cause
        guard += 1
    }
    return if (isOfflineError(this)) NO_NETWORK else GENERIC
}

fun MinbarAdminApi.ApiException.arabicReason(): String {
    val server = message.orEmpty().trim()
    if (server.isNotEmpty() && isArabicText(server)) return server
    return when (code) {
        401 -> SESSION_EXPIRED
        403 -> "هذا الحساب غير مخوّل لتنفيذ هذه العملية."
        400, 422 -> "البيانات المُرسَلة ناقصة أو غير صالحة. راجعها ثم أعد المحاولة."
        404 -> "العنصر المطلوب غير موجود."
        409 -> CONFLICT
        408 -> TIMED_OUT
        429 -> TOO_MANY
        in 500..599 -> "تعذّر الوصول إلى الخادم. أعد المحاولة بعد قليل."
        else -> GENERIC
    }
}

/** هل النصّ عربيّ (فيُعرض كما هو من الخادم)؟ */
internal fun isArabicText(text: String): Boolean = text.any { it.code in 0x0600..0x06FF }

/** انقطاع شبكة صريح — لا خطأ خادم. */
internal fun isOfflineError(error: Throwable): Boolean {
    var current: Throwable? = error
    var guard = 0
    while (current != null && guard < CAUSE_DEPTH) {
        if (current is java.net.UnknownHostException ||
            current is java.net.SocketTimeoutException ||
            current is java.net.ConnectException ||
            current is javax.net.ssl.SSLException
        ) {
            return true
        }
        if (current is IOException) {
            val text = current.message.orEmpty().lowercase()
            if (
                text.contains("unable to resolve host") ||
                text.contains("failed to connect") ||
                text.contains("network is unreachable") ||
                text.contains("timeout") ||
                text.contains("تعذّر الاتصال")
            ) {
                return true
            }
        }
        current = current.cause
        guard += 1
    }
    return false
}
