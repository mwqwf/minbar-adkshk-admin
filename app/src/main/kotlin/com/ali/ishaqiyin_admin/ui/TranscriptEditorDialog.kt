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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.VerticalAlignCenter
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.TranscriptsRepository
import com.ali.ishaqiyin_admin.util.ImageMerger
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import kotlinx.coroutines.launch

/**
 * صورة في المحرر: إمّا منشورة (remotePath+remoteUrl) أو مضافة الآن (local).
 * الرفع صار «محليّاً أولاً»: لا شيء يُرفع قبل ضغط «حفظ» — فيصحّ القصّ
 * والدمج وإعادة الترتيب والتراجع بلا ملفات يتيمة في التخزين.
 */
private data class EditorImage(
    val remotePath: String? = null,
    val remoteUrl: String? = null,
    val local: Uri? = null,
) {
    val isLocal: Boolean get() = local != null
    val model: Any get() = local ?: remoteUrl.orEmpty()
}

/**
 * 📖 محرر «النص المشروح» لدرس من شاشة الإدارة: نص المتن + اسم الكتاب +
 * نطاق المقطع + صور صفحات الكتاب (حتى 4) — بقصّ كل صورة، وترتيب صريح
 * بالأسهم، ودمج الصور المضافة عموديّاً بترتيبها (كدمج ملفات MP3)، مع
 * استخراج النص من الصور (OCR خادمي). الحفظ يرفع الجديد ثم يستدعي
 * upsertLessonTranscript (تحقق خادمي + روابط عامة + تنظيف اليتيم).
 */
@Composable
fun TranscriptEditorDialog(
    lessonId: String,
    lessonTitle: String,
    onDismiss: () -> Unit,
    initialText: String = "",
    initialImages: List<Uri> = emptyList(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var existed by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var bookTitle by remember { mutableStateOf("") }
    var sourceRef by remember { mutableStateOf("") }
    var viewingImage by remember { mutableStateOf<Any?>(null) }
    var cropIndex by remember { mutableIntStateOf(-1) }
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
                    transcript.images.forEach {
                        images.add(EditorImage(remotePath = it.path, remoteUrl = it.url))
                    }
                }
            }
            .onFailure { snack("تعذّر جلب النص الحالي.") }
        // حمولة المشاركة الخارجية (نص/صور) تُلحق بعد تحميل الموجود.
        if (initialText.isNotBlank() && text.isBlank()) text = initialText.take(20000)
        initialImages.forEach { uri ->
            if (images.size < TranscriptsRepository.MAX_IMAGES) {
                images.add(EditorImage(local = uri))
            }
        }
        loading = false
    }

    // «القصّ أثناء المعاينة»: كل صورة تُختار تمرّ بشاشة القصّ قبل إدراجها؛
    // الإلغاء داخل الشاشة يعني «أدرِجها كما هي».
    val pendingNew = remember { mutableStateListOf<Uri>() }
    var cropActive by remember { mutableStateOf(false) }

    fun cropOptions(uri: Uri) = CropImageContractOptions(
        uri,
        CropImageOptions(
            activityTitle = "قصّ صورة الصفحة",
            cropMenuCropButtonTitle = "تم",
            // نافذة القصّ تبدأ مغطّية الصورة كاملة (حتى الأعلى والأسفل) —
            // الهامش الافتراضي كان يوهم أن الأطراف خارج متناول القصّ.
            initialCropWindowPaddingRatio = 0f,
            guidelines = com.canhub.cropper.CropImageView.Guidelines.ON,
        ),
    )

    val cropper = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (cropIndex >= 0) {
            val index = cropIndex
            cropIndex = -1
            if (result.isSuccessful && index in images.indices) {
                result.uriContent?.let { images[index] = EditorImage(local = it) }
            }
            return@rememberLauncherForActivityResult
        }
        val source = pendingNew.removeFirstOrNull()
        val final = if (result.isSuccessful) (result.uriContent ?: source) else source
        if (final != null && images.size < TranscriptsRepository.MAX_IMAGES) {
            images.add(EditorImage(local = final))
        }
        cropActive = false
    }

    LaunchedEffect(pendingNew.size, cropActive) {
        if (!cropActive && pendingNew.isNotEmpty()) {
            cropActive = true
            cropper.launch(cropOptions(pendingNew.first()))
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        val remaining = TranscriptsRepository.MAX_IMAGES - images.size - pendingNew.size
        pendingNew.addAll(uris.take(remaining.coerceAtLeast(0)))
        if (uris.size > remaining) {
            snack("الحد الأقصى ${TranscriptsRepository.MAX_IMAGES} صور.")
        }
    }

    fun crop(index: Int) {
        val local = images[index].local ?: run {
            snack("القصّ متاح للصور المضافة الآن فقط — الصور المنشورة أعد إرفاقها لقصّها.")
            return
        }
        cropIndex = index
        cropper.launch(cropOptions(local))
    }

    viewingImage?.let { model ->
        Dialog(onDismissRequest = { viewingImage = null }) {
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
        onDismissRequest = { if (!saving) onDismiss() },
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
                        enabled = !saving,
                    )
                    Spacer(Modifier.height(8.dp))
                    AdminTextField(
                        value = sourceRef,
                        onValueChange = { if (it.length <= 300) sourceRef = it },
                        label = "المقطع (من … إلى …) — اختياري",
                        enabled = !saving,
                    )
                    Spacer(Modifier.height(8.dp))
                    AdminTextField(
                        value = text,
                        onValueChange = { if (it.length <= 20000) text = it },
                        label = "النص المشروح",
                        singleLine = false,
                        minLines = 6,
                        maxLines = 14,
                        enabled = !saving,
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
                            enabled = !saving && images.size < TranscriptsRepository.MAX_IMAGES,
                        ) {
                            Icon(
                                Icons.Filled.AddPhotoAlternate,
                                contentDescription = "إرفاق صورة",
                                tint = kTeal,
                            )
                        }
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(images.size) { i ->
                                val image = images[i]
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(92.dp)) {
                                        AsyncImage(
                                            model = image.model,
                                            contentDescription = "صورة صفحة ${i + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(kBoxBg, RoundedCornerShape(8.dp))
                                                .clickable { viewingImage = image.model },
                                        )
                                        // رقم الترتيب — أيّ صفحة أولاً (وهو ترتيب الدمج).
                                        Box(
                                            Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .size(18.dp)
                                                .background(kTeal, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "${i + 1}",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        IconButton(
                                            onClick = { images.removeAt(i) },
                                            enabled = !saving,
                                            modifier = Modifier
                                                .size(22.dp)
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
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                val item = images.removeAt(i)
                                                images.add(i - 1, item)
                                            },
                                            enabled = !saving && i > 0,
                                            modifier = Modifier.size(26.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.ArrowForward,
                                                contentDescription = "تقديم",
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { crop(i) },
                                            enabled = !saving,
                                            modifier = Modifier.size(26.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Crop,
                                                contentDescription = "قصّ",
                                                tint = if (image.isLocal) kTeal else kMuted,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val item = images.removeAt(i)
                                                images.add(i + 1, item)
                                            },
                                            enabled = !saving && i < images.lastIndex,
                                            modifier = Modifier.size(26.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.ArrowBack,
                                                contentDescription = "تأخير",
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (images.size >= 2) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "رتّب الصور بالأسهم (أيّها أولاً) — الدمج يلصقها " +
                                    "عموديّاً بهذا الترتيب.",
                                fontSize = 11.sp,
                                color = kMuted,
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    if (!images.all { it.isLocal }) {
                                        snack(
                                            "الدمج متاح للصور المضافة الآن فقط — " +
                                                "أزل المنشورة أو أعد إرفاقها.",
                                        )
                                        return@OutlinedButton
                                    }
                                    merging = true
                                    scope.launch {
                                        runCatching {
                                            ImageMerger.mergeVertically(
                                                context,
                                                images.mapNotNull { it.local },
                                            )
                                        }.onSuccess { merged ->
                                            images.clear()
                                            images.add(EditorImage(local = merged))
                                            snack("دُمجت الصور في صورة واحدة.")
                                        }.onFailure {
                                            snack(it.message ?: "تعذّر دمج الصور.")
                                        }
                                        merging = false
                                    }
                                },
                                enabled = !saving && !merging,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (merging) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = kTeal,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("جارٍ الدمج…")
                                } else {
                                    Icon(
                                        Icons.Filled.VerticalAlignCenter,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("دمج الصور في صورة واحدة (بالترتيب)")
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // OCR على المنشور فقط (الجديدة لم تُرفع بعد فلا يراها الخادم).
                        val remotePaths = images.mapNotNull { it.remotePath }
                        if (remotePaths.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    extracting = true
                                    scope.launch {
                                        try {
                                            val parts = remotePaths.mapNotNull { path ->
                                                runCatching {
                                                    TranscriptsRepository.extractText(path)
                                                }.getOrNull()?.takeIf { it.isNotBlank() }
                                            }
                                            if (parts.isEmpty()) {
                                                snack("لم يُستخرج نص من الصور المنشورة.")
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
                                enabled = !extracting && !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (extracting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = kTeal,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("جارٍ الاستخراج…")
                                } else {
                                    Icon(
                                        Icons.Filled.TextSnippet,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("استخراج النص من الصور المنشورة (OCR)")
                                }
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
                    // النقص يُشرح بعينه — الزر لا يُعطَّل إلا أثناء الحفظ.
                    if (text.trim().length < 10 && images.isEmpty()) {
                        snack("أدخل نص المقطع (١٠ أحرف على الأقل) أو أرفق صورة صفحة واحدة.")
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        runCatching {
                            // «محلي أولاً»: الصور الجديدة تُرفع الآن فقط، ثم
                            // يُرسل الترتيب الكامل (منشور + جديد) كما رتّبه المشرف.
                            val orderedPaths = images.map { image ->
                                image.remotePath
                                    ?: TranscriptsRepository.uploadTranscriptImage(
                                        context,
                                        lessonId,
                                        requireNotNull(image.local),
                                    )
                            }
                            TranscriptsRepository.upsert(
                                lessonId = lessonId,
                                text = text,
                                bookTitle = bookTitle,
                                sourceRef = sourceRef,
                                imagePaths = orderedPaths,
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
                enabled = !loading && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = kTeal),
            ) { Text(if (saving) "جارٍ الحفظ…" else "حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("إغلاق") }
        },
    )
}
