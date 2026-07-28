package com.ali.ishaqiyin_admin.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.LessonUploadWorker
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.util.AudioMerger
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

    // ملفّ وارد من مشاركة خارجية: عبّئ الحقل واقترح عنواناً من اسمه.
    val sharedFile = remember { ShareIntake.peek() }
    var sharedConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (sharedFile != null) {
            files.add(sharedFile)
            title = smartTitleFromFileName(sharedFile.name)
        }
        runCatching {
            categories = AdminRepository.fetchCategories()
            subcategories = AdminRepository.fetchSubcategories()
        }
    }

    // النموذج انتهى؛ أكّد استهلاك ملفّ المشاركة سواء رُفع أو أُلغي العمل.
    DisposableEffect(Unit) {
        onDispose {
            if (sharedFile != null && !sharedConsumed) ShareIntake.consumeFirst()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val picked = uris.map { context.pickedFileFrom(it) }
        val existing = files.map { it.uri.toString() }.toSet()
        val combined = files + picked.filter { it.uri.toString() !in existing }

        // الدمج المباشر (لصق الإطارات) لا يصح إلا لملفات MP3.
        if (combined.size > 1) {
            val bad = combined.firstOrNull { !AudioMerger.isMp3(it.name) }
            if (bad != null) {
                message = "لدمج عدة ملفات يجب أن تكون جميعها MP3 — «${bad.name}» ليس كذلك."
                isError = true
                return@rememberLauncherForActivityResult
            }
        }
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
    val canQueue = title.isNotBlank() && categoryId != null && subcategoryId != null &&
        files.isNotEmpty() && !queuing

    /**
     * يُدرج الدرس في طابور الرفع ثم يُفرغ النموذج فوراً.
     * لا ينتظر شبكة ولا اكتمال رفع: الملفّ يُنسخ إلى تخزين التطبيق،
     * ويُختم زمن الإضافة الآن فيصل الدرس إلى التطبيق العام بترتيب إضافته.
     */
    fun queueLesson() {
        if (!canQueue) {
            message = "يرجى تعبئة جميع الحقول واختيار ملف صوتي."
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
        scope.launch {
            try {
                if (files.size == 1) {
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
                    )
                } else {
                    // عدّة ملفات = درس واحد متّصل: يُدمج محليّاً أوّلاً (لا
                    // يحتاج شبكة) ثم يدخل الطابور ملفّاً واحداً.
                    merging = true
                    val locals = files.map { context.copyUriToCache(it.uri, it.name) }
                    val out = File(context.cacheDir, "merged_${System.currentTimeMillis()}.mp3")
                    val merged = withContext(Dispatchers.IO) {
                        AudioMerger.mergeMp3(inputs = locals, outputPath = out.absolutePath)
                    }
                    locals.forEach { runCatching { it.delete() } }
                    merging = false
                    UploadQueue.enqueueLocalFile(
                        file = merged,
                        fileName = "merged.mp3",
                        title = snapshotTitle,
                        categoryId = categoryId!!,
                        subcategoryId = subcategoryId!!,
                        sectionLabel = label,
                        featured = featured,
                        featuredUntilMs = featuredUntil,
                        addedBy = AuthService.currentUser?.email.orEmpty(),
                    )
                }
                LessonUploadWorker.kick(context)

                // إفراغ النموذج فوراً — المشرف يواصل إضافة درس آخر.
                title = ""
                files.clear()
                featured = false
                featuredUntil = null
                featuredLabel = ""
                queuing = false
                if (sharedFile != null && !sharedConsumed) {
                    sharedConsumed = true
                    ShareIntake.consumeFirst()
                }
                message = "أُضيف «$snapshotTitle» إلى طابور الرفع — يكمل وحده."
                isError = false
            } catch (e: Exception) {
                queuing = false
                merging = false
                message = if (e is Mp3FormatException) {
                    "تعذّر دمج الملفات — تأكد أنها ملفات MP3 سليمة."
                } else {
                    "تعذّر تجهيز الدرس: ${e.message ?: e}"
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
                // التسجيل (m4a) لا يُدمج مع ملفات — يحلّ محلّ الاختيار الحالي.
                files.clear()
                files.add(
                    PickedFile(
                        uri = android.net.Uri.fromFile(file),
                        name = name,
                        size = file.length(),
                    ),
                )
                message = ""
                isError = false
            },
        )
    }

    AdminScaffold(title = "إضافة درس صوتي", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
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
                            tint = kTeal,
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
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = kDanger)
                        Spacer(Modifier.size(6.dp))
                        Text("تسجيل مباشر", overflow = TextOverflow.Ellipsis, maxLines = 1)
                    }
                }
                if (files.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(kTeal.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
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
                            color = kDanger,
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
                    Icon(Icons.Filled.Star, contentDescription = null, tint = kGold)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("تمييز الدرس (مختارات المنبر)")
                        if (featured) {
                            Text(
                                featuredLabel,
                                fontSize = 11.sp,
                                color = kGold,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (merging) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = kTeal,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "جارٍ دمج الملفات في مقطع واحد…",
                        color = kTeal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (message.isNotEmpty()) {
                    Text(
                        message,
                        color = if (isError) kDanger else kGreen,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
                Button(
                    onClick = { queueLesson() },
                    enabled = canQueue,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = kTeal),
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
            Modifier.size(26.dp).background(kTeal.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", fontSize = 12.sp, color = kTeal)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = kTeal,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "إزالة",
                tint = kDanger,
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
