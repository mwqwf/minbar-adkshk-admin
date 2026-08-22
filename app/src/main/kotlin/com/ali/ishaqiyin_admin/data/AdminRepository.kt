package com.ali.ishaqiyin_admin.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * كل عمليات القراءة/الكتابة على Firestore لإدارة محتوى منبر.
 * المجموعات: categories, subcategories, lessons,
 * dashboard_admins, dashboard_owner_codes.
 */
object AdminRepository {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance()

    private const val DASH_COL = "dashboard_admins"
    private const val OWNER_CODES_COL = "dashboard_owner_codes"

    /**
     * مهلة انتظار تأكيد الخادم لكتابة قسم. بعدها نُعلن للمشرف أنّ الطلب
     * محفوظ وسيُرسَل عند عودة الشبكة بدل تركه أمام مؤشّر لا ينتهي.
     */
    private const val SECTION_WRITE_TIMEOUT_MS = 12_000L

    // ---------------- جلب ----------------
    // فكّ الترميز والفرز خارج الخيط الرئيسي: `await()` يستأنف على سياق
    // المستدعي (Main في LaunchedEffect)، فكانت مئات الوثائق تُحوَّل هناك.
    suspend fun fetchCategories(): List<Category> {
        val snap = db.collection("categories").get().await()
        return withContext(Dispatchers.Default) {
            snap.documents
                .map { Category.fromDoc(it.id, it.dataMap()) }
                .sortedBy { it.name }
        }
    }

    suspend fun fetchSubcategories(): List<Subcategory> {
        val snap = db.collection("subcategories").get().await()
        return withContext(Dispatchers.Default) {
            snap.documents.map { Subcategory.fromDoc(it.id, it.dataMap()) }
        }
    }

    suspend fun fetchLessons(): List<Lesson> {
        val snap = db.collection("lessons").get().await()
        return withContext(Dispatchers.Default) {
            snap.documents
                .map { Lesson.fromDoc(it.id, it.dataMap()) }
                .sortedByDescending { it.createdAtMs }
        }
    }

    /**
     * دروس قسم فرعي واحد — استعلام مقيَّد بدل جلب **كلّ** الدروس ثم ترشيحها
     * على الجهاز (شاشة إعادة الترتيب كانت تقرأ المجموعة كاملة لعرض قسم واحد).
     *
     * بلا `orderBy` فلا يلزم فهرس مركّب؛ الفرز يبقى محليّاً عند المستدعي.
     * ويُستعلم عن الشكلين: الحقل الجذري في الوثائق الحديثة، والحقل المتداخل
     * في الوثائق القديمة المغلَّفة `{data:{...}}`. وإن لم يُعِد الشكلان شيئاً
     * (شكل أقدم يخزّن `subcategory._id`) نعود للجلب الكامل مرّة واحدة كي لا
     * يختفي درس من شاشة الترتيب.
     */
    suspend fun fetchSubcategoryLessons(subcategoryId: String): List<Lesson> {
        val col = db.collection("lessons")
        val found = LinkedHashMap<String, Lesson>()
        listOf("subcategoryId", "data.subcategoryId").forEach { field ->
            runCatching { col.whereEqualTo(field, subcategoryId).get().await() }
                .getOrNull()
                ?.documents
                ?.forEach { found[it.id] = Lesson.fromDoc(it.id, it.dataMap()) }
        }
        if (found.isEmpty()) {
            return fetchLessons().filter { it.subcategoryId == subcategoryId }
        }
        return found.values.toList()
    }

    // ---------------- مشرفو لوحة التحكّم (dashboard_admins) ----------------
    // المشرف يُنشأ فقط عبر رمز الاعتماد (AuthService.verifyOwnerCode)، تماماً
    // كما في نبراس — لا يوجد هنا إضافة مشرف يدوياً بكتابة بريده.

    /**
     * قائمة كل الحسابات المصرَّح لها (المالك + المشرفون) — نظير صفحة
     * المشرفين في نبراس. أحدث تسجيل دخول في الأعلى (fallback: addedAt).
     */
    suspend fun fetchDashAdmins(): List<DashAdmin> {
        val snap = db.collection(DASH_COL).get().await()
        return snap.documents
            .map { DashAdmin.fromDoc(it.id, it.dataMap()) }
            .sortedByDescending { it.lastSignedInAtMs ?: it.addedAtMs }
    }

    /** حالة بريد معيّن: null إن غير موجود، وإلا الوثيقة. */
    suspend fun getDashAdmin(email: String): DashAdmin? {
        val id = email.trim().lowercase()
        if (id.isEmpty()) return null
        val doc = db.collection(DASH_COL).document(id).get().await()
        if (!doc.exists()) return null
        return DashAdmin.fromDoc(doc.id, doc.dataMap())
    }

    /**
     * يثبّت دور المالك ويحدّث وقت آخر دخول (idempotent — نظير Owner Bypass
     * في نبراس). يُستدعى من AuthService.resolveAccess عند كل دخول للمالك.
     */
    fun upsertOwnerRecord(email: String, displayName: String = "", photoURL: String = "") {
        val id = email.trim().lowercase()
        if (id.isEmpty()) return
        // ⚠️ بلا await: مهمّة كتابة Firestore لا تكتمل إلا بتأكيد الخادم،
        // فانتظارها هنا كان يُجمّد **تسجيل الدخول نفسه** على شبكة ضعيفة
        // (الأصل Flutter كان يستدعيها بـ unawaited لهذا السبب بالضبط).
        // التوثيق أفضل-جهد: يُطبَّق محليّاً ويُرسَل تلقائياً عند عودة الشبكة.
        db.collection(DASH_COL).document(id).set(
            mapOf(
                "email" to id,
                "role" to "owner",
                "blocked" to false,
                "blockMode" to null,
                "displayName" to displayName,
                "photoURL" to photoURL,
                "addedBy" to "owner_bypass",
                "addedAt" to nowIso(),
                "lastSignedInAt" to nowIso(),
            ),
            SetOptions.merge(),
        )
    }

    /**
     * يحدّث وقت آخر دخول لمشرف معتمَد (مسموح للمستخدم بتحديث وثيقته فقط
     * طالما غير محظور — انظر firestore.rules).
     */
    fun touchLastSignedIn(email: String) {
        val id = email.trim().lowercase()
        if (id.isEmpty()) return
        // بلا await — للسبب نفسه في [upsertOwnerRecord]: ختم وقت الدخول
        // معلومة إداريّة لا يجوز أن تحجز شاشة الدخول خلف الشبكة.
        runCatching {
            db.collection(DASH_COL).document(id).update("lastSignedInAt", nowIso())
        }
    }

    /** حظر مؤقّت/نهائي أو إلغاء الحظر — نظير أزرار صفحة المشرفين في نبراس. */
    suspend fun setDashAdminBlocked(email: String, blocked: Boolean, mode: String = "temporary") {
        val id = email.trim().lowercase()
        db.collection(DASH_COL).document(id).update(
            mapOf(
                "blocked" to blocked,
                "blockMode" to if (blocked) mode else null,
                "blockedAt" to if (blocked) nowIso() else null,
            ),
        ).await()
    }

    suspend fun removeDashAdmin(email: String) {
        val id = email.trim().lowercase()
        db.collection(DASH_COL).document(id).delete().await()
    }

    /**
     * بثّ حيّ لكل رموز الاعتماد المعلَّقة (يقرؤها المالك فقط — firestore.rules).
     * الرموز الحقيقية وثائق بمعرّف = بريد المرشّح؛ وثيقة `current` مرآة توافق
     * قديمة تُستبعد كي لا يظهر الرمز نفسه مرتين.
     */
    fun watchPendingOwnerCodes(): Flow<List<PendingOwnerCode>> =
        db.collection(OWNER_CODES_COL).querySnapshots().map { snap ->
            snap.documents
                .filter { it.id != "current" }
                .map { PendingOwnerCode.fromDoc(it.dataMap()) }
                .filter { !it.isExpired && it.code.isNotEmpty() }
                .sortedByDescending { it.expiresAtMs }
        }

    /**
     * يُبطل رمز مرشّح فعلياً: يحذف وثيقة بريده (التي يتحقق منها الخادم)،
     * ومرآة `current` إن كانت تخص المرشّح نفسه.
     */
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
     * مفتاح ثابت مشتقّ من محتوى القسم يُستعمل **معرّفاً للوثيقة**: نفس الاسم
     * (ونفس الأب للفرعي) يعطي المعرّف نفسه، فتُصبح الكتابة تكراريّة الأمان.
     *
     * ⚠️ سبب وجوده: كتابة Firestore لا تكتمل إلا بتأكيد الخادم، لكنّ الكاش
     * الدائم يسجّلها محليّاً ويرسلها عند عودة الشبكة **حتى لو أُلغيت
     * الكوروتين**. فمشرفٌ ظنّ أنّ الإنشاء فشل فأعاد الاسم نفسه كان يُنشئ
     * قسمين متطابقين. بالمعرّف المشتقّ تُكتب المحاولتان فوق وثيقة واحدة —
     * نظير `clientKey` في [addLesson].
     */
    private fun sectionKey(prefix: String, vararg parts: String): String {
        val raw = parts.joinToString("|") {
            it.trim().lowercase().replace(Regex("\\s+"), " ")
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return prefix + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /**
     * يكتب وثيقة قسم بمعرّف مشتقّ ويعيد `true` إن أكّدها الخادم قبل المهلة.
     * `false` تعني «محفوظة محليّاً وستُرسَل عند عودة الشبكة» لا «فشلت».
     */
    private suspend fun writeSection(
        collection: String,
        key: String,
        data: Map<String, Any?>,
    ): Boolean {
        val task = db.collection(collection).document(key).set(data)
        // بلا شبكة: الكتابة سُجِّلت محليّاً بالفعل ولن يصل تأكيد أبداً —
        // لا ننتظر المهلة كاملة أمام المشرف.
        if (!NetworkMonitor.online.value) return false
        return withTimeoutOrNull(SECTION_WRITE_TIMEOUT_MS) {
            task.await()
            true
        } ?: false
    }

    suspend fun addCategory(name: String): Boolean {
        val clean = name.trim()
        val key = sectionKey("cat_", clean)
        return writeSection(
            "categories",
            key,
            mapOf("name" to clean, "createdAt" to nowIso(), "clientKey" to key),
        )
    }

    suspend fun addSubcategory(name: String, categoryId: String): Boolean {
        val clean = name.trim()
        val key = sectionKey("sub_", categoryId, clean)
        return writeSection(
            "subcategories",
            key,
            mapOf(
                "name" to clean,
                "categoryId" to categoryId,
                "createdAt" to nowIso(),
                "clientKey" to key,
            ),
        )
    }

    suspend fun addLesson(
        title: String,
        categoryId: String,
        subcategoryId: String,
        audioUrl: String,
        /**
         * اسما القسم الرئيسي والفرعي وقت الإضافة. يُخزَّنان في الوثيقة كي
         * تبقى نسختها في `deleted_lessons` (تُنسخ كما هي) دالّةً على قسمها،
         * فيميّز المشرف بين دروس متشابهة العناوين في سلة المحذوفات.
         */
        categoryName: String = "",
        subcategoryName: String = "",
        audioStoragePath: String? = null,
        addedBy: String = "",
        featured: Boolean = false,
        featuredUntilMs: Long? = null,
        /**
         * زمن الإضافة الحقيقي (لحظة ضغط المشرف «رفع»)، لا لحظة اكتمال
         * الرفع — به يبقى ترتيب الدروس في التطبيق العام مطابقاً لترتيب
         * إضافتها حتى لو رُفعت لاحقاً بعد انقطاع طويل.
         */
        createdAtMs: Long? = null,
        /**
         * مفتاح ثابت من العميل يمنع إنشاء درسين متطابقين إن ضاع ردّ
         * الخادم بعد نجاح الكتابة (حالة معتادة على شبكة ضعيفة).
         */
        clientKey: String? = null,
    ): String {
        val data = mutableMapOf<String, Any>(
            "title" to title.trim(),
            "categoryId" to categoryId,
            "subcategoryId" to subcategoryId,
            "audioUrl" to audioUrl,
            "createdAt" to (createdAtMs?.let(::isoOf) ?: nowIso()),
        )
        if (categoryName.isNotBlank()) data["categoryName"] = categoryName.trim()
        if (subcategoryName.isNotBlank()) data["subcategoryName"] = subcategoryName.trim()
        if (!audioStoragePath.isNullOrEmpty()) data["audioStoragePath"] = audioStoragePath
        if (featured) {
            data["featured"] = true
            featuredUntilMs?.let { data["featuredUntil"] = isoOf(it) }
        }
        if (addedBy.isNotEmpty()) data["addedBy"] = addedBy.lowercase()
        if (!clientKey.isNullOrEmpty()) data["clientKey"] = clientKey
        val result = functions.getHttpsCallable("createLesson").call(data).await()
        // معرّف الدرس المنشأ — يلزم «النص المشروح» المرافق في طابور الرفع.
        return ((result.data as? Map<*, *>)?.get("id"))?.toString().orEmpty()
    }

    /**
     * إعادة ترتيب دروس قسم فرعي: تُرسل القائمة الكاملة بالترتيب الجديد،
     * والخادم يعيد توزيع طوابع الإنشاء نفسها عليها (فيصح «الأقدم أولاً»
     * و«الأحدث أولاً» معاً في التطبيق العام بلا أي تعديل عليه).
     */
    suspend fun reorderSubcategoryLessons(subcategoryId: String, lessonIds: List<String>) {
        functions.getHttpsCallable("reorderSubcategoryLessons").call(
            mapOf("subcategoryId" to subcategoryId, "lessonIds" to lessonIds),
        ).await()
    }

    /**
     * تمييز/إلغاء تمييز درس (يظهر في «مختارات المنبر» أعلى التطبيق).
     * [untilMs] نهاية المدّة، و`null` تعني تمييزاً دائماً.
     * إلغاء التمييز يمسح المدّة أيضاً كي لا تبقى قيمة معلّقة تُربك العرض.
     */
    suspend fun setLessonFeatured(id: String, featured: Boolean, untilMs: Long? = null) {
        // ⚠️ لا updateCompat هنا: هو يسبق المفاتيح بـ`data.` وحدها في الوثائق
        // القديمة المغلَّفة، بينما watchFeatured يستعلم `featured` الجذري —
        // فكان تمييز درس قديم لا يظهر في اللوحة. لكنّ التطبيق العام يقرأ
        // المغلَّف وحده، فالكتابة على **الموضعين معاً** هي الحلّ الوحيد الذي
        // يُبقي اللوحة والتطبيق متّفقَين (وإلا بقي درس ملغى التمييز مميّزاً
        // في التطبيق إلى الأبد لأن `data.featured` لم يُلمس).
        val user = FirebaseAuth.getInstance().currentUser
        val featuredUntil: Any = when {
            !featured -> FieldValue.delete()
            untilMs == null -> FieldValue.delete()
            else -> isoOf(untilMs)
        }
        val featuredKeys = mapOf(
            "featured" to featured,
            "featuredUntil" to featuredUntil,
            "featuredAt" to if (featured) nowIso() else FieldValue.delete(),
        )
        val tracking = buildMap<String, Any?> {
            if (user != null) put("updatedByUid", user.uid)
            val email = user?.email.orEmpty()
            if (email.isNotEmpty()) put("updatedByEmail", email.trim().lowercase())
            put("updatedAt", nowIso())
        }
        val ref = db.collection("lessons").document(id)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            if (!snapshot.exists()) error("الدرس المطلوب غير موجود.")
            val fields = buildMap<String, Any?> {
                putAll(featuredKeys)
                putAll(tracking)
                // الوثائق القديمة المغلَّفة: نكتب النسخة المسبوقة أيضاً كي
                // يراها التطبيق العام الذي يقرأ من `data` وحدها.
                if (snapshot.data?.get("data") is Map<*, *>) {
                    featuredKeys.forEach { (key, value) -> put("data.$key", value) }
                }
            }
            transaction.update(ref, fields)
            null
        }.await()
    }

    /**
     * بثّ حيّ لدروس «مختارات المنبر». الترشيح محلّي على `featured` كي لا
     * يحتاج فهرساً مركّباً، والقائمة صغيرة أصلاً بطبيعتها.
     */
    fun watchFeatured(): Flow<List<Lesson>> =
        db.collection("lessons").whereEqualTo("featured", true).querySnapshots()
            .map { snap ->
                snap.documents
                    .map { Lesson.fromDoc(it.id, it.dataMap()) }
                    .sortedWith(
                        // الدائم أوّلاً ثم الأقرب انتهاءً — ما يوشك على
                        // السقوط يجب أن يقع تحت عين المالك.
                        compareBy<Lesson> { it.featuredUntilMs ?: Long.MAX_VALUE }
                            .thenByDescending { it.createdAtMs },
                    )
            }

    // ⛔ «النشر المجدول» أُزيل من المنظومة كلّها (الدوال السحابيّة والتطبيق
    // العام واللوحة) بقرار صاحب المشروع، والقاعدة خالية من أيّ درس بموعد
    // مستقبليّ. فلا جدولة ولا «نشر الآن» ولا حقل `publishAt` — لا تُعَد.

    // ---------------- تعديل ----------------
    suspend fun updateCategory(id: String, name: String) =
        updateCompat("categories", id, mapOf("name" to name.trim()))

    suspend fun updateSubcategory(id: String, name: String) =
        updateCompat("subcategories", id, mapOf("name" to name.trim()))

    suspend fun updateLessonTitle(id: String, title: String) =
        updateCompat("lessons", id, mapOf("title" to title.trim()))

    /**
     * يحافظ على شكل الوثائق القديمة `{data:{...}}` بدلاً من كتابة حقل جديد
     * في الجذر لا يقرأه التطبيق العام.
     */
    private suspend fun updateCompat(collection: String, id: String, fields: Map<String, Any?>) {
        val ref = db.collection(collection).document(id)
        val user = FirebaseAuth.getInstance().currentUser
        val tracked = buildMap<String, Any?> {
            putAll(fields)
            if (user != null) put("updatedByUid", user.uid)
            val email = user?.email.orEmpty()
            if (email.isNotEmpty()) put("updatedByEmail", email.trim().lowercase())
            put("updatedAt", nowIso())
        }
        db.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            if (!snapshot.exists()) error("المستند المطلوب غير موجود.")
            val raw = snapshot.data ?: emptyMap()
            if (raw["data"] is Map<*, *>) {
                transaction.update(ref, tracked.mapKeys { "data.${it.key}" })
            } else {
                transaction.update(ref, tracked)
            }
            null
        }.await()
    }

    // ---------------- حذف ----------------
    /**
     * يحذف القسم الرئيسي حذفاً تعاقبياً كاملاً: كل أقسامه الفرعية ودروسها
     * (مع ملفاتها الصوتية في التخزين)، ثم أي دروس مرتبطة به مباشرةً، ثم القسم.
     */
    suspend fun deleteCategory(id: String) {
        functions.getHttpsCallable("deleteCategoryCascade")
            .call(mapOf("categoryId" to id)).await()
    }

    /**
     * يحذف القسم الفرعي حذفاً تعاقبياً: كل دروسه (مع ملفاتها الصوتية في
     * التخزين)، ثم وثيقة القسم الفرعي.
     */
    suspend fun deleteSubcategory(id: String) {
        functions.getHttpsCallable("deleteSubcategoryCascade")
            .call(mapOf("subcategoryId" to id)).await()
    }

    /** يحذف الدرس: الملف الصوتي من التخزين (إن وُجد) ثم الوثيقة. */
    suspend fun deleteLesson(lesson: Lesson) {
        functions.getHttpsCallable("deleteLesson")
            .call(mapOf("lessonId" to lesson.id)).await()
    }

    // ---------------- تفاعل المستمعين (feedback) ----------------
    suspend fun fetchFeedback(): List<Map<String, Any?>> {
        val snap = db.collection("feedback").get().await()
        return snap.documents
            .map { doc -> buildMap<String, Any?> { put("id", doc.id); putAll(doc.dataMap()) } }
            .sortedByDescending { (it["createdAtMs"] as? Number)?.toLong() ?: 0L }
    }

    suspend fun deleteFeedback(id: String) {
        db.collection("feedback").document(id).delete().await()
    }

    // ---------------- تنبيهات المشرف (إنجازات/تقرير أسبوعي) ----------------
    /** آخر مرّة نُظِّفت فيها التنبيهات المحسومة — حارس ضدّ استدعاء لكل رجوع. */
    private var lastAlertCleanupMs = 0L

    /** يزيل خادمياً تنبيهات المساهمات التي حُسمت قبل الإصلاح الحالي. */
    suspend fun cleanupResolvedAdminAlerts() {
        // اللوحة تستدعيها عند كل رجوع إليها؛ التنظيف عمل صيانة لا يستحقّ
        // استدعاء دالة سحابيّة أكثر من مرّة في الساعة.
        val now = System.currentTimeMillis()
        if (now - lastAlertCleanupMs < 60 * 60 * 1000L) return
        lastAlertCleanupMs = now
        // لا نحجب اللوحة إذا كانت الدالة لم تُنشر بعد أو كان الاتصال ضعيفاً.
        runCatching { functions.getHttpsCallable("cleanupResolvedAdminAlerts").call().await() }
    }
}
