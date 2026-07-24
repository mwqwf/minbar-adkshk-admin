package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** تنبيه إداري واحد كما يُعرض في «تنبيهاتك». */
data class AdminAlert(
    val id: String,
    val email: String, // '' = عامّ لكل المشرفين.
    /** يُخفى عن هذا البريد (منعاً للتكرار مع تنبيهه الشخصي). */
    val excludeEmail: String,
    val title: String,
    val body: String,
    val type: String, // submission | milestone | owner_code | digest | ...
    val refId: String,
    val createdAtMs: Long,
    val readBy: List<String>,
) {
    fun isReadBy(email: String): Boolean = readBy.contains(email.lowercase())

    companion object {
        fun fromDoc(doc: DocumentSnapshot): AdminAlert {
            val d = doc.dataMap()

            @Suppress("UNCHECKED_CAST")
            val metadata = (d["data"] as? Map<String, Any?>) ?: emptyMap()
            return AdminAlert(
                id = doc.id,
                email = str(d["email"]).lowercase(),
                excludeEmail = str(d["excludeEmail"] ?: metadata["excludeEmail"]).lowercase(),
                title = str(d["title"]),
                body = str(d["body"]),
                type = str(d["type"] ?: metadata["type"]),
                refId = str(
                    d["refId"] ?: metadata["refId"] ?: metadata["submissionId"]
                        ?: metadata["lessonId"] ?: metadata["candidateEmail"],
                ),
                createdAtMs = (d["createdAtMs"] as? Number)?.toLong() ?: 0L,
                readBy = (d["readBy"] as? List<*>)?.map { it.toString().lowercase() } ?: emptyList(),
            )
        }
    }
}

/** مصدر تنبيهات الإدارة الحيّ + عمليّات القراءة/الحذف. */
object AdminAlertsFeed {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    val myEmail: String
        get() = AuthService.currentUser?.email.orEmpty().trim().lowercase()

    /**
     * بثّ التنبيهات المرئيّة لي: المالك يرى الكلّ، والمشرف تنبيهاته والعامّة
     * (مع استبعاد excludeEmail الموجَّه لغيره).
     */
    fun stream(isOwner: Boolean): Flow<List<AdminAlert>> {
        val me = myEmail

        fun visible(docs: Iterable<DocumentSnapshot>): List<AdminAlert> =
            docs.map { AdminAlert.fromDoc(it) }
                .filter { alert ->
                    when {
                        alert.excludeEmail == me -> false
                        !isOwner && alert.email.isNotEmpty() && alert.email != me -> false
                        else -> true
                    }
                }
                .sortedByDescending { it.createdAtMs }

        if (isOwner) {
            return db.collection("admin_alerts").querySnapshots().map { visible(it.documents) }
        }
        if (me.isEmpty()) return flowOf(emptyList())

        val personal = db.collection("admin_alerts").whereEqualTo("email", me).querySnapshots()
        val general = db.collection("admin_alerts").whereEqualTo("email", "").querySnapshots()
        return personal.combine(general) { a, b ->
            val merged = LinkedHashMap<String, DocumentSnapshot>()
            (a.documents + b.documents).forEach { merged[it.id] = it }
            visible(merged.values)
        }
    }

    /** عدد غير المقروء (لشارة بطاقة اللوحة). */
    fun unreadCount(isOwner: Boolean): Flow<Int> =
        stream(isOwner).map { list -> list.count { !it.isReadBy(myEmail) } }

    /** تمييز تنبيه مقروءاً (إضافة بريدي إلى readBy — تسمح به القواعد). */
    suspend fun markRead(alert: AdminAlert) {
        val e = myEmail
        if (e.isEmpty() || alert.isReadBy(e)) return
        // أفضل جهد — يبقى غير مقروء حتى المحاولة التالية.
        runCatching {
            db.collection("admin_alerts").document(alert.id)
                .update("readBy", FieldValue.arrayUnion(e)).await()
        }
    }

    /** تمييز كلّ الظاهر مقروءاً. */
    suspend fun markAllRead(alerts: List<AdminAlert>) {
        val e = myEmail
        if (e.isEmpty()) return
        val batch = db.batch()
        var n = 0
        for (alert in alerts.filter { !it.isReadBy(e) }) {
            batch.update(
                db.collection("admin_alerts").document(alert.id),
                "readBy",
                FieldValue.arrayUnion(e),
            )
            n++
            if (n >= 400) break // حدّ دفعة Firestore.
        }
        if (n > 0) runCatching { batch.commit().await() }
    }

    /**
     * حذف تنبيه — القواعد تسمح للمالك بحذف أيّ تنبيه، وللمشرف بحذف
     * تنبيهه الشخصي فقط.
     */
    suspend fun delete(alert: AdminAlert) {
        db.collection("admin_alerts").document(alert.id).delete().await()
    }

    fun canDelete(alert: AdminAlert, isOwner: Boolean): Boolean =
        isOwner || (alert.email.isNotEmpty() && alert.email == myEmail)
}
