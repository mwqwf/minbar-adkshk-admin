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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Screenshot
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 📸 «إرفاق آخر لقطة شاشة» — كما في واتساب/تلغرام: التقطتَ لقطةً قبل قليل
 * ثمّ فتحتَ مكاناً يقبل الصور ⇒ بطاقة صغيرة تقترحها، بنقرة واحدة تُرفَق.
 *
 * الخصوصيّة أوّلاً (الجمهور لا يتقن التقنية):
 * - لا يُطلب أيّ إذن عند الإقلاع ولا عند فتح الشاشة — أوّل نقرة على البطاقة
 *   هي التي تطلبه (إذن كسول).
 * - الاستعلام مقصور على مجلد Screenshots وحده وبعمر دقيقتين كحدّ أقصى —
 *   لا نلمس بقيّة الاستوديو إطلاقاً.
 * - رفضُ الإذن مرّتين = لا تظهر البطاقة بعدها أبداً (لا إلحاح).
 * - على أندرويد 14 «الوصول الجزئي للصور» يعمل الاستعلام على ما سُمح به فقط
 *   وهذا مقبول عمداً.
 */

/** لقطة مرشَّحة: معرّف MediaStore (لعدم تكرار الاقتراح) مع رابط المحتوى. */
private data class RecentShot(val id: Long, val uri: Uri)

// أسماء التخزين المحلّي — داخل هذا الملف وحده كما تقتضي المواصفة
// (لا نلمس LocalStore/AppPrefs).
private const val PREFS = "recent_screenshot_suggestion"
private const val KEY_DENIED = "denied_count"
private const val KEY_LAST_SUGGESTED = "last_suggested_id"

// أقصى عمر للّقطة المقترَحة: دقيقتان — أقدم من ذلك لم يعد «قبل قليل».
private const val MAX_AGE_MS = 2 * 60 * 1000L

private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** الإذن المناسب لإصدار النظام: صور فقط على 13+ والقراءة العامّة قبلها. */
private val mediaPermission: String =
    if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * هل نملك أيّ وصول للصور؟ يشمل «الوصول الجزئي» في أندرويد 14: عنده يكون
 * READ_MEDIA_IMAGES مرفوضاً لكنّ READ_MEDIA_VISUAL_USER_SELECTED ممنوحاً،
 * والاستعلام يعمل على ما اختاره المستخدم فقط — وهذا مقبول.
 */
private fun hasMediaAccess(context: Context): Boolean {
    fun granted(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return granted(mediaPermission) ||
        (
            Build.VERSION.SDK_INT >= 34 &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            )
}

/**
 * أحدث لقطة شاشة (مجلد Screenshots فقط) بعمر ≤ دقيقتين، أو null.
 * تُستدعى على Dispatchers.IO دائماً، وأيّ فشل صامت — ميزة رفاهية لا
 * تستحقّ رسالة خطأ.
 */
private fun queryLatestScreenshot(context: Context): RecentShot? = runCatching {
    val minDateSec = (System.currentTimeMillis() - MAX_AGE_MS) / 1000
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )
    // ترشيح المجلد داخل الاستعلام نفسه لا بعده: RELATIVE_PATH متاح من
    // أندرويد 10 (API 29)، وقبله يكفي اسم الحاوية BUCKET_DISPLAY_NAME.
    val folderClause = if (Build.VERSION.SDK_INT >= 29) {
        "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?)"
    } else {
        "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
    }
    val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND $folderClause"
    val args = if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(minDateSec.toString(), "%Screenshots%", "Screenshots")
    } else {
        arrayOf(minDateSec.toString(), "Screenshots")
    }
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        RecentShot(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
        )
    }
}.getOrNull()

/**
 * إخفاء البطاقة «بلا إذن» لبقيّة جلسة التطبيق عند إغلاقها — في الذاكرة لا
 * في التخزين: نقترح مجدّداً في جلسة قادمة، لكن لا نلاحق المستخدم في نفس
 * الجلسة على كلّ شاشة.
 */
private object SessionDismiss {
    var noPermissionCard = false
}

/**
 * البطاقة نفسها. تُوضع فوق حقل الإدخال/منطقة الإرفاق، وتظهر عند فتح
 * الشاشة أو العودة إليها (ON_RESUME) إن وُجدت لقطة حديثة لم تُقترح من قبل.
 * [enabled] يطفئها مؤقّتاً (رفع جارٍ، مجموعة مقفلة…).
 */
@Composable
fun RecentScreenshotChip(
    enabled: Boolean,
    onPick: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refresh by remember { mutableIntStateOf(0) }
    var shot by remember { mutableStateOf<RecentShot?>(null) }
    var hasAccess by remember { mutableStateOf(hasMediaAccess(context)) }
    // رفض مرّتين = صمت أبديّ (يُقرأ مرّة عند التركيب ويُحدَّث عند كل رفض).
    var refusedForever by remember {
        mutableStateOf(prefs(context).getInt(KEY_DENIED, 0) >= 2)
    }
    var dismissedHere by remember { mutableStateOf(false) }
    // مرآة حالة الإغلاق الجلسويّ: المتغيّر الساكن لا يُعيد التركيب بنفسه.
    var hidNoPermission by remember { mutableStateOf(SessionDismiss.noPermissionCard) }
    // بعد منح الإذن من نقرة البطاقة: أرفق أحدث لقطة مباشرة بلا نقرة ثانية.
    var attachAfterGrant by remember { mutableStateOf(false) }

    // العودة إلى الشاشة (ON_RESUME) تعيد الفحص — كنمط ClipboardImageSuggestion.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh++
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // «الوصول الجزئي» في أندرويد 14 يصل هنا كرفض ظاهريّ مع وصول فعليّ —
        // لذا نحتكم إلى hasMediaAccess لا إلى قيمة granted وحدها.
        if (granted || hasMediaAccess(context)) {
            hasAccess = true
            attachAfterGrant = true
            refresh++
        } else {
            val next = prefs(context).getInt(KEY_DENIED, 0) + 1
            prefs(context).edit().putInt(KEY_DENIED, next).apply()
            if (next >= 2) refusedForever = true
        }
    }

    LaunchedEffect(refresh, enabled) {
        // تحديث حالة الإذن مع كلّ عودة — قد يُمنح أو يُسحب من إعدادات النظام.
        hasAccess = hasMediaAccess(context)
        if (!enabled || refusedForever || !hasAccess) return@LaunchedEffect
        // الاستعلام على IO دائماً — لا لمس للقرص من خيط الواجهة.
        val latest = withContext(Dispatchers.IO) { queryLatestScreenshot(context) }
        if (latest == null) {
            // إن لم تُوجد لقطة حديثة نُبقي بطاقة معروضة كما هي (قد تكون
            // اللقطة تجاوزت الدقيقتين أثناء التأمّل) ولا نعرض جديداً.
            if (attachAfterGrant) attachAfterGrant = false
            return@LaunchedEffect
        }
        val store = prefs(context)
        if (attachAfterGrant) {
            // مُنح الإذن للتوّ من نقرة «أرسل آخر لقطة شاشة؟» — الإرفاق فوراً
            // هو ما وُعد به المستخدم بنقرته الأولى.
            attachAfterGrant = false
            store.edit().putLong(KEY_LAST_SUGGESTED, latest.id).apply()
            shot = null
            onPick(latest.uri)
            return@LaunchedEffect
        }
        if (latest.id == store.getLong(KEY_LAST_SUGGESTED, -1L)) return@LaunchedEffect
        // تُعلَّم اللقطة «اقتُرحت» فور عرضها كي لا تتكرّر البطاقة نفسها
        // في كل عودة أو شاشة أخرى.
        store.edit().putLong(KEY_LAST_SUGGESTED, latest.id).apply()
        shot = latest
        dismissedHere = false
    }

    if (!enabled || refusedForever) return

    val current = shot
    when {
        // مع الإذن ولقطة حديثة: بطاقة بمصغّرة اللقطة.
        current != null && !dismissedHere -> SuggestionCard(
            modifier = modifier,
            thumbnail = current.uri,
            title = "إرفاق آخر لقطة شاشة",
            onAttach = {
                shot = null
                onPick(current.uri)
            },
            onDismiss = {
                dismissedHere = true
                shot = null
            },
        )

        // بلا إذن: بطاقة بلا مصغّرة (لا نستطيع الاستعلام أصلاً) — أوّل نقرة
        // تطلب الإذن ثمّ تُرفق مباشرة.
        !hasAccess && !hidNoPermission -> SuggestionCard(
            modifier = modifier,
            thumbnail = null,
            title = "أرسل آخر لقطة شاشة؟",
            onAttach = { permissionLauncher.launch(mediaPermission) },
            onDismiss = {
                SessionDismiss.noPermissionCard = true
                hidNoPermission = true
            },
        )
    }
}

/**
 * شكل البطاقة — نفس لغة ClipboardImageSuggestion البصريّة (سطح مستدير
 * بحدود خفيفة) والسمتان تعملان عبر ألوان MaterialTheme، وكلّ أهداف اللمس
 * ≥ 48dp (جمهور غير تقنيّ).
 */
@Composable
private fun SuggestionCard(
    modifier: Modifier,
    thumbnail: Uri?,
    title: String,
    onAttach: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier
                // البطاقة كلّها هدف نقر واحد كبير — أسهل من إصابة زرّ صغير.
                .clickable(onClick = onAttach)
                .padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 6.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = "مصغّرة آخر لقطة شاشة",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    Modifier.size(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Screenshot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.5.sp)
                Text(
                    "بنقرة واحدة",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onAttach,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("إرفاق") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "إخفاء الاقتراح")
            }
        }
    }
}
