package com.ali.ishaqiyin_admin.data

import android.content.Context
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * مقاييس «هل تغيّر شيء؟» الخفيفة. الأعداد وحدها لا تكفي: تعديل عنوان درس
 * أو ارتفاع عدّاد الاستماع لا يغيّر أيّ عدّاد، فتبقى اللقطة مجمّدة أسابيع —
 * ولذلك أُضيف [lastUpdatedMs] (أحدث `updatedAt` في الدروس).
 */
data class ContentCounts(
    val lessons: Int,
    val books: Int,
    val transcripts: Int,
    val lastUpdatedMs: Long = 0L,
)

/** حصيلة مشرف في لوحة الشرف موزَّعة على الأسبوع والشهر وكل الزمن. */
data class AdminScore(
    val email: String,
    val weekLessons: Int,
    val weekViews: Int,
    val monthLessons: Int,
    val monthViews: Int,
    val allLessons: Int,
    val allViews: Int,
)

/** نطاق زمني للوحة الشرف. */
enum class HonorRange { WEEK, MONTH, ALL }

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
    val admins: List<AdminScore>,
    val savedAtMs: Long,
    /** أحدث `updatedAt` رآه المسبار وقت الحساب (مقياس إبطال رابع). */
    val lastUpdatedMs: Long = 0L,
) {
    val missingTranscripts: Int
        get() = (lessonsCount - transcriptsCount).coerceAtLeast(0)

    /** ترتيب لوحة الشرف ضمن نطاق زمني — الوافد الجديد يزاحم الصدارة. */
    fun adminsIn(range: HonorRange): List<Triple<String, Int, Int>> = admins
        .map { a ->
            when (range) {
                HonorRange.WEEK -> Triple(a.email, a.weekLessons, a.weekViews)
                HonorRange.MONTH -> Triple(a.email, a.monthLessons, a.monthViews)
                HonorRange.ALL -> Triple(a.email, a.allLessons, a.allViews)
            }
        }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.third }
            .thenByDescending { it.second })
}

/** نتيجة فحص الطزاجة: هل اللقطة بائتة، ومعها العدّادات إن جُلبت فعلاً. */
data class FreshnessCheck(val stale: Boolean, val counts: ContentCounts?)

/**
 * 📊 إحصائيات بكاش محلّي: الشاشة تفتح فوراً من آخر لقطة محفوظة (حتى بلا
 * إنترنت)، ولا يُعاد الجلب الكامل إلا إذا تغيّر عدد الدروس أو الكتب أو
 * النصوص المشروحة، **أو تغيّر أحدث `updatedAt` في الدروس**، أو مضت على
 * اللقطة ست ساعات، أو طلب المشرف التحديث يدوياً.
 */
object AnalyticsRepository {
    private const val PREFS = "analytics_cache_v1"
    private const val KEY = "snapshot"

    /** عمر أقصى للّقطة مهما بقيت الأعداد ثابتة (ارتفاع الاستماع لا يُعدّ). */
    private const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** حارس: لقطة عمرها أقلّ من خمس دقائق لا تستحقّ حتى مسبار قراءة. */
    private const val PROBE_GUARD_MS = 5L * 60 * 1000

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
            admins = o.optJSONArray("admins").toAdminScores(),
            savedAtMs = o.optLong("savedAtMs"),
            lastUpdatedMs = o.optLong("lastUpdatedMs"),
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
                    s.admins.forEach { a ->
                        arr.put(
                            JSONObject()
                                .put("a", a.email)
                                .put("wl", a.weekLessons)
                                .put("wv", a.weekViews)
                                .put("ml", a.monthLessons)
                                .put("mv", a.monthViews)
                                .put("b", a.allLessons)
                                .put("c", a.allViews),
                        )
                    }
                },
            )
            .put("savedAtMs", s.savedAtMs)
            .put("lastUpdatedMs", s.lastUpdatedMs)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    /**
     * أحدث طابع تعديل في الدروس — قراءة واحدة (وثيقة واحدة). يلتقط تعديل
     * عنوان أو قسم أو تمييز لا يغيّر أيّ عدّاد. غيابه (وثائق قديمة بلا
     * `updatedAt`) يعيد صفراً فلا يُبطل شيئاً بغير حقّ.
     */
    private suspend fun latestLessonUpdateMs(): Long = runCatching {
        val snap = db.collection("lessons")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()
        snap.documents.firstOrNull()?.let { parseDateMs(it.dataMap()["updatedAt"]) } ?: 0L
    }.getOrDefault(0L)

    /**
     * عدّادات خفيفة من الخادم (استعلام count تجميعي — لا يجلب الوثائق).
     * الاستعلامات الأربعة مستقلّة فتُطلق **متوازيةً**: أربع جولات شبكة
     * متتابعة كانت تضاعف انتظار فتح الشاشة على الشبكات البطيئة.
     */
    suspend fun fetchCounts(): ContentCounts = coroutineScope {
        val lessons = async {
            db.collection("lessons").count().get(AggregateSource.SERVER).await().count.toInt()
        }
        val books = async {
            db.collection("books").count().get(AggregateSource.SERVER).await().count.toInt()
        }
        val transcripts = async {
            db.collection("lesson_transcripts").count()
                .get(AggregateSource.SERVER).await().count.toInt()
        }
        val latest = async { latestLessonUpdateMs() }
        ContentCounts(lessons.await(), books.await(), transcripts.await(), latest.await())
    }

    /** هل تطابق اللقطة المحفوظة مقاييس الخادم الحالية؟ */
    fun matches(s: AnalyticsSnapshot, c: ContentCounts): Boolean =
        s.lessonsCount == c.lessons &&
            s.booksCount == c.books &&
            s.transcriptsCount == c.transcripts &&
            s.lastUpdatedMs == c.lastUpdatedMs

    /**
     * قرار «هل نُعيد الحساب؟» كاملاً في مكان واحد:
     * 1. لا لقطة ⇒ احسب.
     * 2. لقطة عمرها أقلّ من خمس دقائق ⇒ لا مسبار أصلاً.
     * 3. لقطة تجاوزت ست ساعات ⇒ بائتة مهما كانت الأعداد (الاستماع يرتفع
     *    بلا أثر في أيّ عدّاد، وعليه تُبنى «الأكثر استماعاً» و«لوحة الشرف»).
     * 4. غير ذلك ⇒ قارن الأعداد وأحدث `updatedAt`.
     */
    suspend fun checkFreshness(cached: AnalyticsSnapshot?): FreshnessCheck {
        if (cached == null) return FreshnessCheck(stale = true, counts = null)
        val age = System.currentTimeMillis() - cached.savedAtMs
        if (age in 0 until PROBE_GUARD_MS) return FreshnessCheck(stale = false, counts = null)
        if (age >= MAX_AGE_MS) return FreshnessCheck(stale = true, counts = null)
        val counts = fetchCounts()
        return FreshnessCheck(stale = !matches(cached, counts), counts = counts)
    }

    /** الجلب الكامل وإعادة الحساب والحفظ. */
    suspend fun compute(context: Context, counts: ContentCounts?): AnalyticsSnapshot {
        val lessons = AdminRepository.fetchLessons()
        val subs = AdminRepository.fetchSubcategories()
        val c = counts ?: fetchCounts()
        val now = System.currentTimeMillis()
        val weekMs = 7L * 24 * 3600 * 1000
        val monthMs = 30L * 24 * 3600 * 1000
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
                .map { (email, all) ->
                    val week = all.filter { now - it.createdAtMs < weekMs }
                    val month = all.filter { now - it.createdAtMs < monthMs }
                    AdminScore(
                        email = email,
                        weekLessons = week.size,
                        weekViews = week.sumOf { it.views },
                        monthLessons = month.size,
                        monthViews = month.sumOf { it.views },
                        allLessons = all.size,
                        allViews = all.sumOf { it.views },
                    )
                }
                .sortedByDescending { it.allViews },
            savedAtMs = now,
            lastUpdatedMs = c.lastUpdatedMs,
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

    /** يقرأ لقطات ما قبل التوزيع الزمني أيضاً (فيها «الكل» فقط). */
    private fun JSONArray?.toAdminScores(): List<AdminScore> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optJSONObject(i)?.let {
                AdminScore(
                    email = it.optString("a"),
                    weekLessons = it.optInt("wl"),
                    weekViews = it.optInt("wv"),
                    monthLessons = it.optInt("ml"),
                    monthViews = it.optInt("mv"),
                    allLessons = it.optInt("b"),
                    allViews = it.optInt("c"),
                )
            }
        }
    }
}
