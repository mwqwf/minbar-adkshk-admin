package com.ali.ishaqiyin_admin.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import com.ali.ishaqiyin_admin.data.LessonUploadWorker
import com.ali.ishaqiyin_admin.data.NetworkMonitor
import com.ali.ishaqiyin_admin.data.PendingUpload
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.data.UploadState
import com.ali.ishaqiyin_admin.data.UploadWorkWatcher
import com.ali.ishaqiyin_admin.data.arabicReason
import com.ali.ishaqiyin_admin.data.formatBytes
import com.ali.ishaqiyin_admin.data.needsAttention
import kotlinx.coroutines.delay

/**
 * 📤 شريط طابور الرفع — يظهر وحده متى كان هناك درس قيد الرفع أو بانتظار
 * الشبكة، ويختفي وحده عند الفراغ. النقر يفتح تفاصيل الطابور.
 *
 * يعرض أيضاً **تأكيداً فوريّاً لكلّ إضافة** (اسم الملفّ وموقعه في الدور)،
 * لا للإضافة الأولى فقط: `atMs` في [com.ali.ishaqiyin_admin.data.JustEnqueued]
 * يتغيّر مع كلّ إدراج فيُعاد إظهار التأكيد ولو تطابق العنوان.
 *
 * ⛔ **كلّ** نصّ هنا مشتقّ من مصدر واحد من ثلاثة: حالة WorkManager نفسها
 * ([UploadWorkWatcher])، أو نبضة تقدّم عمرها أقلّ من [LIVE_PULSE_MS]، أو حالة
 * محفوظة على القرص ([PendingUpload.state]). كانت النصوص الخمسة تُشتقّ من
 * لقطة ذاكرة بلا ختم زمنيّ، فيسقط `when` إلى «بانتظار الدور» في ثلاث حالات
 * متناقضة تماماً: عمل يجري فعلاً، وعمل نائم في backoff، ولا عمل مجدول
 * إطلاقاً — وشريطٌ عند صفر فوقها يؤكّد الكذبة. لا يجوز أن يعود نصّ بلا مصدر.
 */
@Composable
fun UploadQueueBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by UploadQueue.items.collectAsState()
    val progress by UploadQueue.progress.collectAsState()
    val online by NetworkMonitor.online.collectAsState()
    val justAdded by UploadQueue.justEnqueued.collectAsState()
    val paused by UploadQueue.paused.collectAsState()
    val work by UploadWorkWatcher.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    // انزواء التأكيد وحده بعد لحظات — بلا زرّ إغلاق يدويّ.
    LaunchedEffect(justAdded?.id, justAdded?.atMs) {
        val added = justAdded ?: return@LaunchedEffect
        delay(CONFIRM_VISIBLE_MS)
        UploadQueue.clearJustEnqueued(added.id)
    }

    if (items.isEmpty()) return

    // ⏱ نبضة إعادة تركيب: عمرُ آخر نبضة تقدّم هو ما يقرّر النصّ، ولا شيء
    // يعيد التركيب حين **تتوقّف** النبضات — وهي بالضبط الحالة التي كان
    // يُعرض فيها «جارٍ الرفع… 43%» ثابتاً عشر دقائق بلا بايت واحد يُنقل.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }

    val current = progress
    // النبضة الحيّة وحدها — انظر [LIVE_PULSE_MS].
    val live = current?.takeIf { nowMs - it.atMs < LIVE_PULSE_MS }
    val fresh = live != null
    val interrupted = items.firstOrNull { it.state == UploadState.INTERRUPTED }
    // ما لا يمضي وحده: مركون، أو منقطع، أو فشل غير شبكيّ.
    val attention = items.count { it.needsAttention }
    // درس مركون خرج من دور الرفع (peek يستبعده)، فلا يُحسب من «الحيّ»:
    // لا يجوز أن يُعرض «بانتظار الدور» لطابور كلّه مركون.
    val pendingCount = items.count { !it.parked }
    // لا عمل مجدول إطلاقاً وفي الطابور دروس حيّة = الرفع متوقّف فعلاً.
    val stalled = !paused && work.loaded && work.state == null && pendingCount > 0
    // بند 21: من `!online` وحدها — لا من لقطة ذاكرة كتبها عامل ميت وبقيت
    // ساعات بعد عودة الواي‑فاي تحوّل اللوم إلى الشبكة فيمتنع المشرف.
    val waiting = !paused && !online
    val waitMs = if (work.nextRunAtMs > 0L) work.nextRunAtMs - nowMs else 0L
    // المركون وحده أحمر: هو الذي لا يتحرّك أبداً بلا تدخّل. والمنقطع
    // والمتعثّر برتقاليّان — يُستأنفان وحدهما، وصبغهما بالخطر يجعل كلّ
    // انقطاع دقيقتين يبدو درساً ضائعاً.
    val parkedCount = items.count { it.parked }
    val alerting = parkedCount > 0 && !fresh && !paused
    val attentionTone = if (parkedCount > 0) {
        MaterialTheme.colorScheme.error
    } else {
        adminOrange
    }
    val accent = when {
        stalled || alerting -> MaterialTheme.colorScheme.error
        paused || waiting || interrupted != null || attention > 0 -> adminOrange
        else -> MaterialTheme.colorScheme.primary
    }
    val attentionText = if (attention > 1) {
        "${arabicCount(attention, "درس متعثّر", "درسان متعثّران", "دروس متعثّرة", "درساً متعثّراً")} " +
            "في الرفع — افتح للتفاصيل"
    } else {
        "درس متعثّر في الرفع — افتح للتفاصيل"
    }
    val status = when {
        paused -> "موقوف مؤقّتاً" +
            (current?.percent?.takeIf { it > 0 }?.let { " عند $it%" } ?: "") +
            " — يُستأنف من حيث توقّف"
        // نبضة حيّة: الادّعاء الوحيد المسموح بأنّ بايتات تُنقل الآن.
        live != null -> "جارٍ الرفع… ${live.percent}%"
        // العامل يعمل والنبضة متجمّدة = إعادة محاولة داخليّة لا نقل.
        current != null && work.state == WorkInfo.State.RUNNING ->
            "متعثّر عند ${current.percent}% — يعيد المحاولة"
        stalled -> "⚠️ متوقّف — اضغط لإعادة التشغيل"
        waiting -> "بانتظار الإنترنت — سيُستأنف تلقائياً من حيث توقّف"
        interrupted != null ->
            "انقطع أثناء الرفع عند ${interrupted.lastPercent}% — سيُستأنف"
        work.state == WorkInfo.State.BLOCKED -> "في الدور خلف مهمّة"
        waitMs > RETRY_SOON_MS -> "المحاولة بعد ${formatWait(waitMs)}"
        work.state == WorkInfo.State.RUNNING -> "جارٍ التجهيز…"
        attention > 0 -> attentionText
        else -> "بانتظار الدور"
    }
    // العنوان المعروض: ما يُرفع الآن، وإلّا أوّل درس **حيّ** — المركون خرج
    // من الدور فلا يصلح أن يمثّل «التالي».
    val headline = current?.title?.takeIf { it.isNotBlank() }
        ?: items.firstOrNull { !it.parked }?.title?.takeIf { it.isNotBlank() }
        ?: items.firstOrNull()?.title.orEmpty()
    // موقع ما يُرفع الآن في الدور — لا تُطبع اللافتة إلّا لرفع حيّ فعلاً.
    val activePosition = current?.id
        ?.let { id -> items.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?.plus(1)
        ?: 1
    // شريط محدَّد فقط حيث تكون النسبة معلومة وصادقة: نبضة حيّة، أو نسبة
    // مجمَّدة معروفة (موقوف/منقطع). وما عداها **غير محدَّد** لا صفر.
    val barPercent = when {
        live != null -> live.percent
        paused -> current?.percent ?: interrupted?.lastPercent
            ?: items.firstOrNull { !it.parked }?.lastPercent ?: 0
        interrupted != null -> interrupted.lastPercent
        else -> null
    }

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
                        stalled || alerting -> Icons.Filled.ErrorOutline
                        paused -> Icons.Filled.PauseCircle
                        waiting || interrupted != null -> Icons.Filled.CloudOff
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
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        status,
                        fontSize = 11.sp,
                        color = if (stalled || alerting) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                // ⏸/▶ في الشريط نفسه: أسرع تدخّل ممكن بلا فتح الورقة —
                // المشرف الذي انتبه أنّه على بيانات الجوّال يريد إيقافاً الآن.
                // وحين لا يكون هناك عمل مجدول أصلاً يصير الزرّ «إعادة تشغيل»:
                // زرّ «إيقاف» فوق طابور متوقّف عبثٌ، والمشرف يحتاج مخرجاً.
                IconButton(
                    onClick = {
                        when {
                            stalled -> LessonUploadWorker.kickNow(context)
                            paused -> {
                                UploadQueue.resume()
                                LessonUploadWorker.kickNow(context)
                            }
                            else -> UploadQueue.pause()
                        }
                    },
                    // بلا تصغير المساحة: 30dp كانت دون الحدّ الأدنى للمسّ،
                    // والخطأ هنا لا يمرّ بلا أثر بل يفتح ورقة الطابور كاملة.
                    // حجم الأيقونة وحده هو ما يضبط المظهر.
                ) {
                    Icon(
                        when {
                            stalled -> Icons.Filled.Refresh
                            paused -> Icons.Filled.PlayArrow
                            else -> Icons.Filled.Pause
                        },
                        contentDescription = when {
                            stalled -> "إعادة تشغيل الرفع"
                            paused -> "استئناف الرفع"
                            else -> "إيقاف الرفع مؤقّتاً"
                        },
                        tint = accent,
                        modifier = Modifier.size(19.dp),
                    )
                }
                if (items.size > 1) {
                    Box(
                        Modifier
                            .background(accent, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        // «س من ص» لا تُطبع إلّا ورفعٌ حيّ يجري: كانت تُطبع
                        // «1 من ن» بلا أيّ عامل يعمل فتؤكّد كذبة «يعمل الآن».
                        Text(
                            if (fresh) "$activePosition من ${items.size}" else "${items.size}",
                            // اللافتة فوق لون مصمت متغيّر — الحبر يُشتقّ منه.
                            color = contentColorOn(accent),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // ⛔ لا شريط محدَّد عند صفر بلا نبضة: كان يقول «بدأ ولم يتقدّم»
            // بينما لا عامل يعمل أصلاً. غير المحدَّد يقول «ننتظر» بلا ادّعاء.
            if (barPercent != null) {
                LinearProgressIndicator(
                    progress = { barPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.2f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.2f),
                )
            }
            // ما تعثّر لا يُخفيه انشغال الطابور بغيره: يبقى ظاهراً حتى يقرّر
            // المشرف. ⛔ الشرط أن يكون السطر الرئيسي مشغولاً بنصّ آخر — بلاه
            // إمّا تكرار للسطر نفسه، وإمّا اختفاء التنبيه كلّه.
            if (attention > 0 && status != attentionText) {
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(attentionTone.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = attentionTone,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        attentionText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = attentionTone,
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
                        .background(adminGreen.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = adminGreen,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Column(Modifier.weight(1f)) {
                        // إعلان الدفعة يذكر عددها لا اسم أوّل ملفّ وحده: من
                        // رفع مجلّداً كاملاً يريد أن يرى أنّ العشرين دخلت.
                        Text(
                            if (added.batchCount > 1) {
                                "أُضيف ${lessonsCountLabel(added.batchCount)} إلى الطابور"
                            } else {
                                "أُضيف إلى الطابور: ${added.fileName}"
                            },
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = adminGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (added.batchCount > 1) {
                                "تبدأ من الترتيب ${added.position} من ${added.total} — " +
                                    "تُرفع واحداً تلو الآخر بترتيب إضافتها."
                            } else {
                                "الترتيب ${added.position} من ${added.total} — " +
                                    "تابع إضافة درس آخر، الرفع يكمل وحده."
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * عمر النبضة الذي يجوز بعده قول «جارٍ الرفع».
 *
 * ⛔ أثناء إعادة المحاولة الداخليّة لـFirebase (نافذتها 30 دقيقة) لا تُطلق
 * نبضات تقدّم إطلاقاً، فكانت النسبة تتجمّد والنصّ يقول «جارٍ الرفع… 43%» بلا
 * بايت واحد يُنقل. الختم الزمنيّ وحده يفصل النقل عن التعثّر.
 */
private const val LIVE_PULSE_MS = 30_000L

/** فاصل إعادة التركيب — به وحده يتقادم نصّ «جارٍ الرفع» ذاتياً. */
private const val TICK_MS = 5_000L

/** دون هذا الفارق لا معنى لقول «المحاولة بعد…» — الموعد الآن عملياً. */
private const val RETRY_SOON_MS = 20_000L

/**
 * سطر حالة الدرس في الورقة — من الحالة **المحفوظة** وحدها.
 *
 * ⚠️ كان السطر لا يظهر إلّا بـ`lastError != null || parked`، وكانت الحالتان
 * الأكثر وقوعاً (انقطاع أثناء الرفع، وانتظار الشبكة) لا تكتبان شيئاً — فيُعرض
 * الدرس بلا أيّ إشارة إلى ما جرى له.
 */
private fun itemStatusLine(
    item: PendingUpload,
    active: Boolean,
    online: Boolean,
): String? = when {
    active -> null // الشريط الحيّ تحته يكفي
    item.parked -> "توقّف الرفع — أعد المحاولة أو ألغِه"
    item.state == UploadState.INTERRUPTED ->
        "انقطع أثناء الرفع عند ${item.lastPercent}% — سيُستأنف من حيث توقّف" +
            // سبب الإيقاف محفوظ ومترجَم («أوقفه النظام لتوفير البطارية»،
            // «انتهت مهلة الرفع في الخلفية») وكان بلا قارئ واحد، فيستحيل على
            // المشرف تمييز إسبات النظام من مهلة الخدمة الأماميّة.
            (item.stoppedReason?.let { " — $it" } ?: "")
    // ⚠️ ادّعاء «بانتظار الإنترنت» من الشبكة الحيّة لا من الحالة المحفوظة:
    // فشل النقل يُصنَّف شبكيّاً بلا اشتراط انقطاع، فكانت البطاقة تعلّق اللوم
    // على الشبكة فوق واي‑فاي سليم فيمتنع المشرف عن التدخّل.
    item.state == UploadState.WAITING && !online ->
        "بانتظار الإنترنت — يُستأنف من ${item.lastPercent}%"
    item.lastError != null -> "تعذّر الرفع — يعيد المحاولة تلقائياً"
    else -> null
}

/** مدّة انتظار مقروءة بالعربية من `nextScheduleTimeMillis`. */
private fun formatWait(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "$seconds ثانية"
        seconds < 3_600 -> "${seconds / 60} دقيقة"
        else -> "${seconds / 3_600} ساعة"
    }
}

/** تفاصيل الطابور: ترتيب الرفع، حالة كلّ درس، وإلغاء أو إعادة محاولة. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadQueueSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items by UploadQueue.items.collectAsState()
    val progress by UploadQueue.progress.collectAsState()
    val paused by UploadQueue.paused.collectAsState()
    val online by NetworkMonitor.online.collectAsState()
    val backgroundBlocked by UploadQueue.backgroundBlocked.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // الإلغاء يحذف النسخة المحليّة نهائياً — وهي النسخة الوحيدة للملفّ
    // المدموج (enqueueLocalFile تحذف المصدر) — فلا يقع بلا تأكيد صريح.
    var confirmCancel by remember { mutableStateOf<PendingUpload?>(null) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    // ⏱ نفس نبضة الشريط: بها يتقادم «يُرفع الآن» ذاتياً بلا حدث خارجيّ.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }
    val current = progress
    val liveId = current?.id?.takeIf { nowMs - current.atMs < LIVE_PULSE_MS }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "طابور الرفع (${items.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "تُرفع بالترتيب المعروض، وأوّل درس أُضيف هو أوّل ما يظهر في " +
                    "التطبيق العام. أضف درساً جديداً ولو كان السابق قيد الرفع، " +
                    "وأغلق الشاشة أو التطبيق إن شئت — الرفع يكمل في الخلفية " +
                    "ويصلك إشعار عند اكتمال كلّ درس، وإشعار آخر إن تعذّر رفعه.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            // ⏸ التحكّم بالطابور كلّه: إيقاف مؤقّت يُبقي كلّ شيء ويستأنف من
            // البايت نفسه، وإلغاء الكل يحذف — ولذلك يمرّ بتأكيد صريح.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (paused) {
                            UploadQueue.resume()
                            // تدخّل بشريّ صريح ⇒ `kickNow`: كان `kick`
                            // (APPEND_OR_REPLACE) يُلحق العملَ **BLOCKED** خلف
                            // عقدة نائمة في backoff فلا يقع شيء، والواجهة تؤكّد.
                            LessonUploadWorker.kickNow(context)
                        } else {
                            UploadQueue.pause()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (paused) "استئناف الرفع" else "إيقاف مؤقّت")
                }
                OutlinedButton(
                    onClick = { confirmCancelAll = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("إلغاء الكل", color = MaterialTheme.colorScheme.error)
                }
            }
            if (paused) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "الرفع موقوف: لا شيء يُرفع حتى تضغط «استئناف». الملفّات " +
                        "وجلسات الرفع محفوظة، والاستئناف يُكمل من حيث توقّف " +
                        "لا من الصفر.",
                    fontSize = 11.5.sp,
                    color = adminOrange,
                    lineHeight = 17.sp,
                )
            }
            // ⚠️ الرفع بلا خدمة أماميّة يخضع لحدّ النظام (عشر دقائق) فلا يكتمل
            // ملفّ كبير أبداً. كان الفشل يُبتلع صامتاً، فيبدو العطل بلا سبب.
            if (backgroundBlocked) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "الرفع يعمل بلا حماية الخلفية: منع النظامُ إشعارَ الرفع، " +
                        "فقد يوقفه بعد دقائق. أبقِ الشاشة مفتوحة حتى ينتهي، " +
                        "أو استثنِ التطبيق من تقييد البطارية.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("فتح إعدادات البطارية")
                }
            }
            // «إعادة محاولة الكل»: بعد انقطاع طويل قد يُركن عدّة دروس معاً،
            // فإعادتها واحداً واحداً عبء بلا سبب — تُرفع عنها جميعاً حالة
            // الركن ثمّ يوقَظ العامل مرّة واحدة فيمضي بها بالدور.
            val parkedItems = items.filter { it.parked }
            if (parkedItems.size > 1) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        // معاملة واحدة على IO ثمّ الإيقاظ بعد الحفظ: النداء
                        // لكلّ عنصر كان يعيد كتابة المدوّنة كاملةً في كلّ مرّة
                        // على خيط الواجهة.
                        UploadQueue.unparkAll(parkedItems.map { it.id }) {
                            LessonUploadWorker.kickNow(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("إعادة محاولة الكل (${parkedItems.size})")
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // سقف لا ارتفاع ثابت: العمود الحاوي يمرّر ما تبقّى من الارتفاع،
                // فتنكمش القائمة في الوضع الأفقي وبخطّ كبير بدل أن يُقصّ أسفلها،
                // ولا تحجز فراغاً حين يقلّ العدد.
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    // «يُرفع الآن» ادّعاء لا يجوز إلّا بنبضة حيّة: لقطة التقدّم
                    // وحدها كانت تُبقي شريط عنصر يتحرّك بعد موت العامل.
                    val active = item.id == liveId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val line = itemStatusLine(item, active, online)
                            if (line != null) {
                                Spacer(Modifier.height(3.dp))
                                // السبب محفوظ في العنصر وكان يُخفى خلف نصّ
                                // ثابت. يُعرض الآن مترجماً: العربيّ كما هو،
                                // ورسالة Firebase الخام تسقط إلى صياغة
                                // عربية مفهومة بدل إنجليزية تُقلق بلا فائدة.
                                val reason = item.lastError
                                    ?.takeIf { it.isNotBlank() && it != line }
                                    ?.let { RuntimeException(it).arabicReason() }
                                // ⚠️ لون الخطر للمركون وحده: المنقطع والمنتظِر
                                // يمضيان وحدهما، وصبغهما بالأحمر كان يجعل كلّ
                                // انقطاع دقيقتين يبدو درساً ضائعاً.
                                val tone = if (item.parked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    adminOrange
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (item.parked) {
                                            Icons.Filled.ErrorOutline
                                        } else {
                                            Icons.Filled.CloudOff
                                        },
                                        contentDescription = null,
                                        tint = tone,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        buildString {
                                            append(line)
                                            if (reason != null) {
                                                append("\nالسبب: ")
                                                append(reason)
                                            }
                                        },
                                        fontSize = 10.5.sp,
                                        lineHeight = 15.sp,
                                        color = tone,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else if (active) {
                                Spacer(Modifier.height(5.dp))
                                LinearProgressIndicator(
                                    progress = { (progress?.percent ?: 0) / 100f },
                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                )
                            }
                        }
                        // ⛔ الزرّ لكلّ عنصر لا يُرفع الآن، بلا شرط: كان مشروطاً
                        // بـ`lastError != null || parked`، وفرع الشبكة كان يمحو
                        // `lastError` — فيبقى الدرس العالق بلا سطر خطأ وبلا زرّ
                        // إنقاذ، ومخرجه الوحيد حذفُه وفقدُ نسخته الوحيدة.
                        if (!active) {
                            IconButton(
                                onClick = {
                                    // رفع الركن أولاً وإلّا بقي خارج الدور.
                                    // و`unpark` لم تعد تهدم جلسة صالحة، فالضغط
                                    // لا يُرجع الرفع إلى 0%. والإيقاظ بعد
                                    // الحفظ: عاملٌ يسبقه لا يرى الركن مرفوعاً.
                                    UploadQueue.unpark(item.id) {
                                        LessonUploadWorker.kickNow(context)
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "إعادة المحاولة الآن",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        IconButton(onClick = { confirmCancel = item }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "إلغاء",
                                tint = MaterialTheme.colorScheme.error,
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
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                UploadQueue.cancel(target.id)
                confirmCancel = null
            },
            onDismiss = { confirmCancel = null },
        )
    }

    if (confirmCancelAll) {
        ConfirmDialog(
            title = "إلغاء كلّ ما في الطابور؟",
            body = "ستُحذف ${arabicCount(items.size, "عنصر واحد", "عنصران", "عناصر", "عنصراً")} من طابور الرفع، وتُحذف معها " +
                "نسخها المحليّة نهائياً بلا تراجع. لن يُنشر أيّ منها في " +
                "التطبيق العام. إن كنت تريد التوقّف مؤقّتاً فقط فاستعمل " +
                "«إيقاف مؤقّت» — فهو يُبقي كلّ شيء.",
            confirmLabel = "إلغاء الكل وحذف الملفّات",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                UploadQueue.cancelAll()
                confirmCancelAll = false
                onDismiss()
            },
            onDismiss = { confirmCancelAll = false },
        )
    }
}
