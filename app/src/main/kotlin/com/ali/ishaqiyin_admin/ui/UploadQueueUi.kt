package com.ali.ishaqiyin_admin.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.LessonUploadWorker
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.PendingUpload
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.data.arabicReason
import com.ali.ishaqiyin_admin.data.formatBytes
import kotlinx.coroutines.delay

/**
 * 📤 شريط طابور الرفع — يظهر وحده متى كان هناك درس قيد الرفع أو بانتظار
 * الشبكة، ويختفي وحده عند الفراغ. النقر يفتح تفاصيل الطابور.
 *
 * يعرض أيضاً **تأكيداً فوريّاً لكلّ إضافة** (اسم الملفّ وموقعه في الدور)،
 * لا للإضافة الأولى فقط: `atMs` في [com.ali.ishaqiyin_admin.data.JustEnqueued]
 * يتغيّر مع كلّ إدراج فيُعاد إظهار التأكيد ولو تطابق العنوان.
 */
@Composable
fun UploadQueueBanner(modifier: Modifier = Modifier) {
    val items by UploadQueue.items.collectAsState()
    val progress by UploadQueue.progress.collectAsState()
    val online by NetworkMonitor.online.collectAsState()
    val justAdded by UploadQueue.justEnqueued.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    // انزواء التأكيد وحده بعد لحظات — بلا زرّ إغلاق يدويّ.
    LaunchedEffect(justAdded?.id, justAdded?.atMs) {
        val added = justAdded ?: return@LaunchedEffect
        delay(CONFIRM_VISIBLE_MS)
        UploadQueue.clearJustEnqueued(added.id)
    }

    if (items.isEmpty()) return

    val waiting = !online || progress?.waitingForNetwork == true
    val current = progress
    // درس مركون = فشل رفعه نهائياً وخرج من دور الرفع (peek يستبعد المركون)،
    // فلا يجوز أن يُعرض «بانتظار الدور» — لن يُرفع أبداً بلا تدخّل المشرف.
    val parkedCount = items.count { it.parked }
    val idle = current == null && !waiting
    val alerting = parkedCount > 0 && idle
    val accent = when {
        alerting -> kDanger
        waiting -> kOrange
        else -> kTeal
    }
    val parkedText = if (parkedCount > 1) {
        "تعذّر رفع $parkedCount دروس — افتح للتفاصيل"
    } else {
        "تعذّر رفع درس — افتح للتفاصيل"
    }
    // العنوان المعروض: ما يُرفع الآن، وإلّا أوّل درس **حيّ** — المركون خرج
    // من الدور فلا يصلح أن يمثّل «التالي».
    val headline = current?.title?.takeIf { it.isNotBlank() }
        ?: items.firstOrNull { !it.parked }?.title?.takeIf { it.isNotBlank() }
        ?: items.firstOrNull()?.title.orEmpty()
    // موقع ما يُرفع الآن في الدور — يبقى «1 من ن» ما لم يبدأ رفع بعد.
    val activePosition = current?.id
        ?.let { id -> items.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?.plus(1)
        ?: 1

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable { showSheet = true }
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        alerting -> Icons.Filled.ErrorOutline
                        waiting -> Icons.Filled.CloudOff
                        else -> Icons.Filled.CloudUpload
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        headline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = kTealDark,
                    )
                    Text(
                        when {
                            waiting -> "بانتظار الإنترنت — سيُستأنف تلقائياً من حيث توقّف"
                            current != null -> "جارٍ الرفع… ${current.percent}%"
                            parkedCount > 0 -> parkedText
                            else -> "بانتظار الدور"
                        },
                        fontSize = 11.sp,
                        color = if (alerting) kDanger else kMuted,
                    )
                }
                if (items.size > 1) {
                    Box(
                        Modifier
                            .background(accent, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        // «س من ص» لا العدد وحده: يعرف المشرف أين وصل الدور.
                        Text(
                            "$activePosition من ${items.size}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (waiting || current == null) {
                LinearProgressIndicator(
                    progress = { (current?.percent ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.2f),
                )
            } else {
                LinearProgressIndicator(
                    progress = { current.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.2f),
                )
            }
            // الفشل الدائم لا يُخفيه انشغال الطابور بغيره: يبقى ظاهراً بلون
            // الخطر حتى يُعيد المشرف المحاولة أو يُلغيه.
            if (parkedCount > 0 && !idle) {
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(kDanger.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = kDanger,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        parkedText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = kDanger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            justAdded?.let { added ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(kGreen.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = kGreen,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "أُضيف إلى الطابور: ${added.fileName}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = kGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "الترتيب ${added.position} من ${added.total} — " +
                                "تابع إضافة درس آخر، الرفع يكمل وحده.",
                            fontSize = 10.5.sp,
                            color = kMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (showSheet) UploadQueueSheet(onDismiss = { showSheet = false })
}

/** مدّة بقاء تأكيد الإضافة ظاهراً قبل أن ينزوي وحده. */
private const val CONFIRM_VISIBLE_MS = 6_000L

/** تفاصيل الطابور: ترتيب الرفع، حالة كلّ درس، وإلغاء أو إعادة محاولة. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadQueueSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items by UploadQueue.items.collectAsState()
    val progress by UploadQueue.progress.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // الإلغاء يحذف النسخة المحليّة نهائياً — وهي النسخة الوحيدة للملفّ
    // المدموج (enqueueLocalFile تحذف المصدر) — فلا يقع بلا تأكيد صريح.
    var confirmCancel by remember { mutableStateOf<PendingUpload?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "طابور الرفع (${items.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = kTealDark,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "تُرفع بالترتيب المعروض، وأوّل درس أُضيف هو أوّل ما يظهر في " +
                    "التطبيق العام. أضف درساً جديداً ولو كان السابق قيد الرفع، " +
                    "وأغلق الشاشة أو التطبيق إن شئت — الرفع يكمل في الخلفية " +
                    "ويصلك إشعار عند اكتمال كلّ درس، وإشعار آخر إن تعذّر رفعه.",
                fontSize = 11.5.sp,
                color = kMuted,
                lineHeight = 18.sp,
            )
            // «إعادة محاولة الكل»: بعد انقطاع طويل قد يُركن عدّة دروس معاً،
            // فإعادتها واحداً واحداً عبء بلا سبب — تُرفع عنها جميعاً حالة
            // الركن ثمّ يوقَظ العامل مرّة واحدة فيمضي بها بالدور.
            val parkedItems = items.filter { it.parked }
            if (parkedItems.size > 1) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        parkedItems.forEach { UploadQueue.unpark(it.id) }
                        LessonUploadWorker.kick(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = kTeal,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("إعادة محاولة الكل (${parkedItems.size})")
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(360.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    val active = progress?.id == item.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(kBoxBg, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title.ifBlank { item.fileName },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildString {
                                    append(item.sectionLabel)
                                    if (item.sizeBytes > 0) {
                                        append(" • ")
                                        append(formatBytes(item.sizeBytes))
                                    }
                                },
                                fontSize = 10.5.sp,
                                color = kMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.lastError != null || item.parked) {
                                Spacer(Modifier.height(3.dp))
                                // السبب محفوظ في العنصر وكان يُخفى خلف نصّ
                                // ثابت. يُعرض الآن مترجماً: العربيّ كما هو،
                                // ورسالة Firebase الخام تسقط إلى صياغة
                                // عربية مفهومة بدل إنجليزية تُقلق بلا فائدة.
                                val reason = item.lastError
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { RuntimeException(it).arabicReason() }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = kDanger,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        buildString {
                                            append(
                                                if (item.parked) {
                                                    "توقّف الرفع — أعد المحاولة أو ألغِه"
                                                } else {
                                                    "تعذّر الرفع — أعد المحاولة"
                                                },
                                            )
                                            if (reason != null) {
                                                append("\nالسبب: ")
                                                append(reason)
                                            }
                                        },
                                        fontSize = 10.5.sp,
                                        lineHeight = 15.sp,
                                        color = kDanger,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else if (active) {
                                Spacer(Modifier.height(5.dp))
                                LinearProgressIndicator(
                                    progress = { (progress?.percent ?: 0) / 100f },
                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                    color = kTeal,
                                    trackColor = kTeal.copy(alpha = 0.2f),
                                )
                            }
                        }
                        if (item.lastError != null || item.parked) {
                            IconButton(
                                onClick = {
                                    // رفع الركن أولاً وإلّا بقي خارج الدور.
                                    UploadQueue.unpark(item.id)
                                    LessonUploadWorker.kick(context)
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "إعادة المحاولة",
                                    tint = kTeal,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        IconButton(onClick = { confirmCancel = item }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "إلغاء",
                                tint = kDanger,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    confirmCancel?.let { target ->
        val name = target.title.ifBlank { target.fileName }
        ConfirmDialog(
            title = "إلغاء رفع هذا الدرس؟",
            body = "سيُحذف «$name» من طابور الرفع، وتُحذف معه نسخته المحليّة " +
                "نهائياً بلا تراجع — وهي النسخة الوحيدة إن كان ملفّاً مدموجاً. " +
                "لن يُنشر الدرس في التطبيق العام.",
            confirmLabel = "إلغاء وحذف الملفّ",
            confirmColor = kDanger,
            onConfirm = {
                UploadQueue.cancel(target.id)
                confirmCancel = null
            },
            onDismiss = { confirmCancel = null },
        )
    }
}
