package com.ali.ishaqiyin_admin.data

import com.ali.ishaqiyin_admin.core.MinbarAdminApi
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * مستودع المحتوى في اللوحة — على `minbar-api` (Cloudflare D1) منذ 2026-09-10.
 *
 * الواجهة العامة للدوال كما كانت (الشاشات لا تتغيّر)، والفرق في المصدر:
 * - القراءة: `/admin/{categories|subcategories|lessons}` بطلب واحد لكل مجموعة.
 * - الكتابة: `PUT /admin/{table}/{id}` (إنشاء أو تعديل بالمفتاح نفسه — تكراري
 *   الأمان كما كان `clientKey`)، و`DELETE` حذفٌ ناعم متسلسل إلى السلة.
 * - لم يبقَ لـFirestore هنا إلا رموز اعتماد المرشّحين (تنتقل في المرحلة الثانية).
 */
object AdminRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private const val OWNER_CODES_COL = "dashboard_owner_codes"
    private const val SECTIONS_TTL_MS = 5 * 60 * 1000L
    private const val WATCH_POLL_MS = 30_000L

    @Volatile
    private var categoriesCache: Pair<Long, List<Category>>? = null
    @Volatile
    private var subcategoriesCache: Pair<Long, List<Subcategory>>? = null

    fun invalidateSectionsCache() {
        categoriesCache = null
        subcategoriesCache = null
    }

    // ---------------- تحويل صفوف D1 إلى النماذج ----------------

    private fun JSONArray?.rows(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun categoryFromRow(row: JSONObject): Category = Category.fromDoc(
        row.optString("id"),
        mapOf("name" to row.optString("name"), "createdAt" to row.optLong("created_at_ms")),
    )

    private fun subcategoryFromRow(row: JSONObject): Subcategory = Subcategory.fromDoc(
        row.optString("id"),
        mapOf(
            "name" to row.optString("name"),
            "categoryId" to row.optString("category_id"),
            "createdAt" to row.optLong("created_at_ms"),
        ),
    )

    internal fun lessonFromRow(row: JSONObject): Lesson {
        val sha = row.optString("sha256")
        val originalKey = row.optString("original_key")
        return Lesson.fromDoc(
            row.optString("id"),
            mapOf(
                "title" to row.optString("title"),
                "categoryId" to row.optString("category_id"),
                "subcategoryId" to row.optString("subcategory_id"),
                "audioUrl" to row.optString("audio_url"),
                "audioStoragePath" to originalKey.ifEmpty { if (sha.isNotEmpty()) "serving/$sha.ogg" else "" },
                "createdAt" to row.optLong("created_at_ms").takeIf { it > 0L },
                "views" to row.optInt("views"),
                "featured" to (row.optInt("featured") == 1),
                "featuredUntil" to row.optLong("featured_until_ms").takeIf { it > 0L },
            ),
        )
    }

    private fun adminFromRow(row: JSONObject): DashAdmin {
        val role = row.optString("role").ifEmpty { "supervisor" }
        return DashAdmin.fromDoc(
            row.optString("email"),
            mapOf(
                "email" to row.optString("email"),
                "role" to if (role == "blocked") "supervisor" else role,
                "blocked" to (role == "blocked"),
                "blockMode" to if (role == "blocked") "permanent" else "",
                "addedBy" to "minbar-api",
                "addedAt" to row.optLong("added_at_ms"),
            ),
        )
    }

    // ---------------- قراءة ----------------

    suspend fun fetchCategories(): List<Category> {
        val now = System.currentTimeMillis()
        categoriesCache?.let { (at, value) -> if (now - at < SECTIONS_TTL_MS) return value }
        val rows = MinbarAdminApi.get("/admin/categories").optJSONArray("items").rows()
        return withContext(Dispatchers.Default) {
            rows.map(::categoryFromRow).sortedBy { it.name }
        }.also { categoriesCache = System.currentTimeMillis() to it }
    }

    suspend fun fetchSubcategories(): List<Subcategory> {
        val now = System.currentTimeMillis()
        subcategoriesCache?.let { (at, value) -> if (now - at < SECTIONS_TTL_MS) return value }
        val rows = MinbarAdminApi.get("/admin/subcategories").optJSONArray("items").rows()
        return withContext(Dispatchers.Default) { rows.map(::subcategoryFromRow) }
            .also { subcategoriesCache = System.currentTimeMillis() to it }
    }

    private suspend fun fetchLessonRows(): List<JSONObject> =
        MinbarAdminApi.get("/admin/lessons").optJSONArray("items").rows()

    suspend fun fetchLessons(limit: Long? = null): List<Lesson> {
        val rows = fetchLessonRows()
        return withContext(Dispatchers.Default) {
            val all = rows.map(::lessonFromRow).sortedByDescending { it.createdAtMs }
            if (limit != null && limit > 0) all.take(limit.toInt()) else all
        }
    }

    suspend fun fetchSubcategoryLessons(subcategoryId: String): List<Lesson> {
        val rows = fetchLessonRows().filter { it.optString("subcategory_id") == subcategoryId }
        return withContext(Dispatchers.Default) {
            rows.sortedWith(compareBy<JSONObject> { it.optLong("created_at_ms") }).map(::lessonFromRow)
        }
    }

    // ---------------- المشرفون ----------------

    suspend fun fetchDashAdmins(): List<DashAdmin> =
        MinbarAdminApi.get("/admin/admins").optJSONArray("items").rows()
            .map(::adminFromRow)
            .sortedByDescending { it.lastSignedInAtMs ?: it.addedAtMs }

    suspend fun getDashAdmin(email: String): DashAdmin? {
        val id = email.trim().lowercase()
        if (id.isEmpty()) return null
        return fetchDashAdmins().firstOrNull { it.email.equals(id, ignoreCase = true) }
    }

    /** المالك مسجَّل في جدول المشرفين على الخادم؛ لا شيء يُكتب عند دخوله. */
    fun upsertOwnerRecord(email: String, displayName: String = "", photoURL: String = "") = Unit

    fun touchLastSignedIn(email: String) = Unit

    suspend fun setDashAdminBlocked(email: String, blocked: Boolean, mode: String = "temporary") {
        val id = email.trim().lowercase()
        if (id.isEmpty()) return
        MinbarAdminApi.put(
            "/admin/admins",
            JSONObject().put("email", id).put("role", if (blocked) "blocked" else "supervisor"),
        )
    }

    suspend fun removeDashAdmin(email: String) {
        val id = email.trim().lowercase()
        if (id.isEmpty()) return
        MinbarAdminApi.delete("/admin/admins/${java.net.URLEncoder.encode(id, "UTF-8")}")
    }

    /** اعتماد مشرف جديد ببريده (يُستعمل بعد التحقق من رمز المالك). */
    suspend fun addDashAdmin(email: String) {
        val id = email.trim().lowercase()
        if (id.isEmpty()) return
        MinbarAdminApi.put("/admin/admins", JSONObject().put("email", id).put("role", "supervisor"))
    }

    // رموز اعتماد المرشّحين — ما زالت على Firestore حتى المرحلة الثانية.
    fun watchPendingOwnerCodes(): Flow<List<PendingOwnerCode>> =
        db.collection(OWNER_CODES_COL).querySnapshots().map { snap ->
            snap.documents
                .filter { it.id != "current" }
                .map { PendingOwnerCode.fromDoc(it.dataMap()) }
                .filter { !it.isExpired && it.code.isNotEmpty() }
                .sortedByDescending { it.expiresAtMs }
        }

    suspend fun cancelOwnerCode(code: PendingOwnerCode) {
        val email = code.candidateEmail.trim().lowercase()
        if (email.isNotEmpty()) {
            db.collection(OWNER_CODES_COL).document(email).delete().await()
        }
        runCatching {
            val mirrorRef = db.collection(OWNER_CODES_COL).document("current")
            val mirror = mirrorRef.get().await()
            val mirrorEmail = str(mirror.dataMap()["candidateEmail"]).lowercase()
            if (mirror.exists() && (mirrorEmail.isEmpty() || mirrorEmail == email)) {
                mirrorRef.delete().await()
            }
        }
    }

    // ---------------- إضافة ----------------

    /**
     * مفتاح ثابت مشتقّ من محتوى القسم يُستعمل **معرّفاً للصفّ**: نفس الاسم
     * (ونفس الأب للفرعي) يعطي المعرّف نفسه، فتُصبح الكتابة تكراريّة الأمان —
     * محاولتان لنفس القسم تكتبان صفاً واحداً.
     */
    private fun sectionKey(prefix: String, vararg parts: String): String {
        val raw = parts.joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return prefix + digest.joinToString("") { byte -> "%02x".format(java.util.Locale.ROOT, byte.toInt() and 0xff) }
    }

    suspend fun addCategory(name: String): Boolean {
        val clean = name.trim()
        val key = sectionKey("cat_", clean)
        invalidateSectionsCache()
        return runCatching {
            MinbarAdminApi.put(
                "/admin/categories/$key",
                JSONObject().put("name", clean).put("createdAtMs", System.currentTimeMillis()),
            )
        }.isSuccess
    }

    suspend fun addSubcategory(name: String, categoryId: String): Boolean {
        val clean = name.trim()
        val key = sectionKey("sub_", categoryId, clean)
        invalidateSectionsCache()
        return runCatching {
            MinbarAdminApi.put(
                "/admin/subcategories/$key",
                JSONObject().put("name", clean).put("categoryId", categoryId)
                    .put("createdAtMs", System.currentTimeMillis()),
            )
        }.isSuccess
    }

    /**
     * إنشاء درس. إن كان [audioStoragePath] أصلاً في R2 (`originals/…`) سُجّل
     * الدرس بحالة `pending` وشُغّل خطّ الترميز (GitHub Actions) الذي يثبّته
     * ready ببصمته — فلا يظهر للمستخدمين إلا بعد أن يصير ملفه القانوني جاهزاً.
     * وإن جاء برابط جاهز (`serving/…`) سُجّل ready مباشرة.
     */
    suspend fun addLesson(
        title: String,
        categoryId: String,
        subcategoryId: String,
        audioUrl: String,
        categoryName: String = "",
        subcategoryName: String = "",
        audioStoragePath: String? = null,
        addedBy: String = "",
        featured: Boolean = false,
        featuredUntilMs: Long? = null,
        createdAtMs: Long? = null,
        clientKey: String? = null,
        sourceSha256: String = "",
    ): String {
        val id = clientKey?.takeIf { it.isNotBlank() } ?: newLessonId()
        val pending = audioStoragePath.orEmpty().startsWith("originals/")
        val body = JSONObject()
            .put("title", title.trim())
            .put("categoryId", categoryId)
            .put("subcategoryId", subcategoryId)
            .put("audioUrl", if (pending) "" else audioUrl)
            .put("createdAtMs", createdAtMs ?: System.currentTimeMillis())
            .put("featured", featured)
            .put("featuredUntilMs", featuredUntilMs ?: 0L)
            .put("audioStatus", if (pending) "pending" else "ready")
        if (pending) body.put("originalKey", audioStoragePath).put("sourceSha256", sourceSha256)
        if (addedBy.isNotEmpty()) body.put("extraJson", JSONObject().put("addedBy", addedBy.lowercase()).toString())
        MinbarAdminApi.put("/admin/lessons/$id", body)
        if (pending) {
            MinbarAdminApi.post(
                "/admin/lessons/$id/transcode",
                JSONObject().put("originalKey", audioStoragePath).put("sourceSha256", sourceSha256),
            )
        }
        return id
    }

    private fun newLessonId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    suspend fun reorderSubcategoryLessons(subcategoryId: String, lessonIds: List<String>) {
        MinbarAdminApi.post(
            "/admin/lessons/reorder",
            JSONObject().put("subcategoryId", subcategoryId).put("lessonIds", JSONArray(lessonIds)),
        )
    }

    suspend fun setLessonFeatured(id: String, featured: Boolean, untilMs: Long? = null) {
        MinbarAdminApi.put(
            "/admin/lessons/$id",
            JSONObject().put("featured", featured).put("featuredUntilMs", if (featured) (untilMs ?: 0L) else 0L),
        )
    }

    fun watchFeatured(): Flow<List<Lesson>> = flow {
        while (true) {
            emit(
                fetchLessonRows().filter { it.optInt("featured") == 1 }.map(::lessonFromRow)
                    .sortedWith(
                        compareBy<Lesson> { it.featuredUntilMs ?: Long.MAX_VALUE }
                            .thenByDescending { it.createdAtMs },
                    ),
            )
            delay(WATCH_POLL_MS)
        }
    }.flowOn(Dispatchers.IO)

    // ---------------- تعديل ----------------

    suspend fun updateCategory(id: String, name: String) {
        invalidateSectionsCache()
        MinbarAdminApi.put("/admin/categories/$id", JSONObject().put("name", name.trim()))
    }

    suspend fun updateSubcategory(id: String, name: String) {
        invalidateSectionsCache()
        MinbarAdminApi.put("/admin/subcategories/$id", JSONObject().put("name", name.trim()))
    }

    suspend fun updateLessonTitle(id: String, title: String) {
        MinbarAdminApi.put("/admin/lessons/$id", JSONObject().put("title", title.trim()))
    }

    data class MoveLessonResult(val placedLast: Boolean)

    suspend fun moveLessonToSubcategory(
        lesson: Lesson,
        target: Subcategory,
        targetCategoryName: String = "",
        reorderAfterMove: Boolean = true,
    ): MoveLessonResult {
        if (target.id == lesson.subcategoryId) return MoveLessonResult(true)
        MinbarAdminApi.put(
            "/admin/lessons/${lesson.id}",
            JSONObject().put("categoryId", target.categoryId).put("subcategoryId", target.id),
        )
        if (!reorderAfterMove) return MoveLessonResult(true)
        val placedLast = runCatching {
            val destination = fetchSubcategoryLessons(target.id)
            if (destination.size < 2) return@runCatching true
            val ordered = destination.filter { it.id != lesson.id }.sortedBy { it.createdAtMs }.map { it.id } + lesson.id
            reorderSubcategoryLessons(target.id, ordered)
            true
        }.getOrDefault(false)
        return MoveLessonResult(placedLast)
    }

    // ---------------- حذف (ناعم إلى السلة، متسلسل) ----------------

    suspend fun deleteCategory(id: String) {
        MinbarAdminApi.delete("/admin/categories/$id")
        invalidateSectionsCache()
    }

    suspend fun deleteSubcategory(id: String) {
        MinbarAdminApi.delete("/admin/subcategories/$id")
        invalidateSectionsCache()
    }

    suspend fun deleteLesson(lesson: Lesson) {
        MinbarAdminApi.delete("/admin/lessons/${lesson.id}")
    }

    // ---------------- آخر التغييرات ----------------

    data class RecentChange(
        val id: String,
        val kind: String,
        val name: String,
        val byEmail: String,
        val atMs: Long,
    )

    suspend fun fetchRecentChanges(limit: Int = 50): List<RecentChange> {
        val found = mutableListOf<RecentChange>()
        listOf(
            Triple("lessons", "lesson", "title"),
            Triple("categories", "category", "name"),
            Triple("subcategories", "subcategory", "name"),
        ).forEach { (table, kind, nameKey) ->
            runCatching { MinbarAdminApi.get("/admin/$table?all=1").optJSONArray("items").rows() }
                .getOrDefault(emptyList())
                .forEach { row ->
                    val atMs = row.optLong("updated_at_ms")
                    if (atMs > 0L) {
                        found += RecentChange(
                            id = row.optString("id"),
                            kind = kind,
                            name = row.optString(nameKey),
                            byEmail = "",
                            atMs = atMs,
                        )
                    }
                }
        }
        return withContext(Dispatchers.Default) { found.sortedByDescending { it.atMs }.take(limit) }
    }

    // ---------------- ملاحظات المستمعين ----------------

    suspend fun fetchFeedback(): List<Map<String, Any?>> =
        MinbarAdminApi.get("/admin/feedback").optJSONArray("items").rows().map { row ->
            mapOf(
                "id" to row.optString("id"),
                "type" to row.optString("kind"),
                "note" to row.optString("text"),
                "contact" to row.optString("contact"),
                "lessonId" to row.optString("lesson_id"),
                "createdAtMs" to row.optLong("created_at_ms"),
            )
        }.sortedByDescending { (it["createdAtMs"] as? Number)?.toLong() ?: 0L }

    suspend fun deleteFeedback(id: String) {
        MinbarAdminApi.delete("/admin/feedback/$id")
    }

    /** تنبيهات المشرفين المحلولة تُكنَس على الخادم دورياً — لا شيء هنا. */
    suspend fun cleanupResolvedAdminAlerts() = Unit
}
