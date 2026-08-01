package com.ali.ishaqiyin_admin.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.TranscriptsRepository
import kotlinx.coroutines.launch

/** صورة في المحرر: مسار التخزين + نموذج العرض (رابط أو Uri محلي بعد الرفع). */
private data class EditorImage(val path: String, val model: Any)

/**
 * 📖 محرر «النص المشروح» لدرس من شاشة الإدارة: نص المتن الذي تشرحه
 * الصوتية + اسم الكتاب + نطاق المقطع + صور صفحات الكتاب (حتى 4)، مع
 * استخراج النص من الصور (OCR خادمي). الحفظ عبر upsertLessonTranscript
 * (تحقق خادمي + روابط عامة + تنظيف الصور اليتيمة).
 */
@Composable
fun TranscriptEditorDialog(
    lessonId: String,
    lessonTitle: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var existed by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var bookTitle by remember { mutableStateOf("") }
    var sourceRef by remember { mutableStateOf("") }
    var viewingImage by remember { mutableStateOf<Any?>(null) }
    val images = remember { mutableStateListOf<EditorImage>() }

    LaunchedEffect(lessonId) {
        runCatching { TranscriptsRepository.fetchTranscript(lessonId) }
            .onSuccess { transcript ->
                if (transcript != null) {
                    existed = true
                    text = transcript.text
                    bookTitle = transcript.bookTitle
                    sourceRef = transcript.sourceRef
                    images.clear()
                    transcript.images.forEach { images.add(EditorImage(it.path, it.url)) }
                }
            }
            .onFailure { snack("تعذّر جلب النص الحالي.") }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (images.size >= TranscriptsRepository.MAX_IMAGES) {
            snack("الحد الأقصى ${TranscriptsRepository.MAX_IMAGES} صور.")
            return@rememberLauncherForActivityResult
        }
        uploading = true
        scope.launch {
            runCatching {
                TranscriptsRepository.uploadTranscriptImage(context, lessonId, uri)
            }.onSuccess { path ->
                images.add(EditorImage(path, uri))
            }.onFailure {
                snack(it.message ?: "تعذّر رفع الصورة.")
            }
            uploading = false
        }
    }

    viewingImage?.let { model ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { viewingImage = null }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .clickable { viewingImage = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = "صورة الصفحة",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "حذف النص المشروح؟",
            body = "سيُحذف النص وكل صوره من درس «$lessonTitle» نهائياً.",
            confirmLabel = "حذف",
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                saving = true
                scope.launch {
                    runCatching { TranscriptsRepository.remove(lessonId) }
                        .onSuccess {
                            snack("حُذف النص المشروح.")
                            onDismiss()
                        }
                        .onFailure { snack("تعذّر الحذف: ${it.message.orEmpty()}") }
                    saving = false
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = { if (!saving && !uploading) onDismiss() },
        title = {
            Column {
                Text("النص المشروح", fontWeight = FontWeight.Bold)
                Text(
                    lessonTitle,
                    fontSize = 12.sp,
                    color = kMuted,
                    maxLines = 1,
                )
            }
        },
        text = {
            if (loading) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = kTeal) }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "النص الأصلي من الكتاب الذي تشرحه هذه الصوتية — " +
                            "يظهر للمستمعين في شاشة التشغيل.",
                        fontSize = 12.sp,
                        color = kMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    AdminTextField(
                        value = bookTitle,
                        onValueChange = { if (it.length <= 200) bookTitle = it },
                        label = "اسم الكتاب/المتن (اختياري)",
                    )
                    Spacer(Modifier.height(8.dp))
                    AdminTextField(
                        value = sourceRef,
                        onValueChange = { if (it.length <= 300) sourceRef = it },
                        label = "المقطع (من … إلى …) — اختياري",
                    )
                    Spacer(Modifier.height(8.dp))
                    AdminTextField(
                        value = text,
                        onValueChange = { if (it.length <= 20000) text = it },
                        label = "النص المشروح",
                        singleLine = false,
                        minLines = 6,
                        maxLines = 14,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "صور صفحات الكتاب (${images.size}/${TranscriptsRepository.MAX_IMAGES})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { picker.launch("image/*") },
                            enabled = !uploading && images.size < TranscriptsRepository.MAX_IMAGES,
                        ) {
                            if (uploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = kTeal,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.AddPhotoAlternate,
                                    contentDescription = "إرفاق صورة",
                                    tint = kTeal,
                                )
                            }
                        }
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images.size) { i ->
                                val image = images[i]
                                Box(Modifier.size(96.dp)) {
                                    AsyncImage(
                                        model = image.model,
                                        contentDescription = "صورة صفحة ${i + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(kBoxBg, RoundedCornerShape(8.dp))
                                            .clickable { viewingImage = image.model },
                                    )
                                    IconButton(
                                        onClick = { images.removeAt(i) },
                                        modifier = Modifier
                                            .size(24.dp)
                                            .align(Alignment.TopEnd)
                                            .background(
                                                Color.Black.copy(alpha = 0.55f),
                                                CircleShape,
                                            ),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "إزالة الصورة",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                extracting = true
                                scope.launch {
                                    try {
                                        val parts = images.mapNotNull { image ->
                                            runCatching {
                                                TranscriptsRepository.extractText(image.path)
                                            }.getOrNull()?.takeIf { it.isNotBlank() }
                                        }
                                        if (parts.isEmpty()) {
                                            snack("لم يُستخرج نص من الصور.")
                                        } else {
                                            val joined = parts.joinToString("\n\n")
                                            text = if (text.isBlank()) {
                                                joined
                                            } else {
                                                "$text\n\n$joined"
                                            }.take(20000)
                                            snack("أُلحق النص المستخرج — دقّقه قبل الحفظ.")
                                        }
                                    } catch (e: Exception) {
                                        snack(e.message ?: "تعذّر استخراج النص.")
                                    }
                                    extracting = false
                                }
                            },
                            enabled = !extracting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (extracting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = kTeal,
                                )
                                Spacer(Modifier.size(6.dp))
                                Text("جارٍ الاستخراج…")
                            } else {
                                Icon(
                                    Icons.Filled.TextSnippet,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("استخراج النص من الصور (OCR)")
                            }
                        }
                    }
                    if (existed) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = { confirmRemove = true },
                            enabled = !saving,
                        ) {
                            Text("حذف النص المشروح نهائياً", color = kDanger, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching {
                            TranscriptsRepository.upsert(
                                lessonId = lessonId,
                                text = text,
                                bookTitle = bookTitle,
                                sourceRef = sourceRef,
                                imagePaths = images.map { it.path },
                            )
                        }.onSuccess {
                            snack("حُفظ النص المشروح. ✅")
                            onDismiss()
                        }.onFailure {
                            snack("تعذّر الحفظ: ${it.message.orEmpty()}")
                        }
                        saving = false
                    }
                },
                enabled = !loading && !saving && !uploading &&
                    (text.trim().length >= 10 || images.isNotEmpty()),
                colors = ButtonDefaults.buttonColors(containerColor = kTeal),
            ) { Text(if (saving) "جارٍ الحفظ…" else "حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("إغلاق") }
        },
    )
}
