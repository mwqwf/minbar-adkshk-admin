package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch

private sealed interface PendingAction {
    data class EditCategory(val item: Category) : PendingAction
    data class EditSubcategory(val item: Subcategory) : PendingAction
    data class EditLesson(val item: Lesson) : PendingAction
    data class DeleteLesson(val item: Lesson) : PendingAction
}

/** القسم المطلوب حذفه — رئيسيّ أو فرعيّ، بمسار واحد للحوارين. */
private sealed interface DeleteTarget {
    val name: String

    data class Main(val item: Category) : DeleteTarget {
        override val name: String get() = item.name
    }

    data class Sub(val item: Subcategory) : DeleteTarget {
        override val name: String get() = item.name
    }
}

@Composable
fun ManageAllScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    // اختيار مدّة التمييز — التمييز لم يعد دائماً بالضرورة.
    var featureFor by remember { mutableStateOf<Lesson?>(null) }
    // محرر «النص المشروح» (المتن الذي تشرحه الصوتية).
    var transcriptFor by remember { mutableStateOf<Lesson?>(null) }
    // شاشة إعادة ترتيب دروس القسم الفرعي المفتوح.
    var reorderOpen by remember { mutableStateOf(false) }
    // نقل درس إلى قسم آخر — البديل عن «احذف ثم أعد الرفع» الذي كان يكلّف
    // رفع الصوتيّة كاملةً ويُضيّع النصّ المشروح وعدّاد الاستماع.
    var moveFor by remember { mutableStateOf<Lesson?>(null) }
    var moveBusy by remember { mutableStateOf(false) }

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    // ⚡ قائمة الدروس **كاملةً** لم تعد تُجلب عند فتح الشاشة: على الإنترنت
    // الضعيف كانت قراءةً ثقيلة قبل أن يطلب المشرف شيئاً. تُجلب فقط حين
    // يُكتب بحث (فالبحث يحتاجها كلّها) أو حين يُطلب حذف قسم (فحساب مدى
    // الحذف يحتاجها). null = لم تُجلب بعد.
    var allLessons by remember { mutableStateOf<List<Lesson>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var treeError by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    // ⛔ فشل الجلب كان يترك القوائم فارغة، فيحسب حوار الحذف التعاقبيّ «0 قسماً
    // و0 درساً» ويطمئنّ المشرف بينما الخادم يمحو كلّ المحتوى. هذا العلم يمنع
    // التأكيد ما لم تكن قائمة الدروس محمَّلة فعلاً.
    var scopeKnown by remember { mutableStateOf(false) }

    // التصفّح: لا شيء مفتوح = عرض الأقسام الرئيسيّة.
    var openCategory by remember { mutableStateOf<Category?>(null) }
    var openSub by remember { mutableStateOf<Subcategory?>(null) }
    var browseLessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf("") }
    var browseReload by remember { mutableIntStateOf(0) }

    // حوار «انقل دروسه أم احذفها معه؟» ثم حوار التأكيد التعاقبيّ.
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var cascadeTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    val hasQuery = query.trim().isNotEmpty()

    /** جلب شجرة الأقسام وحدها — خفيفة، وتكفي لعرض الشاشة عند فتحها. */
    LaunchedEffect(reload) {
        loading = true
        treeError = ""
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }.onFailure {
            treeError = it.arabicReason()
        }
        loading = false
    }

    /** يجلب كلّ الدروس مرّة واحدة ويحفظها؛ يعيد true إن صارت معروفة. */
    suspend fun loadAllLessons(): Boolean {
        if (allLessons != null) return true
        loading = true
        val result = runCatching { AdminRepository.fetchLessons() }
        loading = false
        return result.onSuccess {
            allLessons = it
            scopeKnown = true
        }.onFailure {
            allLessons = null
            scopeKnown = false
            snack("تعذّر تحميل الدروس: ${it.arabicReason()}")
        }.isSuccess
    }

    // البحث يحتاج كلّ الدروس — تُجلب عند أوّل حرف يُكتب لا قبله.
    LaunchedEffect(hasQuery) {
        if (hasQuery) loadAllLessons()
    }

    // دروس القسم المفتوح وحده: استعلام مقيَّد بدل قراءة المجموعة كاملة.
    LaunchedEffect(openSub, browseReload) {
        val sub = openSub
        if (sub == null) {
            browseLessons = emptyList()
            browseError = ""
            return@LaunchedEffect
        }
        browseLoading = true
        browseError = ""
        runCatching { AdminRepository.fetchSubcategoryLessons(sub.id) }
            .onSuccess { list ->
                // ترتيب القسم مبنيّ على طابع الإنشاء تصاعديّاً — كما يراه
                // المستمع في التطبيق تماماً.
                browseLessons = list.sortedBy { it.createdAtMs }
            }
            .onFailure { browseError = it.arabicReason() }
        browseLoading = false
    }

    /** بعد أيّ تغيير في الدروس: يُحدَّث المفتوح، ويُبطَل كاش البحث. */
    fun lessonsChanged() {
        if (openSub != null) browseReload++
        if (hasQuery) {
            // بحثٌ قائم: يُعاد الجلب فوراً كي لا تختفي النتائج تحت يد المشرف.
            scope.launch {
                runCatching { AdminRepository.fetchLessons() }
                    .onSuccess { allLessons = it; scopeKnown = true }
                    .onFailure { allLessons = null; scopeKnown = false }
            }
        } else {
            allLessons = null
            scopeKnown = false
        }
    }

    fun <T> filter(list: List<T>, name: (T) -> String): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return list.mapNotNull { item ->
            val idx = name(item).lowercase().indexOf(q)
            if (idx == -1) null else item to idx
        }.sortedBy { it.second }.map { it.first }
    }

    // خريطتان مثبّتتان: مرشِّح البحث ينادي الاسمين لكلّ درس ولكلّ رمز، والبحث
    // الخطّي فيهما كان يتكرّر مع كل ضغطة مفتاح.
    val catNames = remember(categories) { categories.associate { it.id to it.name } }
    val subNames = remember(subcategories) { subcategories.associate { it.id to it.name } }

    fun subName(id: String): String = subNames[id].orEmpty()
    fun catName(id: String): String = catNames[id].orEmpty()

    // القسم المفتوح آخر مرّة يُحفظ ليُقترح عند الفتح التالي: المشرف يعمل على
    // قسم واحد أيّاماً، فإعادة البحث عنه في كلّ مرّة عبء بلا فائدة.
    LaunchedEffect(openCategory, openSub) {
        AppPrefs.lastManageCategoryId = openCategory?.id
        AppPrefs.lastManageSubcategoryId = openSub?.id
    }

    // قسم محفوظ حُذف بعد آخر جلسة: الاقتراح لا يُعرض بلا وجود.
    val savedCategory = categories.firstOrNull { it.id == AppPrefs.lastManageCategoryId }
    val savedSub = subcategories.firstOrNull { it.id == AppPrefs.lastManageSubcategoryId }

    val cats = filter(categories) { it.name }
    val subs = filter(subcategories) { it.name }

    // بحث الدروس رمزيّ عامّ: يقبل رقماً واحداً، وكل كلمة تُطابق العنوان أو
    // اسم القسم الرئيسي أو الفرعي («3 الفقه» = الدرس 3 في الفقه).
    val lessonTokens = query.trim().lowercase().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    val foundLessons = if (!hasQuery) {
        emptyList()
    } else {
        allLessons.orEmpty().filter { lesson ->
            lessonTokens.all { token ->
                "${lesson.title} ${catName(lesson.categoryId)} ${subName(lesson.subcategoryId)}"
                    .lowercase().contains(token)
            }
        }
    }
    val emptySearch = hasQuery && !loading &&
        cats.isEmpty() && subs.isEmpty() && foundLessons.isEmpty()

    // حوارات التعديل/الحذف
    when (val action = pending) {
        is PendingAction.EditCategory -> EditTextDialog(
            title = "تعديل القسم الرئيسي",
            initial = action.item.name,
            onDismiss = { pending = null },
            onSave = { name ->
                pending = null
                if (name.isNotEmpty() && name != action.item.name) {
                    scope.launch {
                        runCatching { AdminRepository.updateCategory(action.item.id, name) }
                            .onSuccess { snack("تم التعديل."); reload++ }
                            .onFailure { snack("تعذّر التعديل: ${it.arabicReason()}") }
                    }
                }
            },
        )

        is PendingAction.EditSubcategory -> EditTextDialog(
            title = "تعديل القسم الفرعي",
            initial = action.item.name,
            onDismiss = { pending = null },
            onSave = { name ->
                pending = null
                if (name.isNotEmpty() && name != action.item.name) {
                    scope.launch {
                        runCatching { AdminRepository.updateSubcategory(action.item.id, name) }
                            .onSuccess { snack("تم التعديل."); reload++ }
                            .onFailure { snack("تعذّر التعديل: ${it.arabicReason()}") }
                    }
                }
            },
        )

        is PendingAction.EditLesson -> EditTextDialog(
            title = "تعديل عنوان الدرس",
            initial = action.item.title,
            onDismiss = { pending = null },
            onSave = { title ->
                pending = null
                if (title.isNotEmpty() && title != action.item.title) {
                    scope.launch {
                        runCatching { AdminRepository.updateLessonTitle(action.item.id, title) }
                            .onSuccess { snack("تم التعديل."); lessonsChanged() }
                            .onFailure { snack("تعذّر التعديل: ${it.arabicReason()}") }
                    }
                }
            },
        )

        is PendingAction.DeleteLesson -> ConfirmDialog(
            title = "تأكيد الحذف",
            body = "هل أنت متأكد من حذف \"${action.item.title}\"؟\n\n" +
                "ينتقل الدرس إلى «سلة المحذوفات» ويبقى قابلاً للاستعادة " +
                "30 يوماً قبل حذفه النهائي تلقائياً.",
            confirmLabel = "حذف",
            confirmColor = MaterialTheme.colorScheme.error,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                loading = true
                scope.launch {
                    runCatching { AdminRepository.deleteLesson(action.item) }
                        .onSuccess { snack("تم حذف الدرس والملف الصوتي.") }
                        .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                    loading = false
                    lessonsChanged()
                }
            },
        )

        null -> Unit
    }

    // ─── حذف قسم: النقل أوّلاً، والحذف التعاقبيّ خياراً ثانياً ───
    deleteTarget?.let { target ->
        val doomedSubs = when (target) {
            is DeleteTarget.Main -> subcategories.filter { it.categoryId == target.item.id }
            is DeleteTarget.Sub -> emptyList()
        }
        val doomedSubIds = doomedSubs.map { it.id }.toSet()
        val doomedLessons = when (target) {
            is DeleteTarget.Main -> allLessons.orEmpty().filter {
                it.categoryId == target.item.id || it.subcategoryId in doomedSubIds
            }
            is DeleteTarget.Sub -> allLessons.orEmpty().filter {
                it.subcategoryId == target.item.id
            }
        }
        // ⛔ الوجهة لا تكون داخل ما سيُحذف — وإلا نُقل الدرس إلى قسم يختفي بعد لحظة.
        val excluded = when (target) {
            is DeleteTarget.Main -> doomedSubIds
            is DeleteTarget.Sub -> setOf(target.item.id)
        }
        SectionDeleteFlowDialog(
            sectionName = target.name,
            isMainCategory = target is DeleteTarget.Main,
            knownLessonCount = doomedLessons.size,
            scopeKnown = scopeKnown,
            categories = categories,
            subcategories = subcategories,
            excludedSubIds = excluded,
            loadLessons = {
                // ⚠️ الدروس تُقرأ من جديد لحظة النقل: قائمة قديمة قد تُبقي
                // درساً في قسم يُظنّ أنّه صار فارغاً.
                when (target) {
                    is DeleteTarget.Sub ->
                        AdminRepository.fetchSubcategoryLessons(target.item.id)
                    is DeleteTarget.Main -> {
                        val fresh = AdminRepository.fetchLessons()
                        fresh.filter {
                            it.categoryId == target.item.id || it.subcategoryId in doomedSubIds
                        }
                    }
                }
            },
            onDismiss = { deleteTarget = null },
            onChooseCascade = {
                deleteTarget = null
                cascadeTarget = target
            },
            onSomethingMoved = { lessonsChanged() },
            onDeleteEmptied = {
                deleteTarget = null
                loading = true
                scope.launch {
                    runCatching {
                        when (target) {
                            is DeleteTarget.Main ->
                                AdminRepository.deleteCategory(target.item.id)
                            is DeleteTarget.Sub ->
                                AdminRepository.deleteSubcategory(target.item.id)
                        }
                    }
                        .onSuccess { snack("حُذف القسم بعد نقل دروسه.") }
                        .onFailure { snack("تعذّر حذف القسم: ${it.arabicReason()}") }
                    // القسم المفتوح قد يكون هو المحذوف — نعود إلى الجذر.
                    openSub = null
                    openCategory = null
                    allLessons = null
                    scopeKnown = false
                    reload++
                }
            },
        )
    }

    cascadeTarget?.let { target ->
        val doomedSubs = when (target) {
            is DeleteTarget.Main -> subcategories.filter { it.categoryId == target.item.id }
            is DeleteTarget.Sub -> emptyList()
        }
        val doomedSubIds = doomedSubs.map { it.id }.toSet()
        val doomedLessons = when (target) {
            is DeleteTarget.Main -> allLessons.orEmpty().filter {
                it.categoryId == target.item.id || it.subcategoryId in doomedSubIds
            }
            is DeleteTarget.Sub -> allLessons.orEmpty().filter {
                it.subcategoryId == target.item.id
            }
        }
        ConfirmDialog(
            title = "تأكيد الحذف",
            // ⚠️ حين يفشل الجلب تكون الأعداد أدناه صفرية كاذبة، فيُصدَّر التحذير أوّلاً.
            body = (if (!scopeKnown) "⚠️ تعذّر حساب مدى الحذف — لا تتابع. أعد التحميل ثمّ حاول.\n\n" else "") +
                "هل أنت متأكد من حذف \"${target.name}\"؟\n\n" +
                (
                    if (target is DeleteTarget.Main) {
                        "سيُحذف ${arabicCount(doomedSubs.size, "قسم فرعيّ واحد", "قسمان فرعيّان", "أقسام فرعيّة", "قسماً فرعيّاً")} " +
                            "و${lessonsCountLabel(doomedLessons.size)}، "
                    } else {
                        "سيُحذف ${lessonsCountLabel(doomedLessons.size)} "
                    }
                    ) +
                "وملفاتها الصوتية من التخزين نهائياً. لا يمكن التراجع." +
                // الرقم لا يُراجَع والاسم يُراجَع: رؤية اسم لم يقصده
                // المشرف توقفه قبل الضغط، والعدد وحده لا يوقفه.
                namesBlock("الأقسام الفرعيّة:", doomedSubs.map { it.name }, ::moreSubsLabel) +
                namesBlock("الدروس:", doomedLessons.map { it.title }, ::moreLessonsLabel),
            confirmLabel = "حذف",
            confirmColor = MaterialTheme.colorScheme.error,
            // ⛔ الحذف التعاقبيّ لا يُؤكَّد ومداه مجهول.
            confirmEnabled = scopeKnown,
            onDismiss = { cascadeTarget = null },
            onConfirm = {
                cascadeTarget = null
                loading = true
                scope.launch {
                    runCatching {
                        when (target) {
                            is DeleteTarget.Main ->
                                AdminRepository.deleteCategory(target.item.id)
                            is DeleteTarget.Sub ->
                                AdminRepository.deleteSubcategory(target.item.id)
                        }
                    }
                        .onSuccess { snack("تم حذف القسم ومحتوياته بالكامل.") }
                        .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                    openSub = null
                    openCategory = null
                    allLessons = null
                    scopeKnown = false
                    reload++
                }
            },
        )
    }

    transcriptFor?.let { lesson ->
        TranscriptEditorDialog(
            lessonId = lesson.id,
            lessonTitle = lesson.title,
            onDismiss = { transcriptFor = null },
        )
    }

    if (reorderOpen && openSub != null) {
        ReorderLessonsDialog(
            subcategoryId = openSub!!.id,
            subcategoryName = openSub!!.name,
            onDismiss = { reorderOpen = false },
            onSaved = { browseReload++ },
        )
    }

    moveFor?.let { lesson ->
        MoveLessonDialog(
            lesson = lesson,
            currentCategoryName = catName(lesson.categoryId),
            currentSubcategoryName = subName(lesson.subcategoryId),
            categories = categories,
            subcategories = subcategories,
            busy = moveBusy,
            onDismiss = { if (!moveBusy) moveFor = null },
            onConfirm = { target, targetCategoryName ->
                moveBusy = true
                scope.launch {
                    runCatching {
                        AdminRepository.moveLessonToSubcategory(
                            lesson = lesson,
                            target = target,
                            targetCategoryName = targetCategoryName,
                        )
                    }.onSuccess { result ->
                        moveBusy = false
                        moveFor = null
                        // النقل نجح والترتيب وحده قد يتعذّر — لا يُقال «فشل»
                        // لعملٍ تمّ، وإلا أعاد المشرف رفع الصوتيّة بلا داعٍ.
                        snack(
                            if (result.placedLast) {
                                "نُقل الدرس إلى «$targetCategoryName ← ${target.name}» ووُضع في آخر القائمة. ✅"
                            } else {
                                "نُقل الدرس إلى «$targetCategoryName ← ${target.name}». " +
                                    "رتّبه داخل القسم من زرّ «إعادة ترتيب الدروس» إن أردت."
                            },
                        )
                        lessonsChanged()
                    }.onFailure {
                        moveBusy = false
                        snack(
                            "تعذّر النقل: ${it.arabicReason()} " +
                                "الدرس وملفّه الصوتيّ سليمان في مكانهما — " +
                                "تحقّق من الإنترنت وأعد المحاولة.",
                        )
                    }
                }
            },
        )
    }

    featureFor?.let { lesson ->
        FeatureDurationSheet(
            lessonTitle = lesson.title,
            currentUntilMs = lesson.featuredUntilMs,
            onDismiss = { featureFor = null },
            onPick = { duration ->
                featureFor = null
                scope.launch {
                    runCatching {
                        AdminRepository.setLessonFeatured(lesson.id, true, duration.untilMs())
                    }.onSuccess {
                        snack("مُيّز في مختارات المنبر — ${duration.label}")
                        lessonsChanged()
                    }.onFailure { snack("تعذّر التمييز: ${it.arabicReason()}") }
                }
            },
        )
    }

    // بطاقة درس واحدة — مستعملة في التصفّح وفي نتائج البحث معاً.
    val lessonCard: @Composable (Lesson) -> Unit = { l ->
        LessonRow(
            lesson = l,
            subcategoryName = subName(l.subcategoryId),
            onToggleFeatured = {
                if (l.featured) {
                    loading = true
                    scope.launch {
                        runCatching {
                            AdminRepository.setLessonFeatured(l.id, false)
                        }.onSuccess {
                            snack("أُزيل من مختارات المنبر.")
                            loading = false
                            lessonsChanged()
                        }.onFailure {
                            snack("تعذّر التعديل: ${it.arabicReason()}")
                            loading = false
                        }
                    }
                } else {
                    featureFor = l
                }
            },
            onEdit = { pending = PendingAction.EditLesson(l) },
            onDelete = { pending = PendingAction.DeleteLesson(l) },
            onTranscript = { transcriptFor = l },
            onMove = { moveFor = l },
        )
    }

    AdminScaffold(title = "التعديل والحذف / البحث", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // البحث يبقى في مكانه لمن يعرفه — لكنّه لم يعد شرطاً للوصول.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("للبحث السريع: اكتب اسم الدرس أو رقمه…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = adminFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (loading || browseLoading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when {
                // ─── وضع البحث ───
                hasQuery -> when {
                    emptySearch -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("لا توجد نتائج")
                    }

                    else -> LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            bottom = 24.dp,
                        ),
                    ) {
                        if (cats.isNotEmpty()) item { SectionTitle("الأقسام الرئيسية") }
                        items(cats.size) { i ->
                            val c = cats[i]
                            SimpleRow(
                                title = c.name,
                                onEdit = { pending = PendingAction.EditCategory(c) },
                                onDelete = {
                                    scope.launch {
                                        loadAllLessons()
                                        deleteTarget = DeleteTarget.Main(c)
                                    }
                                },
                            )
                        }
                        if (subs.isNotEmpty()) item { SectionTitle("الأقسام الفرعية") }
                        items(subs.size) { i ->
                            val s = subs[i]
                            SimpleRow(
                                title = s.name,
                                onEdit = { pending = PendingAction.EditSubcategory(s) },
                                onDelete = {
                                    scope.launch {
                                        loadAllLessons()
                                        deleteTarget = DeleteTarget.Sub(s)
                                    }
                                },
                            )
                        }
                        if (foundLessons.isNotEmpty()) {
                            item { SectionTitle("الدروس الصوتية") }
                            item { TranscriptHint() }
                        }
                        items(foundLessons.size) { i -> lessonCard(foundLessons[i]) }
                    }
                }

                // ─── تعذّر تحميل الأقسام ───
                treeError.isNotBlank() -> RetryBox(
                    message = "تعذّر تحميل الأقسام: $treeError",
                    onRetry = { reload++ },
                )

                // ─── التصفّح: دروس القسم الفرعيّ المفتوح ───
                openSub != null -> Column(Modifier.fillMaxSize()) {
                    BrowseBreadcrumb(
                        path = "${openCategory?.name.orEmpty()} ← ${openSub!!.name}",
                        onBack = { openSub = null },
                    )
                    OutlinedButton(
                        onClick = { reorderOpen = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 12.dp),
                    ) {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("إعادة ترتيب دروس هذا القسم")
                    }
                    when {
                        browseError.isNotBlank() -> RetryBox(
                            message = "تعذّر تحميل دروس هذا القسم: $browseError",
                            onRetry = { browseReload++ },
                        )

                        !browseLoading && browseLessons.isEmpty() ->
                            EmptyHint("لا يوجد درس في هذا القسم بعد.")

                        else -> LazyColumn(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                bottom = 24.dp,
                            ),
                        ) {
                            item {
                                Text(
                                    "${lessonsCountLabel(browseLessons.size)} بترتيبها كما " +
                                        "يراها المستمع.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                            item { TranscriptHint() }
                            items(browseLessons.size) { i -> lessonCard(browseLessons[i]) }
                        }
                    }
                }

                // ─── التصفّح: الأقسام الفرعيّة داخل قسم رئيسيّ ───
                openCategory != null -> {
                    val children = subcategories.filter { it.categoryId == openCategory!!.id }
                    Column(Modifier.fillMaxSize()) {
                        BrowseBreadcrumb(
                            path = openCategory!!.name,
                            onBack = { openCategory = null },
                        )
                        if (children.isEmpty()) {
                            EmptyHint("لا يوجد قسم فرعيّ هنا بعد.")
                        } else {
                            LazyColumn(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = 24.dp,
                                ),
                            ) {
                                item { SectionTitle("اختر القسم لعرض دروسه") }
                                items(children.size) { i ->
                                    val s = children[i]
                                    BrowseSectionCard(
                                        title = s.name,
                                        subtitle = "",
                                        onOpen = { openSub = s },
                                        onEdit = { pending = PendingAction.EditSubcategory(s) },
                                        onDelete = {
                                            scope.launch {
                                                loadAllLessons()
                                                deleteTarget = DeleteTarget.Sub(s)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // ─── التصفّح: الأقسام الرئيسيّة (أوّل ما يراه المشرف) ───
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 24.dp,
                    ),
                ) {
                    item {
                        Text(
                            "اضغط على القسم لتفتحه، ثمّ اضغط على القسم الفرعيّ " +
                                "لترى كلّ دروسه وتعدّلها.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    // اختصار «تابع من حيث توقّفت»: المشرف يعمل على قسم واحد
                    // أيّاماً، فالوصول إليه يجب أن يكون بنقرة واحدة.
                    if (savedCategory != null && savedSub != null) {
                        item {
                            BrowseSectionCard(
                                title = "تابع في: ${savedSub.name}",
                                subtitle = savedCategory.name,
                                onOpen = { openCategory = savedCategory; openSub = savedSub },
                                onEdit = { pending = PendingAction.EditSubcategory(savedSub) },
                                onDelete = {
                                    scope.launch {
                                        loadAllLessons()
                                        deleteTarget = DeleteTarget.Sub(savedSub)
                                    }
                                },
                            )
                        }
                    }
                    if (categories.isNotEmpty()) item { SectionTitle("الأقسام الرئيسية") }
                    items(categories.size) { i ->
                        val c = categories[i]
                        val count = subcategories.count { it.categoryId == c.id }
                        BrowseSectionCard(
                            title = c.name,
                            subtitle = arabicCount(
                                count,
                                "قسم فرعيّ واحد",
                                "قسمان فرعيّان",
                                "أقسام فرعيّة",
                                "قسماً فرعيّاً",
                            ),
                            onOpen = { openCategory = c },
                            onEdit = { pending = PendingAction.EditCategory(c) },
                            onDelete = {
                                scope.launch {
                                    loadAllLessons()
                                    deleteTarget = DeleteTarget.Main(c)
                                }
                            },
                        )
                    }
                    if (!loading && categories.isEmpty()) {
                        item { EmptyHint("لا توجد أقسام بعد. أنشئها من شاشة «إدارة الأقسام».") }
                    }
                }
            }
        }
    }
}

/** إيماءة توجيهية: أين يضيف المشرف «النص المشروح»؟ */
@Composable
private fun TranscriptHint() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "أيقونة الكتاب بجانب كل درس تضيف/تعدّل «النص " +
                "المشروح» الذي يظهر للمستمعين في شاشة التشغيل.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SimpleRow(title: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "تعديل",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "حذف",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ⛔ لا «نشر مجدول» هنا: الجدولة أُزيلت من اللوحة والتطبيق والدوال السحابيّة
// بقرار صاحب المشروع — لا شارة ولا زرّ «نشر الآن» ولا حقل `publishAt`.
@Composable
private fun LessonRow(
    lesson: Lesson,
    subcategoryName: String,
    onToggleFeatured: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTranscript: () -> Unit,
    onMove: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                lesson.title.ifEmpty { "بدون عنوان" },
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subcategoryName.isNotEmpty()) {
                Text(
                    "القسم الفرعي: $subcategoryName",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // الجزء المعلوماتي في صفّ ذي وزن: مع أربعة أزرار (48dp لكلٍّ) لم
                // يكن يبقى للنصوص عرض، فكان آخر الأبناء يُقصّ خارج البطاقة.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (lesson.views > 0) {
                        Text(
                            "${lesson.views} استماع",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onToggleFeatured, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (lesson.featured) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (lesson.featured) "إلغاء التمييز" else "تمييز",
                        tint = if (lesson.featured) {
                            adminGold
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onTranscript, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "النص المشروح",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "تعديل",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "حذف",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // زرّ نصّيّ عريض لا أيقونة خامسة مزدحمة: «رُفع في القسم الخطأ»
            // أشهر خطأ في اللوحة، وعلاجه يجب أن يُقرأ لا أن يُخمَّن.
            OutlinedButton(
                onClick = onMove,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("نقل إلى قسم آخر")
            }
        }
    }
}

// ─── أسماء ما سيُحذف (لا عدده وحده) ───────────────────────────────────

/** كم اسماً يُعرض قبل «وغيرها» — خمسة تكفي للمراجعة ولا تُطيل الحوار. */
private const val NAMES_PREVIEW = 5

/**
 * كتلة أسماء جاهزة للإلحاق بنصّ حوار الحذف — فارغة تماماً إن لم يكن هناك
 * ما يُحذف، كي لا يظهر عنوان بلا قائمة تحته.
 */
private fun namesBlock(title: String, names: List<String>, more: (Int) -> String): String {
    if (names.isEmpty()) return ""
    val lines = names.take(NAMES_PREVIEW)
        .joinToString("\n") { "• ${it.ifBlank { "بدون عنوان" }}" }
    val rest = names.size - NAMES_PREVIEW
    val tail = if (rest > 0) "\n• ${more(rest)}" else ""
    return "\n\n$title\n$lines$tail"
}

/** «ودرس واحد آخر»/«ودرسان آخران»/«و3 دروس أخرى»/«و11 درساً آخر». */
private fun moreLessonsLabel(rest: Int): String = when {
    rest == 1 -> "ودرس واحد آخر"
    rest == 2 -> "ودرسان آخران"
    rest <= 10 -> "و$rest دروس أخرى"
    else -> "و$rest درساً آخر"
}

/** نظيرتها للأقسام الفرعيّة. */
private fun moreSubsLabel(rest: Int): String = when {
    rest == 1 -> "وقسم فرعيّ واحد آخر"
    rest == 2 -> "وقسمان فرعيّان آخران"
    rest <= 10 -> "و$rest أقسام فرعيّة أخرى"
    else -> "و$rest قسماً فرعيّاً آخر"
}
