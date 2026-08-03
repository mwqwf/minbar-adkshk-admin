package com.ali.ishaqiyin_admin.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AppPrefs
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.LessonUploadWorker
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.util.AudioMerger
import androidx.lifecycle.repeatOnLifecycle
import com.ali.ishaqiyin_admin.util.AudioTranscodeMerger
import com.ali.ishaqiyin_admin.util.Mp3FormatException
import com.ali.ishaqiyin_admin.util.PickedFile
import com.ali.ishaqiyin_admin.util.copyUriToCache
import com.ali.ishaqiyin_admin.util.pickedFileFrom
import com.ali.ishaqiyin_admin.util.smartTitleFromFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

private fun fmtDate(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    fun p(v: Int) = v.toString().padStart(2, '0')
    return "${c.get(Calendar.YEAR)}/${p(c.get(Calendar.MONTH) + 1)}/${p(c.get(Calendar.DAY_OF_MONTH))} " +
        "${p(c.get(Calendar.HOUR_OF_DAY))}:${p(c.get(Calendar.MINUTE))}"
}

/** منتقي تاريخ ثم وقت بحوارات النظام (نظير showDatePicker/showTimePicker). */
fun pickDateTime(context: Context, initialMs: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initialMs }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(picked.timeInMillis)
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                true,
            ).show()
        },
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH),
        c.get(Calendar.DAY_OF_MONTH),
    ).apply {
        datePicker.minDate = System.currentTimeMillis() - 1000
        datePicker.maxDate = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
    }.show()
}

/**
 * حفظ الملفات المختارة عبر تدوير الشاشة: (uri، حجم، اسم) في سطر واحد —
 * الاسم آخراً كي لا يكسره أيّ فاصل داخله.
 */
private val pickedFilesSaver = listSaver<SnapshotStateList<PickedFile>, String>(
    save = { list -> list.map { "${it.uri}\n${it.size}\n${it.name}" } },
    restore = { saved ->
        mutableStateListOf<PickedFile>().apply {
            saved.forEach { entry ->
                val parts = entry.split("\n", limit = 3)
                if (parts.size == 3) {
                    add(
                        PickedFile(
                            uri = Uri.parse(parts[0]),
                            name = parts[2],
                            size = parts[1].toLongOrNull() ?: 0L,
                        ),
                    )
                }
            }
        }
    },
)

/**
 * حفظ صور صفحات الكتاب عبر إعادة إنشاء الشاشة — كانت الحقل الوحيد في
 * النموذج المحفوظ بـ`remember`، فأيّ تدوير أو تبديل وضع داكن أو عودة من
 * شاشة القصّ كان يمحو الصور كاملةً بلا تنبيه، فيدخل الدرس الطابور بلا
 * صوره (ومن أرفق صوراً بلا نصّ يفقد النص المشروح كلّه).
 */
private val transcriptImagesSaver = listSaver<SnapshotStateList<Uri>, String>(
    save = { list -> list.map { it.toString() } },
    restore = { saved ->
        mutableStateListOf<Uri>().apply {
            saved.filter { it.isNotEmpty() }.forEach { add(Uri.parse(it)) }
        }
    },
)

/**
 * اقتراح العنوان التالي في سلسلة مرقّمة: عنوان منتهٍ برقم (عربيّ أو
 * هنديّ ٠-٩) يُقترح بزيادته واحداً بنفس صيغة أرقامه. وإن لم ينتهِ برقم
 * فلا اقتراح — النصّ الفارغ يعني «اكتب العنوان بنفسك».
 */
private fun nextTitleSuggestion(previous: String): String {
    val source = previous.trimEnd()
    if (source.isEmpty()) return ""
    val normalized = source.map { ch ->
        if (ch in '٠'..'٩') '0' + (ch - '٠') else ch
    }.joinToString("")
    val match = Regex("(\\d+)$").find(normalized) ?: return ""
    val raw = match.groupValues[1]
    // رقم طويل جدّاً (تاريخ أو معرّف) ليس ترقيم سلسلة — لا يُزاد.
    if (raw.length > 6) return ""
    val next = raw.toLongOrNull()?.plus(1) ?: return ""
    val head = source.substring(0, match.range.first)
    val useArabicDigits = source.any { it in '٠'..'٩' }
    // «الدرس 05» يتبعه «الدرس 06» لا «الدرس 6»: يُحفظ التصفير البادئ.
    val padded = next.toString().padStart(
        if (raw.startsWith("0")) raw.length else 1,
        '0',
    )
    val digits = if (useArabicDigits) {
        padded.map { ch -> '٠' + (ch - '0') }.joinToString("")
    } else {
        padded
    }
    return head + digits
}

@Composable
fun AddLessonScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    // rememberSaveable: تدوير الشاشة كان يمسح النموذج كاملاً بعد تعبئته.
    var title by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var subcategoryId by rememberSaveable { mutableStateOf<String?>(null) }

    /** الملفات المختارة بالترتيب — أكثر من ملف يعني دمجها في درس واحد. */
    val files = rememberSaveable(saver = pickedFilesSaver) { mutableStateListOf<PickedFile>() }

    // «إدراج» لا «رفع»: النموذج لا ينتظر الشبكة إطلاقاً — يُدرَج الدرس في
    // الطابور فيفرغ النموذج فوراً ويستطيع المشرف تعبئة درس آخر بينما
    // يُرفع الأوّل في الخلفية (ويستأنف وحده إن انقطع الاتصال).
    var queuing by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var featured by rememberSaveable { mutableStateOf(false) }
    // التمييز صار مؤقّتاً: null مع featured=true يعني «دائم».
    var featuredUntil by rememberSaveable { mutableStateOf<Long?>(null) }
    var featuredLabel by rememberSaveable { mutableStateOf("") }
    var showFeatureSheet by remember { mutableStateOf(false) }
    var showRecorder by remember { mutableStateOf(false) }

    // «النص المشروح» الاختياري المرافق للدرس — من أحبّ أضافه ومن لم يرد فلا.
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var transcriptText by rememberSaveable { mutableStateOf("") }
    var transcriptBookTitle by rememberSaveable { mutableStateOf("") }
    var transcriptSourceRef by rememberSaveable { mutableStateOf("") }
    val transcriptImages = rememberSaveable(saver = transcriptImagesSaver) {
        mutableStateListOf<Uri>()
    }

    LaunchedEffect(Unit) {
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }
        // إعادة اختيار آخر قسم استُعمل — أغلب الدروس تُضاف إلى القسم نفسه
        // تباعاً. الشرط يمنع تجاوز اختيار مستعاد بعد تدوير الشاشة.
        if (categoryId == null) {
            val savedCategory = AppPrefs.lastAddCategoryId
                ?.takeIf { saved -> categories.any { it.id == saved } }
            if (savedCategory != null) {
                categoryId = savedCategory
                subcategoryId = AppPrefs.lastAddSubcategoryId?.takeIf { saved ->
                    subcategories.any { it.id == saved && it.categoryId == savedCategory }
                }
            }
        }
    }

    // ملفات واردة من المشاركة الخارجية: تُلحق بقائمة الدمج **فور وصولها**
    // حتى والنموذج مفتوح — شارِك صوتية أخرى من أي تطبيق وستنضم للدمج هنا.
    // ⚠️ الجمع مقيّد بدورة حياة الشاشة (STARTED): كان مجمّعاً دائم الحياة،
    // فنموذجٌ مفتوح في خلفية النظام «يسرق» المشاركة بصمت من النموذج الظاهر.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(
            androidx.lifecycle.Lifecycle.State.STARTED,
        ) {
            ShareIntake.pending.collect { queue ->
                val shared = queue.firstOrNull() ?: return@collect
                val duplicate = files.any { it.uri.toString() == shared.uri.toString() }
                when {
                    duplicate -> Unit
                    // لا بوابة صيغة بعد اليوم: الدمج يقبل أي صيغ (إعادة ترميز
                    // AAC/M4A عند الحاجة) — فقط حدّ العدد يبقى.
                    files.size >= AudioMerger.maxFiles -> {
                        message = "الحد الأقصى ${AudioMerger.maxFiles} ملفات للدرس الواحد " +
                            "— لم يُضف «${shared.name}»."
                        isError = true
                    }

                    else -> {
                        files.add(shared)
                        if (title.isBlank()) title = smartTitleFromFileName(shared.name)
                    }
                }
                ShareIntake.consumeFirst()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val picked = uris.map { context.pickedFileFrom(it) }
        val existing = files.map { it.uri.toString() }.toSet()
        val combined = files + picked.filter { it.uri.toString() !in existing }

        // قيد «MP3 فقط» أُلغي: الدمج يقبل أي صيغ (لصق مباشر إن كانت كلها
        // MP3، وإلا فكّ وإعادة ترميز AAC/M4A) — لا عبء تحويل على المشرف.
        if (combined.size > AudioMerger.maxFiles) {
            message = "الحد الأقصى ${AudioMerger.maxFiles} ملفات للدرس الواحد — أُبقي أولها."
            isError = true
        } else {
            message = ""
            isError = false
        }
        files.clear()
        files.addAll(combined.take(AudioMerger.maxFiles))
        if (title.isBlank() && files.isNotEmpty()) {
            // عنوان مقترح ذكيّ — يزيل الترقيم وبصمات المواقع وأنماط
            // المسجّلات؛ الاسم الآليّ البحت يُترك فارغاً ليكتبه المشرف.
            title = smartTitleFromFileName(files.first().name)
        }
    }

    val subsForCategory = subcategories.filter { it.categoryId == categoryId }

    /// أوّل نقص بترتيب منطقي مع رسالة تسمّيه بعينه — الزر لا يُعطَّل،
    /// والنقص يُشرح صراحةً بدل زر أصمّ لا يفسّر امتناعه.
    fun firstMissing(): String? = when {
        files.isEmpty() -> "اختر ملفاً صوتياً (أو سجّل مباشرة) أولاً."
        title.isBlank() -> "اكتب عنوان الدرس."
        categoryId == null -> "اختر القسم الرئيسي."
        subcategoryId == null && subsForCategory.isEmpty() ->
            "لا توجد أقسام فرعية لهذا القسم — أنشئ واحداً أو اختر قسماً آخر."
        subcategoryId == null -> "اختر القسم الفرعي."
        else -> null
    }

    /**
     * يُدرج الدرس في طابور الرفع ثم يُفرغ النموذج فوراً.
     * لا ينتظر شبكة ولا اكتمال رفع: الملفّ يُنسخ إلى تخزين التطبيق،
     * ويُختم زمن الإضافة الآن فيصل الدرس إلى التطبيق العام بترتيب إضافته.
     */
    fun queueLesson() {
        if (queuing) return
        val missing = firstMissing()
        if (missing != null) {
            message = missing
            isError = true
            return
        }
        queuing = true
        merging = false
        message = ""
        isError = false
        val snapshotTitle = title
        val cat = categories.firstOrNull { it.id == categoryId }
        val sub = subsForCategory.firstOrNull { it.id == subcategoryId }
        val label = listOfNotNull(cat?.name, sub?.name).joinToString(" ← ")
        // اسما القسمين يُرسَلان مع الدرس: الخادم كان يكتبهما فارغَين، فتسقط
        // هويّة القسم من نسخة الدرس في «سلة المحذوفات».
        val catLabel = cat?.name.orEmpty()
        val subLabel = sub?.name.orEmpty()
        // آخر قسم مستعمَل يُحفظ ليُعاد اختياره تلقائياً في الفتح القادم.
        AppPrefs.lastAddCategoryId = categoryId
        AppPrefs.lastAddSubcategoryId = subcategoryId
        scope.launch {
            try {
                val queued = if (files.size == 1) {
                    UploadQueue.enqueue(
                        context = context,
                        sourceUri = files.first().uri,
                        fileName = files.first().name,
                        title = snapshotTitle,
                        categoryId = categoryId!!,
                        subcategoryId = subcategoryId!!,
                        sectionLabel = label,
                        featured = featured,
                        featuredUntilMs = featuredUntil,
                        addedBy = AuthService.currentUser?.email.orEmpty(),
                        categoryName = catLabel,
                        subcategoryName = subLabel,
                        transcriptText = transcriptText,
                        transcriptBookTitle = transcriptBookTitle,
                        transcriptSourceRef = transcriptSourceRef,
                        transcriptImages = transcriptImages.toList(),
                    )
                } else {
                    // عدّة ملفات = درس واحد متّصل: يُدمج محليّاً أوّلاً (لا
                    // يحتاج شبكة) ثم يدخل الطابور ملفّاً واحداً. كل الملفات
                    // MP3 → لصق إطارات بلا إعادة ترميز؛ غير ذلك أو ترميزات
                    // MP3 متنافرة → فكّ الجميع وإعادة ترميز AAC/M4A — الدمج
                    // يصحّ مهما اختلفت الصيغ والناتج صيغة واحدة.
                    merging = true
                    val stamp = System.currentTimeMillis()
                    val locals = files.map { context.copyUriToCache(it.uri, it.name) }
                    var mergedName = "merged.mp3"
                    val merged = withContext(Dispatchers.IO) {
                        if (files.all { AudioMerger.isMp3(it.name) }) {
                            try {
                                AudioMerger.mergeMp3(
                                    inputs = locals,
                                    outputPath = File(
                                        context.cacheDir,
                                        "merged_$stamp.mp3",
                                    ).absolutePath,
                                )
                            } catch (_: Mp3FormatException) {
                                mergedName = "merged.m4a"
                                AudioTranscodeMerger.mergeToM4a(
                                    locals,
                                    File(context.cacheDir, "merged_$stamp.m4a").absolutePath,
                                )
                            }
                        } else {
                            mergedName = "merged.m4a"
                            AudioTranscodeMerger.mergeToM4a(
                                locals,
                                File(context.cacheDir, "merged_$stamp.m4a").absolutePath,
                            )
                        }
                    }
                    locals.forEach { runCatching { it.delete() } }
                    merging = false
                    UploadQueue.enqueueLocalFile(
                        file = merged,
                        fileName = mergedName,
                        title = snapshotTitle,
                        categoryId = categoryId!!,
                        subcategoryId = subcategoryId!!,
                        sectionLabel = label,
                        featured = featured,
                        featuredUntilMs = featuredUntil,
                        addedBy = AuthService.currentUser?.email.orEmpty(),
                        context = context,
                        categoryName = catLabel,
                        subcategoryName = subLabel,
                        transcriptText = transcriptText,
                        transcriptBookTitle = transcriptBookTitle,
                        transcriptSourceRef = transcriptSourceRef,
                        transcriptImages = transcriptImages.toList(),
                    )
                }
                // يُقرأ الموقع **قبل** إيقاظ العامل: ملفّ صغير قد يُرفع
                // ويخرج من الطابور قبل أن نصل إلى بناء الرسالة، فيصير
                // «الترتيب 0» بلا معنى.
                val position = UploadQueue.positionOf(queued.id)
                val total = UploadQueue.liveCount()
                LessonUploadWorker.kick(context)

                // إفراغ النموذج فوراً — المشرف يواصل إضافة درس آخر.
                // العنوان لا يُفرَغ إن كان جزءاً من سلسلة مرقّمة: يُقترح
                // التالي بزيادة رقمه، وهو الشكل الغالب لدروس المنبر.
                val suggestion = nextTitleSuggestion(snapshotTitle)
                title = suggestion
                files.clear()
                featured = false
                featuredUntil = null
                featuredLabel = ""
                transcriptText = ""
                transcriptBookTitle = ""
                transcriptSourceRef = ""
                transcriptImages.clear()
                transcriptOpen = false
                queuing = false
                // تأكيد **لكلّ إضافة** لا للأولى فقط: الرسالة الداخلية قد
                // تتطابق نصّاً مع سابقتها فلا يلحظ المشرف تغيّراً، فيُضاف
                // موقع الدرس في الدور ويُرفَق شريط سفليّ يظهر من جديد
                // مع كلّ إدراج.
                val order = if (total > 1 && position > 0) {
                    " (الترتيب $position من $total)"
                } else {
                    ""
                }
                message = "أُضيف «$snapshotTitle» إلى طابور الرفع$order — " +
                    "يكمل في الخلفية ويصلك إشعار عند اكتماله. " +
                    "تستطيع إضافة درس آخر الآن." +
                    if (suggestion.isNotEmpty()) {
                        " العنوان التالي مقترح: «$suggestion» — عدّله إن شئت."
                    } else {
                        ""
                    }
                isError = false
                snack("أُضيف «$snapshotTitle» إلى طابور الرفع$order")
            } catch (e: Exception) {
                queuing = false
                merging = false
                message = when (e) {
                    is AudioTranscodeMerger.UnsupportedAudioException ->
                        e.message ?: "تعذّر فكّ أحد الملفات الصوتية."
                    is Mp3FormatException ->
                        "تعذّر دمج الملفات — أحدها ليس ملفاً صوتياً سليماً."
                    else -> "تعذّر تجهيز الدرس: ${e.message ?: e}"
                }
                isError = true
            }
        }
    }

    if (showFeatureSheet) {
        FeatureDurationSheet(
            lessonTitle = title.ifBlank { "الدرس الجديد" },
            currentUntilMs = featuredUntil,
            onDismiss = {
                showFeatureSheet = false
                if (!featured) featuredLabel = ""
            },
            onPick = { duration ->
                showFeatureSheet = false
                featured = true
                featuredUntil = duration.untilMs()
                featuredLabel = duration.label
            },
        )
    }

    if (showRecorder) {
        RecordSheet(
            onDismiss = { showRecorder = false },
            onRecorded = { file, name ->
                showRecorder = false
                // التسجيل (m4a) صار يقبل الدمج كأي صيغة أخرى: يُلحق بالقائمة
                // بدل أن يمحوها — سجّل مقاطع متتابعة أو اخلطها بملفات مختارة.
                if (files.size >= AudioMerger.maxFiles) {
                    message = "الحد الأقصى ${AudioMerger.maxFiles} ملفات للدرس الواحد " +
                        "— لم يُضف التسجيل."
                    isError = true
                } else {
                    files.add(
                        PickedFile(
                            uri = android.net.Uri.fromFile(file),
                            name = name,
                            size = file.length(),
                        ),
                    )
                    message = ""
                    isError = false
                }
            },
        )
    }

    AdminScaffold(title = "إضافة درس صوتي", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            // إرشاد ثابت وموجز: يزيل سؤال «هل أنتظر انتهاء الرفع؟».
            item { QueueHintCard(Modifier.padding(bottom = 8.dp)) }
            // مؤشّر حيّ لما يُرفع الآن وما ينتظر الدور.
            item { UploadQueueBanner(Modifier.padding(bottom = 8.dp)) }
            item {
                AdminTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = "عنوان الدرس الصوتي",
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch("audio/*") },
                        enabled = !queuing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            if (files.isEmpty()) Icons.Filled.AudioFile else Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (files.isEmpty()) {
                                "اختر ملفاً (أو عدّة)"
                            } else {
                                "إضافة ملفات (${files.size}/${AudioMerger.maxFiles})"
                            },
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { showRecorder = true },
                        enabled = !queuing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(6.dp))
                        Text("تسجيل مباشر", overflow = TextOverflow.Ellipsis, maxLines = 1)
                    }
                }
                if (files.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            "ستُدمج ${files.size} ملفات بالترتيب أدناه في درس واحد " +
                                "متصل — استعمل الأسهم لإعادة الترتيب.",
                            fontSize = 13.sp,
                            lineHeight = 22.sp,
                        )
                    }
                }
            }

            itemsIndexed(files) { index, file ->
                FileRow(
                    index = index,
                    name = file.name,
                    canMoveUp = index > 0,
                    canMoveDown = index < files.size - 1,
                    enabled = !queuing,
                    showReorder = files.size > 1,
                    onUp = {
                        val item = files.removeAt(index)
                        files.add(index - 1, item)
                    },
                    onDown = {
                        val item = files.removeAt(index)
                        files.add(index + 1, item)
                    },
                    onRemove = { files.removeAt(index) },
                )
            }

            item {
                Spacer(Modifier.height(14.dp))
                AdminDropdown(
                    label = "القسم الرئيسي",
                    items = categories,
                    selected = categories.firstOrNull { it.id == categoryId },
                    itemLabel = { it.name },
                    enabled = !queuing,
                    onSelected = {
                        categoryId = it.id
                        subcategoryId = null
                    },
                )
                if (categoryId != null) {
                    Spacer(Modifier.height(14.dp))
                    AdminDropdown(
                        label = "القسم الفرعي",
                        items = subsForCategory,
                        selected = subsForCategory.firstOrNull { it.id == subcategoryId },
                        itemLabel = { it.name },
                        enabled = !queuing,
                        onSelected = { subcategoryId = it.id },
                    )
                    if (subsForCategory.isEmpty()) {
                        Text(
                            "لا توجد أقسام فرعية لهذا القسم — أنشئ واحداً أولاً.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = featured,
                        onCheckedChange = {
                            if (queuing) return@Checkbox
                            if (it) {
                                // التمييز يلزمه مدّة — لا يُترك دائماً بالصدفة.
                                showFeatureSheet = true
                            } else {
                                featured = false
                                featuredUntil = null
                                featuredLabel = ""
                            }
                        },
                        enabled = !queuing,
                    )
                    Icon(Icons.Filled.Star, contentDescription = null, tint = adminGold)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("تمييز الدرس (مختارات المنبر)")
                        if (featured) {
                            Text(
                                featuredLabel,
                                fontSize = 11.sp,
                                color = adminGold,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 📖 «النص المشروح» الاختياري — يُنشر مع الدرس فور اكتمال رفعه.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(12.dp)),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { transcriptOpen = !transcriptOpen },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "النص المشروح (اختياري)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    "نص المقطع من الكتاب أو صور صفحاته — يظهر في " +
                                        "شاشة تشغيل الدرس.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (transcriptOpen) {
                                    Icons.Filled.ArrowUpward
                                } else {
                                    Icons.Filled.ArrowDownward
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (transcriptOpen) {
                            Spacer(Modifier.height(10.dp))
                            AdminTextField(
                                value = transcriptBookTitle,
                                onValueChange = { if (it.length <= 200) transcriptBookTitle = it },
                                label = "اسم الكتاب/المتن (اختياري)",
                                enabled = !queuing,
                            )
                            Spacer(Modifier.height(8.dp))
                            AdminTextField(
                                value = transcriptSourceRef,
                                onValueChange = { if (it.length <= 300) transcriptSourceRef = it },
                                label = "المقطع (من … إلى …) — اختياري",
                                enabled = !queuing,
                            )
                            Spacer(Modifier.height(8.dp))
                            AdminTextField(
                                value = transcriptText,
                                onValueChange = { if (it.length <= 20000) transcriptText = it },
                                label = "النص المشروح",
                                singleLine = false,
                                minLines = 4,
                                maxLines = 10,
                                enabled = !queuing,
                            )
                            Spacer(Modifier.height(8.dp))
                            AdminTranscriptImagesEditor(
                                images = transcriptImages,
                                enabled = !queuing,
                                onError = { errorText ->
                                    message = errorText
                                    isError = true
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (merging) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "جارٍ دمج الملفات في مقطع واحد…",
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (message.isNotEmpty()) {
                    Text(
                        message,
                        color = if (isError) MaterialTheme.colorScheme.error else adminGreen,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
                Button(
                    onClick = { queueLesson() },
                    enabled = !queuing,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (queuing) "جارٍ التجهيز…" else "رفع الدرس الصوتي")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 💡 بطاقة إرشاد ثابتة أعلى الشاشة — تجيب صراحةً عمّا يُربك المشرف:
 * لا انتظار بين درس وآخر، والرفع لا يتوقّف بإغلاق الشاشة، والإشعار يخبره.
 */
@Composable
private fun QueueHintCard(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "أضف بلا انتظار",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "• أضف درساً جديداً حتى أثناء رفع الدرس السابق — الطابور " +
                    "يرفعها واحداً تلو الآخر بالترتيب.\n" +
                    "• الرفع يستمرّ في الخلفية ولو أغلقت هذه الشاشة أو التطبيق.\n" +
                    "• يصلك إشعار فور اكتمال رفع كلّ درس.",
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileRow(
    index: Int,
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    showReorder: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "إزالة",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showReorder) {
            IconButton(onClick = onUp, enabled = enabled && canMoveUp) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "أعلى",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDown, enabled = enabled && canMoveDown) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = "أسفل",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
