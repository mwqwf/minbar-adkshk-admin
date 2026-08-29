package com.ali.ishaqiyin_admin.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.ali.ishaqiyin_admin.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 📸 «إرفاق لقطة الشاشة الأخيرة» — قرار المالك النهائي (2026-08-29):
 * «لا أريد الولوج للمعرض نهائياً». الشريحة تستعلم MediaStore عن **أحدث
 * لقطة شاشة** (خلال آخر 10 دقائق فقط)، تعرض مصغّرتها، والنقر يمرّرها
 * **مباشرة** إلى مسار الإرفاق القائم — بلا منتقٍ ولا أيّ شاشة وسيطة،
 * تماماً كشريحة اقتراح الحافظة في لوحات المفاتيح (نمط Gboard).
 *
 * الإذن يُطلب **مرّة واحدة** عند أوّل ظهور محتمل؛ الرفض يُسجَّل ولا يُلَحّ
 * بعده أبداً — تسقط الشريحة بصمت وتبقى شريحة الحافظة (بلا إذن).
 * الوصول الجزئي (READ_MEDIA_VISUAL_USER_SELECTED على 14+) مقبول: إن لم
 * تكن بين الصور المسموحة لقطة حديثة فلا شريحة، بلا خطأ ولا إلحاح.
 */

/** أعلى لقطة أُغلق اقتراحها (بمعرّف MediaStore) — لا تُقترح ثانيةً. */
private object ScreenshotDismiss {
    var dismissedId: Long = -1L
}

private data class ScreenshotCandidate(val id: Long, val uri: Uri)

/** أحدث لقطة شاشة خلال آخر 10 دقائق، أو null. يعمل على خيط IO. */
private fun latestScreenshot(context: Context): ScreenshotCandidate? = runCatching {
    val tenMinutesAgo = System.currentTimeMillis() / 1000L - 10 * 60
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
    )
    // مجلد اللقطات: بالاسم (كل الإصدارات) أو بالمسار النسبي (10+).
    val bucket = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        " OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    } else {
        ""
    }
    val selection = "($bucket$path) AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
    val args = if (path.isEmpty()) {
        arrayOf("%Screenshots%", tenMinutesAgo.toString())
    } else {
        arrayOf("%Screenshots%", "%Screenshots%", tenMinutesAgo.toString())
    }
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@runCatching null
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        ScreenshotCandidate(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
        )
    }
}.getOrNull()

/** الأذونات المناسبة لإصدار النظام (كامل + جزئي على 14+). */
private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** كامل أو جزئي — أيّهما يكفي للاستعلام (الجزئي يرى الصور المسموحة فقط). */
private fun hasMediaAccess(context: Context): Boolean =
    mediaPermissions().any { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

/**
 * الشريحة. تُوضع فوق حقل الإدخال/منطقة الإرفاق. [enabled] يطفئها مؤقّتاً
 * (رفع جارٍ، مجموعة مقفلة، بلوغ سقف الصور…). النقر يستدعي [onPick]
 * بـUri اللقطة نفسها فتدخل مسار الإرفاق القائم مباشرةً.
 */
@Composable
fun RecentScreenshotChip(
    enabled: Boolean,
    onPick: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasMediaAccess(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var candidate by remember { mutableStateOf<ScreenshotCandidate?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        granted = hasMediaAccess(context)
        refresh++
    }

    // العودة للمقدّمة تعيد الفحص: لقطة جديدة قد أُخذت والتطبيق بالخلفية.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasMediaAccess(context)
                refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // طلب الإذن مرّة واحدة فقط في عمر التطبيق كلّه — عند أوّل ظهور محتمل.
    // الرفض يبقى محفوظاً فلا سؤال ثانياً أبداً (لا إلحاح).
    LaunchedEffect(enabled, granted) {
        if (enabled && !granted && !AppPrefs.mediaPermissionAsked) {
            AppPrefs.mediaPermissionAsked = true
            permissionLauncher.launch(mediaPermissions())
        }
    }

    LaunchedEffect(refresh, enabled, granted) {
        candidate = if (!enabled || !granted) {
            null
        } else {
            withContext(Dispatchers.IO) { latestScreenshot(context) }
                ?.takeIf { it.id != ScreenshotDismiss.dismissedId }
        }
    }

    val shown = candidate ?: return
    if (!enabled) return

    fun dismiss() {
        ScreenshotDismiss.dismissedId = shown.id
        candidate = null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier
                // الشريحة كلّها هدف نقر واحد كبير — أسهل من إصابة زرّ صغير.
                .clickable {
                    dismiss()
                    onPick(shown.uri)
                }
                .padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 6.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = shown.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("إرفاق لقطة الشاشة الأخيرة", fontSize = 12.5.sp)
                Text(
                    "بنقرة واحدة — بلا فتح المعرض",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    dismiss()
                    onPick(shown.uri)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("إرفاق") }
            IconButton(onClick = ::dismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "إخفاء الاقتراح")
            }
        }
    }
}
