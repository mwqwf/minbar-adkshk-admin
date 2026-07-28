package com.ali.ishaqiyin_admin.data

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.Collections

private const val TAG = "FirestoreFlows"
private const val RELISTEN_MIN_MS = 2_000L
private const val RELISTEN_MAX_MS = 30_000L

/*
 * تسليم خطأ إلى مستمع Firestore عمليّة **نهائيّة**: يزيل الـSDK الهدف ولا يصل
 * أيّ حدث بعدها مهما طال الانتظار. لذلك:
 *  - إنهاء التدفّق باستثناء (`close(error)`) كان يُسقط التطبيق كاملاً عند أيّ
 *    PERMISSION_DENIED عابر (إزالة مشرف/تسجيل خروج والشاشة مفتوحة)،
 *  - وتجاهل الخطأ وحده يترك الشاشة متجمّدة صامتة إلى الأبد.
 * الحلّ: فصل المستمع الميّت وإعادة الربط بمهلة تصاعديّة، فتتعافى الشاشة وحدها
 * فور عودة الصلاحيّة أو الشبكة.
 */

/** يربط مستمعاً يعيد ربط نفسه بعد أيّ خطأ، ويُرجع أداة تنظيف كلّ التسجيلات. */
private fun <T> ProducerScope<T>.reattachingListener(
    label: String,
    listen: (onEvent: (T?, Exception?) -> Unit) -> ListenerRegistration,
): () -> Unit {
    val registrations = Collections.synchronizedList(mutableListOf<ListenerRegistration>())
    var backoffMs = RELISTEN_MIN_MS

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
            if (value != null) trySend(value)
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
