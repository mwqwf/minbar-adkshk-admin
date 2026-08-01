package com.ali.ishaqiyin_admin.data

import android.content.Context
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/** عدّادات المحتوى الخفيفة التي يُبنى عليها قرار «هل تغيّر شيء؟». */
data class ContentCounts(
    val lessons: Int,
    val books: Int,
    val transcripts: Int,
)

/** لقطة إحصائيات محسوبة ومخزَّنة محلياً. */
data class AnalyticsSnapshot(
    val totalViews: Int,
    val lessonsCount: Int,
    val booksCount: Int,
    val transcriptsCount: Int,
    val newThisWeek: Int,
    val scheduled: Int,
    val topLessons: List<Pair<String, Int>>,
    val topSections: List<Pair<String, Int>>,
    val admins: List<Triple<String, Int, Int>>,
    val savedAtMs: Long,
) {
    val missingTranscripts: Int
        get() = (lessonsCount - transcriptsCount).coerceAtLeast(0)
}

/**
 * 📊 إحصائيات بكاش محلّي: الشاشة تفتح فوراً من آخر لقطة محفوظة (حتى بلا
 * إنترنت)، ولا يُعاد الجلب الكامل إلا إذا تغيّر عدد الدروس أو الكتب أو
 * النصوص المشروحة (استعلامات count() خفيفة) أو طلب المشرف التحديث يدوياً.
 */
object AnalyticsRepository {
    private const val PREFS = "analytics_cache_v1"
    private const val KEY = "snapshot"
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    fun loadCache(context: Context): AnalyticsSnapshot? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        val o = JSONObject(raw)
        AnalyticsSnapshot(
            totalViews = o.optInt("totalViews"),
            lessonsCount = o.optInt("lessonsCount"),
            booksCount = o.optInt("booksCount"),
            transcriptsCount = o.optInt("transcriptsCount"),
            newThisWeek = o.optInt("newThisWeek"),
            scheduled = o.optInt("scheduled"),
            topLessons = o.optJSONArray("topLessons").toPairs(),
            topSections = o.optJSONArray("topSections").toPairs(),
            admins = o.optJSONArray("admins").toTriples(),
            savedAtMs = o.optLong("savedAtMs"),
        )
    }.getOrNull()

    private fun save(context: Context, s: AnalyticsSnapshot) {
        val o = JSONObject()
            .put("totalViews", s.totalViews)
            .put("lessonsCount", s.lessonsCount)
            .put("booksCount", s.booksCount)
            .put("transcriptsCount", s.transcriptsCount)
            .put("newThisWeek", s.newThisWeek)
            .put("scheduled", s.scheduled)
            .put("topLessons", s.topLessons.toJson())
            .put("topSections", s.topSections.toJson())
            .put(
                "admins",
                JSONArray().also { arr ->
                    s.admins.forEach { (email, count, views) ->
                        arr.put(
                            JSONObject().put("a", email).put("b", count).put("c", views),
                        )
                    }
                },
            )
            .put("savedAtMs", s.savedAtMs)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    /** عدّادات خفيفة من الخادم (استعلام count تجميعي — لا يجلب الوثائق). */
    suspend fun fetchCounts(): ContentCounts {
        val lessons = db.collection("lessons").count()
            .get(AggregateSource.SERVER).await().count.toInt()
        val books = db.collection("books").count()
            .get(AggregateSource.SERVER).await().count.toInt()
        val transcripts = db.collection("lesson_transcripts").count()
            .get(AggregateSource.SERVER).await().count.toInt()
        return ContentCounts(lessons, books, transcripts)
    }

    /** هل تطابق اللقطة المحفوظة عدّادات الخادم الحالية؟ */
    fun matches(s: AnalyticsSnapshot, c: ContentCounts): Boolean =
        s.lessonsCount == c.lessons &&
            s.booksCount == c.books &&
            s.transcriptsCount == c.transcripts

    /** الجلب الكامل وإعادة الحساب والحفظ. */
    suspend fun compute(context: Context, counts: ContentCounts?): AnalyticsSnapshot {
        val lessons = AdminRepository.fetchLessons()
        val subs = AdminRepository.fetchSubcategories()
        val c = counts ?: fetchCounts()
        val now = System.currentTimeMillis()
        val weekMs = 7L * 24 * 3600 * 1000
        fun subName(id: String) = subs.firstOrNull { it.id == id }?.name ?: "—"
        val snapshot = AnalyticsSnapshot(
            totalViews = lessons.sumOf { it.views },
            lessonsCount = c.lessons,
            booksCount = c.books,
            transcriptsCount = c.transcripts,
            newThisWeek = lessons.count { now - it.createdAtMs < weekMs },
            scheduled = lessons.count { (it.publishAtMs ?: 0) > now },
            topLessons = lessons.filter { it.views > 0 }
                .sortedByDescending { it.views }
                .take(10)
                .map { it.title.ifEmpty { "بدون عنوان" } to it.views },
            topSections = lessons.filter { it.subcategoryId.isNotEmpty() }
                .groupBy { it.subcategoryId }
                .mapValues { entry -> entry.value.sumOf { it.views } }
                .toList()
                .sortedByDescending { it.second }
                .take(8)
                .map { (id, views) -> subName(id) to views },
            admins = lessons.filter { it.addedBy.isNotEmpty() }
                .groupBy { it.addedBy }
                .map { (email, items) -> Triple(email, items.size, items.sumOf { it.views }) }
                .sortedByDescending { it.third },
            savedAtMs = now,
        )
        save(context, snapshot)
        return snapshot
    }

    private fun List<Pair<String, Int>>.toJson(): JSONArray =
        JSONArray().also { arr ->
            forEach { (a, b) -> arr.put(JSONObject().put("a", a).put("b", b)) }
        }

    private fun JSONArray?.toPairs(): List<Pair<String, Int>> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optJSONObject(i)?.let { it.optString("a") to it.optInt("b") }
        }
    }

    private fun JSONArray?.toTriples(): List<Triple<String, Int, Int>> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optJSONObject(i)?.let {
                Triple(it.optString("a"), it.optInt("b"), it.optInt("c"))
            }
        }
    }
}
