package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.StorageException
import java.io.IOException

/**
 * ترجمة موحّدة لأعطال Firebase إلى **عربية صالحة للعرض مباشرة**.
 *
 * كانت الشاشات تعرض `${it.message ?: it}` فيصل المشرف نصّ الاستثناء الخام:
 * «تعذّر الحذف: INTERNAL» أو
 * «PERMISSION_DENIED: Missing or insufficient permissions.» — لغة لا يعرفها
 * داخل جملة عربية. أخطاء `HttpsError` في هذا المشروع عربية أصلاً، لكن أعطال
 * البنية (INTERNAL / UNAVAILABLE / DEADLINE_EXCEEDED / قواعد Firestore /
 * انقطاع الشبكة) ليست كذلك، فهذه هي الطبقة التي تترجمها كلّها في مكان واحد.
 *
 * القاعدة: رسالة الخادم تُفضَّل إن كانت عربية، وإلّا فالرمز يُترجَم.
 */

/** لا يُترجَم رمز، وإنّما تُقرأ سلسلة الأسباب حتى أوّل نوع معروف. */
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

/**
 * سبب الفشل بالعربية. تُستعمل في كلّ الشاشات هكذا:
 * `snack("تعذّر الحذف: ${it.arabicReason()}")`.
 */
fun Throwable.arabicReason(): String {
    var current: Throwable? = this
    var guard = 0
    while (guard < CAUSE_DEPTH) {
        val error = current ?: break
        when (error) {
            // ⚠️ StorageException يرث IOException، فيجب فحصه قبل فحص الانقطاع.
            is StorageException -> return error.arabicReason()
            is FirebaseFunctionsException -> return error.arabicReason()
            is FirebaseFirestoreException -> return error.arabicReason()
            else -> {
                val text = error.message.orEmpty().trim()
                if (text.isNotEmpty() && isArabicText(text)) return text
            }
        }
        current = error.cause
        guard += 1
    }
    return if (isOfflineError(this)) NO_NETWORK else GENERIC
}

/** ترجمة عطل دالة سحابية — رسالة `HttpsError` العربية تُفضَّل كما هي. */
fun FirebaseFunctionsException.arabicReason(): String {
    val server = message.orEmpty().trim()
    if (server.isNotEmpty() && isArabicText(server)) return server
    return when (code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> SESSION_EXPIRED
        FirebaseFunctionsException.Code.PERMISSION_DENIED ->
            "هذا الحساب غير مخوّل لتنفيذ هذه العملية."
        FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
            "البيانات المُرسَلة ناقصة أو غير صالحة. راجعها ثم أعد المحاولة."
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            "رفض الخادم الطلب قبل التحقق من سلامة التطبيق. أعد فتح اللوحة وحاول مجدّداً."
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> TOO_MANY
        FirebaseFunctionsException.Code.NOT_FOUND,
        FirebaseFunctionsException.Code.UNIMPLEMENTED,
        ->
            "هذه الخدمة غير متاحة على الخادم حالياً (لم تُنشر الدالة المطلوبة)."
        FirebaseFunctionsException.Code.ALREADY_EXISTS ->
            "العنصر موجود مسبقاً."
        FirebaseFunctionsException.Code.ABORTED -> CONFLICT
        FirebaseFunctionsException.Code.UNAVAILABLE ->
            "تعذّر الوصول إلى الخادم. تحقّق من الاتصال بالإنترنت ثم أعد المحاولة."
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> TIMED_OUT
        FirebaseFunctionsException.Code.CANCELLED ->
            "أُلغيت العملية قبل اكتمالها."
        else ->
            if (isOfflineError(this)) NO_NETWORK else "خطأ في الخادم. أعد المحاولة بعد قليل."
    }
}

/** ترجمة عطل Firestore — أشهرها رفض القواعد `PERMISSION_DENIED`. */
fun FirebaseFirestoreException.arabicReason(): String = when (code) {
    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
        "لا تملك صلاحية تنفيذ هذه العملية على هذه البيانات."
    FirebaseFirestoreException.Code.UNAUTHENTICATED -> SESSION_EXPIRED
    FirebaseFirestoreException.Code.UNAVAILABLE ->
        "تعذّر الوصول إلى قاعدة البيانات. تحقّق من الاتصال ثم أعد المحاولة."
    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> TIMED_OUT
    FirebaseFirestoreException.Code.NOT_FOUND ->
        "العنصر غير موجود — ربّما حُذف مسبقاً. حدّث الشاشة."
    FirebaseFirestoreException.Code.ALREADY_EXISTS ->
        "العنصر موجود مسبقاً."
    FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> TOO_MANY
    FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
        "تعذّر تنفيذ العملية في وضعها الحالي. حدّث الشاشة ثم أعد المحاولة."
    FirebaseFirestoreException.Code.ABORTED -> CONFLICT
    FirebaseFirestoreException.Code.CANCELLED ->
        "أُلغيت العملية قبل اكتمالها."
    FirebaseFirestoreException.Code.INVALID_ARGUMENT ->
        "البيانات المُرسَلة ناقصة أو غير صالحة. راجعها ثم أعد المحاولة."
    FirebaseFirestoreException.Code.OUT_OF_RANGE ->
        "قيمة خارج المدى المسموح."
    FirebaseFirestoreException.Code.UNIMPLEMENTED ->
        "هذه العملية غير مدعومة."
    else ->
        if (isOfflineError(this)) NO_NETWORK else "خطأ في قاعدة البيانات. أعد المحاولة بعد قليل."
}

/** ترجمة عطل التخزين (رفع/تنزيل/حذف الملفّات الصوتية والمرفقات). */
fun StorageException.arabicReason(): String = when (errorCode) {
    StorageException.ERROR_OBJECT_NOT_FOUND ->
        "الملفّ غير موجود في التخزين — ربّما حُذف مسبقاً."
    StorageException.ERROR_BUCKET_NOT_FOUND,
    StorageException.ERROR_PROJECT_NOT_FOUND,
    ->
        "إعدادات التخزين غير صحيحة على الخادم."
    StorageException.ERROR_QUOTA_EXCEEDED ->
        "امتلأت حصّة التخزين. راجع إعدادات المشروع."
    StorageException.ERROR_NOT_AUTHENTICATED -> SESSION_EXPIRED
    StorageException.ERROR_NOT_AUTHORIZED ->
        "لا تملك صلاحية الوصول إلى هذا الملفّ في التخزين."
    StorageException.ERROR_RETRY_LIMIT_EXCEEDED ->
        "انتهت مهلة نقل الملفّ. تحقّق من الاتصال ثم أعد المحاولة."
    StorageException.ERROR_INVALID_CHECKSUM ->
        "تلِف الملفّ أثناء النقل. أعد المحاولة."
    StorageException.ERROR_CANCELED ->
        "أُلغيت عملية الملفّ قبل اكتمالها."
    else ->
        "تعذّر إتمام عملية الملفّ في التخزين. أعد المحاولة."
}

/** نطاق الحروف العربية الأساسي U+0600..U+06FF (بلا حروف حرفية في الكود). */
internal fun isArabicText(text: String): Boolean = text.any { it.code in 0x0600..0x06FF }

/** انقطاع الشبكة يصل ملفوفاً داخل سلسلة أسباب — نفحصها كلّها. */
internal fun isOfflineError(error: Throwable): Boolean {
    var cause: Throwable? = error
    var guard = 0
    while (cause != null && guard < CAUSE_DEPTH) {
        if (cause is IOException) return true
        cause = cause.cause
        guard += 1
    }
    return false
}
