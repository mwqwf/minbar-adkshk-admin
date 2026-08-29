package com.ali.ishaqiyin_admin.ui

import com.ali.ishaqiyin_admin.data.dataMap
import com.ali.ishaqiyin_admin.data.querySnapshots
import com.ali.ishaqiyin_admin.data.str
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * 🗂️ قسم محذوف في السلة — كانت وثيقة القسم تُمحى نهائياً بينما دروسه
 * تنجو في السلة، فيُعيد المشرف بناء القسم يدوياً ثم يستعيد دروسه واحداً
 * واحداً ثم ينقلها إليه. الآن يعود القسم بمعرّفه الأصلي بنقرة واحدة،
 * فيرجع إليه كلّ درس مستعاد تلقائياً.
 */
data class TrashedSection(
    /** معرّف وثيقة السلة (مركّب: النوع + معرّف القسم). */
    val entryId: String,
    /** "category" أو "subcategory". */
    val kind: String,
    val docId: String,
    val name: String,
    val parentCategoryId: String,
    val deletedBy: String,
    val deletedAtMs: Long,
    val purgeAfterMs: Long,
) {
    val isCategory: Boolean get() = kind == "category"

    val daysLeft: Int
        get() = (((purgeAfterMs - System.currentTimeMillis()) / 86_400_000L) + 1)
            .coerceAtLeast(0L).toInt()

    companion object {
        fun fromDoc(doc: DocumentSnapshot): TrashedSection {
            val d = doc.dataMap()
            return TrashedSection(
                entryId = doc.id,
                kind = str(d["kind"]).ifEmpty { "subcategory" },
                docId = str(d["docId"]),
                name = str(d["name"]),
                parentCategoryId = str(d["parentCategoryId"]),
                deletedBy = str(d["deletedBy"]),
                deletedAtMs = (d["deletedAtMs"] as? Number)?.toLong() ?: 0L,
                purgeAfterMs = (d["purgeAfterMs"] as? Number)?.toLong() ?: 0L,
            )
        }
    }
}

/**
 * سلّة الأقسام — بنفس نمط `TrashRepository` تماماً: بثّ محدود بالأحدث،
 * واستعادة/محو عبر دوالّ السحابة (الكتابة المباشرة ممنوعة بالقواعد).
 */
object DeletedSectionsRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()
    const val COLLECTION = "deleted_sections"

    // سقف كسقف سلّة الدروس: بثّ حيّ بلا حدّ يكبر بلا نهاية مع كل حذف.
    private const val WATCH_LIMIT = 100L

    fun watchAll(): Flow<List<TrashedSection>> =
        db.collection(COLLECTION)
            .orderBy("deletedAtMs", Query.Direction.DESCENDING)
            .limit(WATCH_LIMIT)
            .querySnapshots()
            .map { snap -> snap.documents.map { TrashedSection.fromDoc(it) } }
            .flowOn(Dispatchers.Default)

    suspend fun restore(item: TrashedSection) {
        functions.getHttpsCallable("restoreDeletedSection")
            .call(mapOf("entryId" to item.entryId)).await()
        // القسم عاد للحياة — كاش الأقسام يجب ألّا يخفيه 5 دقائق.
        com.ali.ishaqiyin_admin.data.AdminRepository.invalidateSectionsCache()
    }

    suspend fun purge(item: TrashedSection) {
        functions.getHttpsCallable("purgeDeletedSection")
            .call(mapOf("entryId" to item.entryId)).await()
    }
}

/** «قسم واحد»/«قسمان»/«ن أقسام»/«ن قسماً». */
fun sectionsCountLabel(count: Int): String =
    arabicCount(count, "قسم واحد", "قسمان", "أقسام", "قسماً")
