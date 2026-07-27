package com.ali.ishaqiyin_admin.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

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

    // ---------------- جلب ----------------
    suspend fun fetchCategories(): List<Category> {
        val snap = db.collection("categories").get().await()
        return snap.documents
            .map { Category.fromDoc(it.id, it.dataMap()) }
            .sortedBy { it.name }
    }

    suspend fun fetchSubcategories(): List<Subcategory> {
        val snap = db.collection("subcategories").get().await()
        return snap.documents.map { Subcategory.fromDoc(it.id, it.dataMap()) }
    }

    suspend fun fetchLessons(): List<Lesson> {
        val snap = db.collection("lessons").get().await()
        return snap.documents
            .map { Lesson.fromDoc(it.id, it.dataMap()) }
            .sortedByDescending { it.createdAtMs }
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
    suspend fun addCategory(name: String) {
        db.collection("categories").add(
            mapOf("name" to name.trim(), "createdAt" to nowIso()),
        ).await()
    }

    suspend fun addSubcategory(name: String, categoryId: String) {
        db.collection("subcategories").add(
            mapOf(
                "name" to name.trim(),
                "categoryId" to categoryId,
                "createdAt" to nowIso(),
            ),
        ).await()
    }

    suspend fun addLesson(
        title: String,
        categoryId: String,
        subcategoryId: String,
        audioUrl: String,
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
    ) {
        val data = mutableMapOf<String, Any>(
            "title" to title.trim(),
            "categoryId" to categoryId,
            "subcategoryId" to subcategoryId,
            "audioUrl" to audioUrl,
            "createdAt" to (createdAtMs?.let(::isoOf) ?: nowIso()),
        )
        if (!audioStoragePath.isNullOrEmpty()) data["audioStoragePath"] = audioStoragePath
        if (featured) {
            data["featured"] = true
            featuredUntilMs?.let { data["featuredUntil"] = isoOf(it) }
        }
        if (addedBy.isNotEmpty()) data["addedBy"] = addedBy.lowercase()
        if (!clientKey.isNullOrEmpty()) data["clientKey"] = clientKey
        functions.getHttpsCallable("createLesson").call(data).await()
    }

    /**
     * تمييز/إلغاء تمييز درس (يظهر في «مختارات المنبر» أعلى التطبيق).
     * [untilMs] نهاية المدّة، و`null` تعني تمييزاً دائماً.
     * إلغاء التمييز يمسح المدّة أيضاً كي لا تبقى قيمة معلّقة تُربك العرض.
     */
    suspend fun setLessonFeatured(id: String, featured: Boolean, untilMs: Long? = null) =
        updateCompat(
            "lessons",
            id,
            mapOf(
                "featured" to featured,
                "featuredUntil" to when {
                    !featured -> FieldValue.delete()
                    untilMs == null -> FieldValue.delete()
                    else -> isoOf(untilMs)
                },
                "featuredAt" to if (featured) nowIso() else FieldValue.delete(),
            ),
        )

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

    /**
     * إلغاء جدولة درس قديم (النشر المجدول أُزيل من الواجهة؛ تبقى هذه
     * لتحرير أيّ درس بقي مجدولاً في القاعدة من قبل).
     */
    suspend fun setLessonPublishAt(id: String, whenMs: Long?) =
        updateCompat(
            "lessons",
            id,
            mapOf("publishAt" to (if (whenMs == null) FieldValue.delete() else isoOf(whenMs))),
        )

    /**
     * «نشر الآن» لدرس مجدول: عبر الدالة الخادمية التي تنشر **وترسل إشعار
     * «درس جديد»** (حذف publishAt وحده كان ينشر بصمت بلا أيّ إشعار).
     * إن تعذّر الوصول للدالة نعود للسلوك القديم كي لا يعلق المشرف.
     */
    suspend fun publishScheduledNow(lessonId: String) {
        try {
            functions.getHttpsCallable("publishScheduledLesson")
                .call(mapOf("lessonId" to lessonId)).await()
        } catch (e: FirebaseFunctionsException) {
            val code = e.code
            if (code == FirebaseFunctionsException.Code.UNIMPLEMENTED ||
                code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                code == FirebaseFunctionsException.Code.INTERNAL ||
                code == FirebaseFunctionsException.Code.NOT_FOUND
            ) {
                setLessonPublishAt(lessonId, null)
                return
            }
            throw e
        }
    }

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
    /** يزيل خادمياً تنبيهات المساهمات التي حُسمت قبل الإصلاح الحالي. */
    suspend fun cleanupResolvedAdminAlerts() {
        // لا نحجب اللوحة إذا كانت الدالة لم تُنشر بعد أو كان الاتصال ضعيفاً.
        runCatching { functions.getHttpsCallable("cleanupResolvedAdminAlerts").call().await() }
    }

    /** المالك يرى كل التنبيهات؛ المشرف يرى تنبيهاته والعامة (email == ''). */
    suspend fun fetchAdminAlerts(email: String, isOwner: Boolean): List<Map<String, Any?>> {
        val e = email.trim().lowercase()
        val docs = LinkedHashMap<String, Map<String, Any?>>()
        if (isOwner) {
            db.collection("admin_alerts").get().await().documents.forEach {
                docs[it.id] = buildMap { put("id", it.id); putAll(it.dataMap()) }
            }
        } else {
            if (e.isEmpty()) return emptyList()
            // تطابق قواعد القراءة: لا نجلب كل التنبيهات ثم نرشّحها محلياً.
            val personal = db.collection("admin_alerts").whereEqualTo("email", e).get().await()
            val general = db.collection("admin_alerts").whereEqualTo("email", "").get().await()
            (personal.documents + general.documents).forEach {
                docs[it.id] = buildMap { put("id", it.id); putAll(it.dataMap()) }
            }
        }
        // نفس قاعدة الموجز: 24 ساعة فقط، وما اطّلعت عليه يختفي عنّي.
        val cutoff = System.currentTimeMillis() - AdminAlertsFeed.TTL_MS
        return docs.values
            .filter { alert ->
                val at = (alert["createdAtMs"] as? Number)?.toLong() ?: 0L
                val readBy = (alert["readBy"] as? List<*>)?.map { it.toString().lowercase() }
                    ?: emptyList()
                (at <= 0L || at >= cutoff) && !readBy.contains(e)
            }
            .sortedByDescending { (it["createdAtMs"] as? Number)?.toLong() ?: 0L }
    }
}
