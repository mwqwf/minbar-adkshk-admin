package com.ali.ishaqiyin_admin.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.ali.ishaqiyin_admin.data.StorageService
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.UploadCanceller
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

@Composable
fun AddLessonScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var subcategories by remember { mutableStateOf<List<Subcategory>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var subcategoryId by remember { mutableStateOf<String?>(null) }

    /** الملفات المختارة بالترتيب — أكثر من ملف يعني دمجها في درس واحد. */
    val files = remember { mutableStateListOf<PickedFile>() }

    var uploading by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var progress by remember { mutableDoubleStateOf(0.0) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var featured by remember { mutableStateOf(false) }
    var publishAt by remember { mutableStateOf<Long?>(null) }
    var canceller by remember { mutableStateOf<UploadCanceller?>(null) }
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
    val canUpload = title.isNotBlank() && categoryId != null && subcategoryId != null &&
        files.isNotEmpty() && !uploading

    fun upload() {
        if (!canUpload) {
            message = "يرجى تعبئة جميع الحقول واختيار ملف صوتي."
            isError = true
            return
        }
        uploading = true
        merging = false
        progress = 0.0
        message = ""
        isError = false
        val activeCanceller = UploadCanceller()
        canceller = activeCanceller
        scope.launch {
            var mergedTemp: File? = null
            try {
                // ملف واحد يُرفع كما هو؛ أكثر من ملف يُدمج محلياً أولاً ثم يُرفع
                // الناتج درساً واحداً متصلاً بالترتيب الظاهر في القائمة.
                val uploadUri: android.net.Uri
                val srcName: String
                if (files.size == 1) {
                    uploadUri = files.first().uri
                    srcName = files.first().name
                } else {
                    merging = true
                    val locals = files.map { context.copyUriToCache(it.uri, it.name) }
                    val out = File(context.cacheDir, "merged_${System.currentTimeMillis()}.mp3")
                    mergedTemp = withContext(Dispatchers.IO) {
                        AudioMerger.mergeMp3(inputs = locals, outputPath = out.absolutePath)
                    }
                    locals.forEach { runCatching { it.delete() } }
                    merging = false
                    uploadUri = android.net.Uri.fromFile(mergedTemp)
                    srcName = "merged.mp3"
                }

                val filename = "${System.currentTimeMillis()}_$srcName"
                val up = StorageService.uploadFile(
                    uri = uploadUri,
                    folder = "lessons",
                    filename = filename,
                    canceller = activeCanceller,
                    onProgress = { progress = it },
                )
                try {
                    AdminRepository.addLesson(
                        title = title,
                        categoryId = categoryId!!,
                        subcategoryId = subcategoryId!!,
                        audioUrl = up.url,
                        audioStoragePath = up.path,
                        addedBy = AuthService.currentUser?.email.orEmpty(),
                        publishAtMs = publishAt,
                        featured = featured,
                    )
                } catch (writeError: Exception) {
                    // لا نترك ملفاً يتيماً إن رفض الخادم إنشاء وثيقة الدرس.
                    try {
                        StorageService.deleteFileOrThrow(up.path)
                    } catch (cleanupError: Exception) {
                        throw IllegalStateException(
                            "فشل إنشاء الدرس، وتعذّر أيضاً تنظيف الملف المرفوع: " +
                                "$writeError / $cleanupError",
                        )
                    }
                    throw writeError
                }
                uploading = false
                progress = 0.0
                title = ""
                files.clear()
                featured = false
                publishAt = null
                if (sharedFile != null && !sharedConsumed) {
                    sharedConsumed = true
                    ShareIntake.consumeFirst()
                }
                message = "تم رفع الملف وإضافة الدرس بنجاح!"
                isError = false
            } catch (e: Exception) {
                uploading = false
                merging = false
                progress = 0.0
                when {
                    StorageService.isCancellation(e) || activeCanceller.cancelled -> {
                        message = "أُلغي الرفع."
                        isError = false
                    }

                    e is Mp3FormatException -> {
                        message = "تعذّر دمج الملفات — تأكد أنها ملفات MP3 سليمة."
                        isError = true
                    }

                    else -> {
                        message = "خطأ أثناء الرفع: ${e.message ?: e}"
                        isError = true
                    }
                }
            } finally {
                canceller = null
                // الملف المدموج مؤقت — يُحذف بعد الرفع (أو الفشل) لتوفير المساحة.
                runCatching { mergedTemp?.delete() }
            }
        }
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
                        enabled = !uploading,
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
                        enabled = !uploading,
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
                    enabled = !uploading,
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
                    enabled = !uploading,
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
                        enabled = !uploading,
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
                        onCheckedChange = { if (!uploading) featured = it },
                        enabled = !uploading,
                    )
                    Icon(Icons.Filled.Star, contentDescription = null, tint = kTeal)
                    Spacer(Modifier.size(8.dp))
                    Text("تمييز الدرس (يظهر أعلى التطبيق)")
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = kTeal)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("جدولة النشر")
                        Text(
                            publishAt?.let { "يظهر للمستخدمين في: ${fmtDate(it)}" }
                                ?: "ينشر فوراً",
                            fontSize = 12.sp,
                            color = kMuted,
                        )
                    }
                    if (publishAt == null) {
                        TextButton(
                            onClick = {
                                pickDateTime(
                                    context,
                                    System.currentTimeMillis() + 3600_000,
                                ) { publishAt = it }
                            },
                            enabled = !uploading,
                        ) { Text("اختيار") }
                    } else {
                        IconButton(onClick = { publishAt = null }, enabled = !uploading) {
                            Icon(Icons.Filled.Clear, contentDescription = null, tint = kDanger)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (uploading) {
                    if (merging) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = kTeal,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { (progress / 100).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = kTeal,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (merging) {
                                "جارٍ دمج الملفات في مقطع واحد…"
                            } else {
                                "جارٍ الرفع… ${progress.toInt()}%"
                            },
                            color = kTeal,
                        )
                        if (!merging) {
                            Spacer(Modifier.size(12.dp))
                            TextButton(onClick = { canceller?.cancel() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = kDanger,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("إلغاء الرفع", color = kDanger)
                            }
                        }
                    }
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
                    onClick = { upload() },
                    enabled = canUpload,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = kTeal),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("رفع الدرس الصوتي")
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
