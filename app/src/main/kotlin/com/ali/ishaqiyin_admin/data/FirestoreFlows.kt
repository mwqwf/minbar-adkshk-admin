package com.ali.ishaqiyin_admin.data

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Collections

private const val TAG = "FirestoreFlows"
private const val RELISTEN_MIN_MS = 2_000L
private const val RELISTEN_MAX_MS = 30_000L

/**
 * ⛔ سقف محاولات رفض الصلاحيّة: `PERMISSION_DENIED` المتكرّر ليس عطلاً عابراً
 * بل حالة دائمة (مشرف حُظر أو أُلغي اعتماده واللوحة مفتوحة)، وكانت إعادة
 * الربط تدور كل 30 ثانية إلى الأبد — استنزاف بطارية وبيانات بلا تعافٍ ممكن.
 * بعد هذا العدد يتوقّف التكرار وتُرفع راية [FirestoreListenerState.permissionDenied].
 */
private const val PERMISSION_DENIED_MAX_RETRIES = 2

/*
 * تسليم خطأ إلى مستمع Firestore عمليّة **نهائيّة**: يزيل الـSDK الهدف ولا يصل
 * أيّ حدث بعدها مهما طال الانتظار. لذلك:
 *  - إنهاء التدفّق باستثناء (`close(error)`) كان يُسقط التطبيق كاملاً عند أيّ
 *    PERMISSION_DENIED عابر (إزالة مشرف/تسجيل خروج والشاشة مفتوحة)،
 *  - وتجاهل الخطأ وحده يترك الشاشة متجمّدة صامتة إلى الأبد.
 * الحلّ: فصل المستمع الميّت وإعادة الربط بمهلة تصاعديّة، فتتعافى الشاشة وحدها
 * فور عودة الصلاحيّة أو الشبكة.
 */

/**
 * 🚩 إبلاغ الطبقة الأعلى بسحب الصلاحية بلا إسقاط التطبيق.
 *
 * ⛔ لا يجوز `close(error)`: تسليم الاستثناء إلى الجامعين يُسقط اللوحة (وهي
 * العلّة التي عولجت أصلاً بإعادة الربط، ولا جامعَ واحد في المشروع يلفّ تدفّقه
 * بـ`catch`). لذا يُوقَف التكرار وتُرفع رايةٌ يمكن للواجهة مراقبتها فتعرض
 * [PERMISSION_DENIED_MESSAGE] بدل التجمّد الصامت.
 */
object FirestoreListenerState {
    const val PERMISSION_DENIED_MESSAGE =
        "لم تعد تملك صلاحية عرض هذه البيانات. سجّل الخروج ثم الدخول، أو راجع المالك."

    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied

    internal fun markPermissionDenied() {
        _permissionDenied.value = true
    }

    /** تُنادى بعد إعادة الدخول أو استعادة الاعتماد. */
    fun clear() {
        _permissionDenied.value = false
    }
}

/** يربط مستمعاً يعيد ربط نفسه بعد أيّ خطأ، ويُرجع أداة تنظيف كلّ التسجيلات. */
private fun <T> ProducerScope<T>.reattachingListener(
    label: String,
    listen: (onEvent: (T?, Exception?) -> Unit) -> ListenerRegistration,
): () -> Unit {
    val registrations = Collections.synchronizedList(mutableListOf<ListenerRegistration>())
    var backoffMs = RELISTEN_MIN_MS
    var deniedCount = 0

    fun detachAll() {
        val dead = synchronized(registrations) {
            val copy = registrations.toList()
            registrations.clear()
            copy
        }
        dead.forEach { runCatching { it.remove() } }
    }

    fun attach() {
        registrations += listen { value, error ->
            if (error != null) {
                val denied = (error as? FirebaseFirestoreException)?.code ==
                    FirebaseFirestoreException.Code.PERMISSION_DENIED
                if (denied) deniedCount++ else deniedCount = 0
                if (denied && deniedCount > PERMISSION_DENIED_MAX_RETRIES) {
                    // رفضٌ دائم: لا فائدة من محاولة أخرى — نُبلّغ الطبقة الأعلى
                    // بدل الدوران أبداً على حساب البطارية والبيانات.
                    Log.w(TAG, "رفض دائم لصلاحية مستمع $label — إيقاف إعادة الربط", error)
                    detachAll()
                    FirestoreListenerState.markPermissionDenied()
                    return@listen
                }
                Log.w(TAG, "خطأ في مستمع $label — إعادة الربط بعد ${backoffMs}ms", error)
                detachAll()
                val wait = backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(RELISTEN_MAX_MS)
                launch {
                    delay(wait)
                    attach()
                }
                return@listen
            }
            backoffMs = RELISTEN_MIN_MS
            // ⚠️ أي استثناء يفلت من ردّ مستمع Firestore يُسقط التطبيق كاملاً
            // (شوهد في إنتاج تطبيق المستخدم) — فالتسليم معزول ويُسجَّل فشله.
            if (value != null) {
                runCatching { trySend(value) }
                    .onFailure { Log.w(TAG, "تعذّر تسليم لقطة مستمع $label", it) }
            }
        }
    }

    attach()
    return ::detachAll
}

/** بثّ حيّ لاستعلام Firestore — نظير `.snapshots()` في Flutter. */
fun Query.querySnapshots(): Flow<QuerySnapshot> = callbackFlow {
    val cleanup = reattachingListener<QuerySnapshot>("الاستعلام") { onEvent ->
        addSnapshotListener { snapshot, error -> onEvent(snapshot, error) }
    }
    awaitClose(cleanup)
}

/** بثّ حيّ لوثيقة واحدة (نفس معالجة الخطأ أعلاه). */
fun DocumentReference.docSnapshots(): Flow<DocumentSnapshot> = callbackFlow {
    val cleanup = reattachingListener<DocumentSnapshot>("الوثيقة") { onEvent ->
        addSnapshotListener { snapshot, error -> onEvent(snapshot, error) }
    }
    awaitClose(cleanup)
}

/** بيانات الوثيقة كخريطة غير قابلة للإفراغ. */
fun DocumentSnapshot.dataMap(): Map<String, Any?> = data ?: emptyMap()
