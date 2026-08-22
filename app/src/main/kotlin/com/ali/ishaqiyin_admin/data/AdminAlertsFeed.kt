package com.ali.ishaqiyin_admin.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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

    /**
     * عمر التنبيه: 24 ساعة فقط. التنبيه إشعار لحظيّ لا سجلّ دائم، وبقاؤه
     * أطول كان يحوّل «تنبيهاتك» إلى أرشيف لا يُقرأ.
     */
    const val TTL_MS = 24L * 60 * 60 * 1000

    /** سقف ما يُنزَّل من المجموعة — أكثر من هذا لا يُعرض بحكم [TTL_MS]. */
    private const val ALERTS_LIMIT = 200L

    val myEmail: String
        get() = AuthService.currentUser?.email.orEmpty().trim().lowercase()

    /**
     * بثّ التنبيهات المرئيّة لي: المالك يرى الكلّ، والمشرف تنبيهاته والعامّة
     * (مع استبعاد excludeEmail الموجَّه لغيره).
     */
    fun stream(isOwner: Boolean): Flow<List<AdminAlert>> {
        val me = myEmail

        fun visible(docs: Iterable<DocumentSnapshot>): List<AdminAlert> {
            // يُحسب مع كل انبعاث: حسابه مرّة عند إنشاء التدفّق يجمّد حدّ
            // الـ24 ساعة على لحظة الفتح فلا يسقط التنبيه أبداً بعدها.
            val cutoff = System.currentTimeMillis() - TTL_MS
            return docs.map { AdminAlert.fromDoc(it) }
                .filter { alert ->
                    when {
                        alert.excludeEmail == me -> false
                        !isOwner && alert.email.isNotEmpty() && alert.email != me -> false
                        // أقدم من 24 ساعة ⇒ لم يعد تنبيهاً.
                        alert.createdAtMs in 1 until cutoff -> false
                        // اطّلع عليه صاحبه ⇒ يختفي عنه هو وحده (يبقى عند غيره).
                        alert.isReadBy(me) -> false
                        else -> true
                    }
                }
                .sortedByDescending { it.createdAtMs }
        }

        // سقف خادميّ: المجموعة تتراكم (التنظيف الخادمي لا يمسّ milestone/digest)
        // فبلا حدٍّ تُنزَّل كاملةً في المزامنة الأولى ويُعاد ترشيحها محليّاً مع
        // كل كتابة readBy. و‏TTL الـ24 ساعة يجعل ما وراء أحدث 200 غير مرئيّ أصلاً.
        if (isOwner) {
            return db.collection("admin_alerts")
                .orderBy("createdAtMs", Query.Direction.DESCENDING)
                .limit(ALERTS_LIMIT)
                .querySnapshots()
                .map { visible(it.documents) }
        }
        if (me.isEmpty()) return flowOf(emptyList())

        val personal = db.collection("admin_alerts")
            .whereEqualTo("email", me).limit(ALERTS_LIMIT).querySnapshots()
        val general = db.collection("admin_alerts")
            .whereEqualTo("email", "").limit(ALERTS_LIMIT).querySnapshots()
        return personal.combine(general) { a, b ->
            val merged = LinkedHashMap<String, DocumentSnapshot>()
            (a.documents + b.documents).forEach { merged[it.id] = it }
            visible(merged.values)
        }
    }

    /** تمييز تنبيه مقروءاً (إضافة بريدي إلى readBy — تسمح به القواعد). */
    fun markRead(alert: AdminAlert) {
        val e = myEmail
        if (e.isEmpty() || alert.isReadBy(e)) return
        // بلا await: الكتابة تُطبَّق محليّاً فيختفي التنبيه فوراً حتى دون
        // اتصال، وتُرسَل للخادم تلقائياً عند عودة الشبكة.
        runCatching {
            db.collection("admin_alerts").document(alert.id)
                .update("readBy", FieldValue.arrayUnion(e))
        }
    }

    /** تمييز كلّ الظاهر مقروءاً. */
    fun markAllRead(alerts: List<AdminAlert>) {
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
        if (n > 0) runCatching { batch.commit() }
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
