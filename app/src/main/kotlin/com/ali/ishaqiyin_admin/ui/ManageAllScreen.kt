package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** كم درساً يُعرض في قائمة «آخر ما أُضيف» الافتراضية قبل أي بحث. */
private const val RECENT_LIMIT = 20

private sealed interface PendingAction {
    data class EditCategory(val item: Category) : PendingAction
    data class EditSubcategory(val item: Subcategory) : PendingAction
    data class EditLesson(val item: Lesson) : PendingAction
    data class DeleteCategory(val item: Category) : PendingAction
    data class DeleteSubcategory(val item: Subcategory) : PendingAction
    data class DeleteLesson(val item: Lesson) : PendingAction
}

@Composable
fun ManageAllScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    // اختيار مدّة التمييز — التمييز لم يعد دائماً بالضرورة.
    var featureFor by remember { mutableStateOf<Lesson?>(null) }
    // محرر «النص المشروح» (المتن الذي تشرحه الصوتية).
    var transcriptFor by remember { mutableStateOf<Lesson?>(null) }
    // شاشة إعادة ترتيب دروس القسم الفرعي المفلتَر.
    var reorderOpen by remember { mutableStateOf(false) }
    // نقل درس إلى قسم آخر — البديل عن «احذف ثم أعد الرفع» الذي كان يكلّف
    // رفع الصوتيّة كاملةً ويُضيّع النصّ المشروح وعدّاد الاستماع.
    var moveFor by remember { mutableStateOf<Lesson?>(null) }
    var moveBusy by remember { mutableStateOf(false) }

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var lessons by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    // مفتاح تحديث منفصل للدروس: تعديل درس لا يمسّ شجرة الأقسام، فإعادة تنزيل
    // المجموعتين معه قراءات مدفوعة بلا فائدة.
    var reloadLessons by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    // ⛔ فشل الجلب كان يترك القوائم فارغة، فيحسب حوار الحذف التعاقبيّ «0 قسماً
    // و0 درساً» ويطمئنّ المشرف بينما الخادم يمحو كلّ المحتوى. هذا العلم يمنع
    // التأكيد ما لم يكن آخر جلب ناجحاً.
    var scopeKnown by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
            lessons = AdminRepository.fetchLessons()
        }
            .onSuccess { scopeKnown = true }
            .onFailure {
                scopeKnown = false
                snack("تعذّر تحميل المحتوى: ${it.arabicReason()}")
            }
        loading = false
    }

    LaunchedEffect(reloadLessons) {
        // الصفر هو التركيب الأوّل — الجلب الكامل أعلاه كفيل به.
        if (reloadLessons == 0) return@LaunchedEffect
        runCatching { lessons = AdminRepository.fetchLessons() }
            .onFailure {
                scopeKnown = false
                snack("تعذّر تحديث الدروس: ${it.arabicReason()}")
            }
        loading = false
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

    // فلتر الأقسام: أغلب عناوين الدروس أرقام متشابهة، والقسم هو ما يميّزها —
    // اختر القسم (الرئيسي/الفرعي) لتحصر البحث فيه، أو اتركه لكل الأقسام.
    // يبدأ من آخر فلتر استُعمل (محفوظ بين الجلسات): المشرف يعمل على قسم
    // واحد أيّاماً، فإعادة اختياره في كلّ فتح عبء بلا فائدة.
    var filterCategoryId by rememberSaveable { mutableStateOf(AppPrefs.lastManageCategoryId) }
    var filterSubcategoryId by rememberSaveable {
        mutableStateOf(AppPrefs.lastManageSubcategoryId)
    }
    val filterSubs = subcategories.filter { it.categoryId == filterCategoryId }

    // فلتر محفوظ يشير إلى قسم حُذف بعد آخر جلسة: يُنظَّف بدل أن تبدأ الشاشة
    // على «لا توجد نتائج» بلا سبب ظاهر.
    LaunchedEffect(categories, subcategories) {
        if (categories.isNotEmpty() && filterCategoryId != null &&
            categories.none { it.id == filterCategoryId }
        ) {
            filterCategoryId = null
            AppPrefs.lastManageCategoryId = null
        }
        if (subcategories.isNotEmpty() && filterSubcategoryId != null &&
            subcategories.none { it.id == filterSubcategoryId }
        ) {
            filterSubcategoryId = null
            AppPrefs.lastManageSubcategoryId = null
        }
    }

    val cats = filter(categories) { it.name }
    val subs = filter(subcategories) { it.name }
    val hasQuery = query.trim().isNotEmpty()
    val hasFilter = filterCategoryId != null || filterSubcategoryId != null

    // بحث الدروس رمزيّ عامّ: يقبل رقماً واحداً، وكل كلمة تُطابق العنوان أو
    // اسم القسم الرئيسي أو الفرعي («3 الفقه» = الدرس 3 في الفقه)، ويتقيّد
    // بفلتر الأقسام إن حُدّد. فلترٌ بلا بحث يعرض دروس القسم كلها.
    val lessonTokens = query.trim().lowercase().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    // الشاشة لا تبدأ فارغة بعد اليوم: الدروس مجلوبة أصلاً ومرتَّبة بالأحدث،
    // فتُعرض «آخر ما أُضيف» فوراً — أغلب التعديلات تقع على درس أُضيف للتوّ.
    val foundLessons = if (!hasQuery && !hasFilter) {
        lessons.take(RECENT_LIMIT)
    } else {
        lessons.filter { lesson ->
            (filterCategoryId == null || lesson.categoryId == filterCategoryId) &&
                (filterSubcategoryId == null || lesson.subcategoryId == filterSubcategoryId) &&
                (
                    lessonTokens.isEmpty() || lessonTokens.all { token ->
                        "${lesson.title} ${catName(lesson.categoryId)} ${subName(lesson.subcategoryId)}"
                            .lowercase().contains(token)
                    }
                    )
        }
    }
    val empty = (hasQuery || hasFilter) &&
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
                            .onSuccess { snack("تم التعديل."); reloadLessons++ }
                            .onFailure { snack("تعذّر التعديل: ${it.arabicReason()}") }
                    }
                }
            },
        )

        is PendingAction.DeleteCategory -> {
            // الحذف تعاقبيّ: المشرف يستحقّ معرفة مدى ما سيختفي قبل الضغط.
            val doomedSubs = subcategories.filter { it.categoryId == action.item.id }
            val doomedSubIds = doomedSubs.map { it.id }.toSet()
            val doomedLessons = lessons.filter {
                it.categoryId == action.item.id || it.subcategoryId in doomedSubIds
            }
            ConfirmDialog(
                title = "تأكيد الحذف",
                // ⚠️ حين يفشل الجلب تكون الأعداد أدناه صفرية كاذبة، فيُصدَّر التحذير أوّلاً.
                body = (if (!scopeKnown) "⚠️ تعذّر حساب مدى الحذف — لا تتابع. أعد التحميل ثمّ حاول.\n\n" else "") +
                    "هل أنت متأكد من حذف \"${action.item.name}\"؟\n\n" +
                    "سيُحذف ${arabicCount(doomedSubs.size, "قسم فرعيّ واحد", "قسمان فرعيّان", "أقسام فرعيّة", "قسماً فرعيّاً")} " +
                    "و${lessonsCountLabel(doomedLessons.size)}، " +
                    "وملفاتها الصوتية من التخزين نهائياً. لا يمكن التراجع." +
                    // الرقم لا يُراجَع والاسم يُراجَع: رؤية اسم لم يقصده
                    // المشرف توقفه قبل الضغط، والعدد وحده لا يوقفه.
                    namesBlock("الأقسام الفرعيّة:", doomedSubs.map { it.name }, ::moreSubsLabel) +
                    namesBlock("الدروس:", doomedLessons.map { it.title }, ::moreLessonsLabel),
                confirmLabel = "حذف",
                confirmColor = MaterialTheme.colorScheme.error,
                // ⛔ الحذف التعاقبيّ لا يُؤكَّد ومداه مجهول.
                confirmEnabled = scopeKnown,
                onDismiss = { pending = null },
                onConfirm = {
                    pending = null
                    loading = true
                    scope.launch {
                        runCatching { AdminRepository.deleteCategory(action.item.id) }
                            .onSuccess { snack("تم حذف القسم ومحتوياته بالكامل.") }
                            .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                        reload++
                    }
                },
            )
        }

        is PendingAction.DeleteSubcategory -> {
            val doomedLessons = lessons.filter { it.subcategoryId == action.item.id }
            ConfirmDialog(
                title = "تأكيد الحذف",
                // ⚠️ حين يفشل الجلب تكون الأعداد أدناه صفرية كاذبة، فيُصدَّر التحذير أوّلاً.
                body = (if (!scopeKnown) "⚠️ تعذّر حساب مدى الحذف — لا تتابع. أعد التحميل ثمّ حاول.\n\n" else "") +
                    "هل أنت متأكد من حذف \"${action.item.name}\"؟\n\n" +
                    "سيُحذف ${lessonsCountLabel(doomedLessons.size)} " +
                    "وملفاتها الصوتية من التخزين نهائياً. " +
                    "لا يمكن التراجع." +
                    // أسماء الدروس لا عددها: العدد لا يكشف الخطأ، والاسم يكشفه.
                    namesBlock("الدروس:", doomedLessons.map { it.title }, ::moreLessonsLabel),
                confirmLabel = "حذف",
                confirmColor = MaterialTheme.colorScheme.error,
                // ⛔ الحذف التعاقبيّ لا يُؤكَّد ومداه مجهول.
                confirmEnabled = scopeKnown,
                onDismiss = { pending = null },
                onConfirm = {
                    pending = null
                    loading = true
                    scope.launch {
                        runCatching { AdminRepository.deleteSubcategory(action.item.id) }
                            .onSuccess { snack("تم حذف القسم الفرعي ومحتوياته بالكامل.") }
                            .onFailure { snack("تعذّر الحذف: ${it.arabicReason()}") }
                        reload++
                    }
                },
            )
        }

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
                    reloadLessons++
                }
            },
        )

        null -> Unit
    }

    transcriptFor?.let { lesson ->
        TranscriptEditorDialog(
            lessonId = lesson.id,
            lessonTitle = lesson.title,
            onDismiss = { transcriptFor = null },
        )
    }

    if (reorderOpen && filterSubcategoryId != null) {
        ReorderLessonsDialog(
            subcategoryId = filterSubcategoryId!!,
            subcategoryName = subName(filterSubcategoryId!!),
            onDismiss = { reorderOpen = false },
            onSaved = { reload++ },
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
                        reloadLessons++
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
                        reloadLessons++
                    }.onFailure { snack("تعذّر التمييز: ${it.arabicReason()}") }
                }
            },
        )
    }

    AdminScaffold(title = "التعديل والحذف / البحث", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("ابحث بالعنوان أو الرقم أو اسم القسم…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = adminFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            // فلتر الأقسام للدروس ذات العناوين الرقمية المتشابهة.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdminDropdown(
                    label = "القسم الرئيسي (الكل)",
                    items = listOf<Category?>(null) + categories,
                    selected = categories.firstOrNull { it.id == filterCategoryId },
                    itemLabel = { it?.name ?: "كل الأقسام" },
                    onSelected = { picked ->
                        filterCategoryId = picked?.id
                        filterSubcategoryId = null
                        AppPrefs.lastManageCategoryId = picked?.id
                        AppPrefs.lastManageSubcategoryId = null
                    },
                    modifier = Modifier.weight(1f),
                )
                AdminDropdown(
                    label = "الفرعي (الكل)",
                    items = listOf<Subcategory?>(null) + filterSubs,
                    selected = filterSubs.firstOrNull { it.id == filterSubcategoryId },
                    itemLabel = { it?.name ?: "كل الفروع" },
                    enabled = filterCategoryId != null,
                    onSelected = { picked ->
                        filterSubcategoryId = picked?.id
                        AppPrefs.lastManageSubcategoryId = picked?.id
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // إعادة ترتيب دروس القسم الفرعي المختار (أسهم/موضع محدد/آلي بالأرقام).
            if (filterSubcategoryId != null) {
                OutlinedButton(
                    onClick = { reorderOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("إعادة ترتيب دروس «${subName(filterSubcategoryId!!)}»")
                }
            }
            Spacer(Modifier.size(6.dp))
            if (loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when {
                empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لا توجد نتائج")
                }

                // لا بحث ولا فلتر ولا درس واحد في القاعدة — الإرشاد يبقى
                // معروضاً كما كان بدل شاشة صمّاء.
                !loading && foundLessons.isEmpty() && cats.isEmpty() && subs.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "ابحث بأي كلمة أو رقم، أو اختر قسماً لعرض دروسه —\n" +
                                "مثال: «3 الفقه» يجد الدرس رقم 3 في قسم الفقه.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
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
                            onDelete = { pending = PendingAction.DeleteCategory(c) },
                        )
                    }
                    if (subs.isNotEmpty()) item { SectionTitle("الأقسام الفرعية") }
                    items(subs.size) { i ->
                        val s = subs[i]
                        SimpleRow(
                            title = s.name,
                            onEdit = { pending = PendingAction.EditSubcategory(s) },
                            onDelete = { pending = PendingAction.DeleteSubcategory(s) },
                        )
                    }
                    if (foundLessons.isNotEmpty()) {
                        item {
                            SectionTitle(
                                if (!hasQuery && !hasFilter) {
                                    "آخر ما أُضيف"
                                } else {
                                    "الدروس الصوتية"
                                },
                            )
                        }
                        // القائمة الافتراضية ليست نتيجة بحث — يُذكَّر المشرف
                        // بأنّها أحدث الدروس وأنّ البحث والفلتر متاحان فوقها.
                        if (!hasQuery && !hasFilter) {
                            item {
                                Text(
                                    "أحدث ${arabicCount(foundLessons.size, "درس", "درسين", "دروس", "درساً")} — ابحث بأي كلمة أو رقم، " +
                                        "أو اختر قسماً لعرض دروسه (مثال: «3 الفقه»).",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                        }
                        // إيماءة توجيهية: أين يضيف المشرف «النص المشروح»؟
                        item {
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
                    }
                    items(foundLessons.size) { i ->
                        val l = foundLessons[i]
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
                                            reloadLessons++
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
                }
            }
        }
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
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "تعديل",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete) {
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
                IconButton(onClick = onToggleFeatured) {
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
                IconButton(onClick = onTranscript) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "النص المشروح",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "تعديل",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete) {
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

/** «ودرس واحد آخر»/«ودرسان آخران»/«و٣ دروس أخرى»/«و١١ درساً آخر». */
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
