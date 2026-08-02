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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import com.ali.ishaqiyin_admin.util.ImageMerger
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import kotlinx.coroutines.launch

/**
 * 🖼️ محرّر صور صفحات الكتاب — يُستعمل في كل نماذج «ساهم بالنص»:
 * إضافة (حتى 4)، قصّ كل صورة، ترتيب صريح بالأسهم (أيّها أولاً)، ودمج
 * الصور المرتَّبة عموديّاً في صورة واحدة (نظير دمج ملفات MP3 في الصوتيات).
 */
@Composable
fun AdminTranscriptImagesEditor(
    images: SnapshotStateList<Uri>,
    enabled: Boolean,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropIndex by remember { mutableIntStateOf(-1) }
    var merging by remember { mutableStateOf(false) }
    // «القصّ أثناء المعاينة»: كل صورة تُختار تمرّ بشاشة القصّ **قبل** إدراجها
    // — لا اختيار ثم قصّ لاحق. الإلغاء داخل شاشة القصّ يعني «أدرِجها كما هي».
    val pendingNew = remember { mutableStateListOf<Uri>() }
    var cropActive by remember { mutableStateOf(false) }

    fun cropOptions(uri: Uri) = CropImageContractOptions(
        uri,
        CropImageOptions(
            activityTitle = "قصّ صورة الصفحة",
            cropMenuCropButtonTitle = "تم",
            // إبقاء مقابض القص داخل الشاشة يجعل الحافتين العلوية والسفلية
            // قابلتين للسحب، مع السماح بتغيير الارتفاع والعرض بحرية.
            initialCropWindowPaddingRatio = 0.08f,
            canChangeCropWindow = true,
            guidelines = com.canhub.cropper.CropImageView.Guidelines.ON,
        ),
    )

    val cropper = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (cropIndex >= 0) {
            // إعادة قصّ صورة مدرجة.
            val index = cropIndex
            cropIndex = -1
            if (result.isSuccessful && index in images.indices) {
                result.uriContent?.let { images[index] = it }
            }
            return@rememberLauncherForActivityResult
        }
        // قصّ صورة جديدة قبل الإدراج: الناتج المقصوص أو الأصل عند الإلغاء.
        val source = pendingNew.removeFirstOrNull()
        val final = if (result.isSuccessful) (result.uriContent ?: source) else source
        if (final != null && images.size < TranscriptsRepository.MAX_IMAGES) {
            images.add(final)
        }
        cropActive = false
    }

    // سلسلة القصّ: صورة تلو أخرى حتى تفرغ قائمة المنتظرات.
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
            onError("الحد الأقصى ${TranscriptsRepository.MAX_IMAGES} صور.")
        }
    }

    fun crop(index: Int) {
        cropIndex = index
        cropper.launch(cropOptions(images[index]))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "صور صفحات الكتاب (${images.size}/${TranscriptsRepository.MAX_IMAGES})",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { picker.launch("image/*") },
            enabled = enabled && images.size < TranscriptsRepository.MAX_IMAGES,
        ) {
            Icon(
                Icons.Filled.AddPhotoAlternate,
                contentDescription = "إرفاق صورة من الكتاب",
                tint = kTeal,
            )
        }
    }
    ClipboardImageSuggestion(
        enabled = enabled && images.size + pendingNew.size < TranscriptsRepository.MAX_IMAGES,
        onImage = { uri -> pendingNew.add(uri) },
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (images.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(images.size) { i ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp)) {
                        AsyncImage(
                            model = images[i],
                            contentDescription = "صورة صفحة ${i + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = enabled) { crop(i) },
                        )
                        // رقم الترتيب: أيّ صفحة تُقرأ أولاً — وهو ترتيب الدمج نفسه.
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .size(20.dp)
                                .background(kTeal, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${i + 1}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        IconButton(
                            onClick = { images.removeAt(i) },
                            enabled = enabled,
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.TopEnd)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "إزالة",
                                tint = Color.White,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // في RTL «التالي» بصريّاً يساراً — أسهم منطقية لا بصرية.
                        IconButton(
                            onClick = {
                                val item = images.removeAt(i)
                                images.add(i - 1, item)
                            },
                            enabled = enabled && i > 0,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowForward,
                                contentDescription = "تقديم الصورة",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { crop(i) },
                            enabled = enabled,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Crop,
                                contentDescription = "قصّ الصورة",
                                tint = kTeal,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                val item = images.removeAt(i)
                                images.add(i + 1, item)
                            },
                            enabled = enabled && i < images.lastIndex,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = "تأخير الصورة",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        if (images.size >= 2) {
            Spacer(Modifier.height(6.dp))
            Text(
                "رتّب الصور بالأسهم (أيّها أولاً) — الدمج يلصقها عموديّاً بهذا الترتيب.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    merging = true
                    scope.launch {
                        runCatching {
                            ImageMerger.mergeVertically(context, images.toList())
                        }.onSuccess { merged ->
                            images.clear()
                            images.add(merged)
                        }.onFailure {
                            onError(it.message ?: "تعذّر دمج الصور.")
                        }
                        merging = false
                    }
                },
                enabled = enabled && !merging,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (merging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = kTeal,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("جارٍ الدمج…")
                } else {
                    Icon(
                        Icons.Filled.VerticalAlignCenter,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("دمج الصور في صورة واحدة (بالترتيب)")
                }
            }
        }
    }
}
