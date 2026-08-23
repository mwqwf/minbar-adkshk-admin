package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminAlert
import com.ali.ishaqiyin_admin.data.AdminAlertsFeed
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * شاشة «تنبيهاتك».
 *
 * كلّ صفّ **قابل للنقر** ويفتح هدفه الفعلي (مساهمة، نصّ مشروح، مراجعة
 * المالك…) بنفس خريطة توجيه الإشعارات في `NotificationRoute` — التنبيه إشارة
 * إلى عمل، لا نصّاً يُقرأ ويُنسى.
 *
 * ولأنّ التنبيه المقروء يختفي عن صاحبه، لا نُميّز شيئاً مقروءاً بمجرّد فتح
 * الشاشة: يُميَّز الصفّ عند **ظهوره الفعلي** داخل القائمة (وبعد لحظة كي لا
 * يُحتسب تمريرٌ خاطف)، أو دفعةً واحدة بزرّ صريح في الشريط. والتمييز البصري
 * لغير المقروء يبقى طوال الزيارة فلا يختفي شيء من تحت عين المشرف.
 */
@Composable
fun AlertsScreen(
    isOwner: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var confirmPublicDelete by remember { mutableStateOf<AdminAlert?>(null) }
    val live by remember(isOwner) { AdminAlertsFeed.stream(isOwner) }
        .collectAsState(initial = emptyList())
    val me = AdminAlertsFeed.myEmail

    // ما مُيّز مقروءاً في هذه الزيارة — يمنع إعادة الكتابة كلّما عاد الصفّ
    // إلى الظهور بالتمرير (النسخة المعروضة تبقى «غير مقروءة» عمداً).
    val marked = remember { mutableSetOf<String>() }
    var markedAll by remember { mutableStateOf(false) }

    // لقطة الزيارة: كلّ ما ظهر منذ فتح الشاشة يبقى معروضاً حتى الخروج —
    // ولا تُحدَّث نسخته بعد التمييز، فتظلّ إشارة «غير مقروء» ظاهرة للزائر.
    val session = remember { androidx.compose.runtime.mutableStateMapOf<String, AdminAlert>() }
    LaunchedEffect(live) {
        live.forEach { alert ->
            if (!session.containsKey(alert.id)) {
                session[alert.id] = alert
                // ⛔ markedAll لم يكن يُصفَّر عند وصول جديد، فيختفي زرّ «تمييز
                // الكل مقروءاً» إلى الأبد بعد أوّل ضغطة رغم تراكم غير المقروء.
                if (!alert.isReadBy(me)) markedAll = false
            }
        }
    }
    val alerts = session.values.sortedByDescending { it.createdAtMs }

    /** الحذف يمحو الوثيقة نفسها لا علامة قراءتي — لذا يُعلَن فشله. */
    fun deleteNow(alert: AdminAlert) {
        session.remove(alert.id)
        scope.launch {
            runCatching { AdminAlertsFeed.delete(alert) }
                // ⛔ كان الصفّ يُزال محليّاً بلا إعادة عند الفشل، فيختفي عن
                // الشاشة وهو باقٍ في القاعدة ⇒ تنبيه «مُعالَج» لم يُحذف.
                .onFailure {
                    session[alert.id] = alert
                    snack("تعذّر حذف التنبيه: ${it.arabicReason()}")
                }
        }
    }

    /**
     * فتح هدف التنبيه: يُبنى المسار من نوعه ومعرّفه بنفس خريطة الإشعارات،
     * ويُميَّز مقروءاً لأنّ المشرف عالجه فعلاً. تنبيه بلا شاشة (تهنئة أو
     * موجز) لا يُسقط شيئاً — يُعلَن أنّه للعلم فقط.
     */
    fun openTarget(alert: AdminAlert) {
        val route = NotificationRoute.routeFor { key ->
            when (key) {
                NotificationRoute.KEY_TYPE -> alert.type
                NotificationRoute.KEY_REF,
                NotificationRoute.KEY_SUBMISSION,
                NotificationRoute.KEY_LESSON,
                NotificationRoute.KEY_REVIEW,
                NotificationRoute.KEY_CANDIDATE,
                -> alert.refId

                else -> null
            }
        }
        marked.add(alert.id)
        AdminAlertsFeed.markRead(alert)
        if (route == null) {
            snack("هذا التنبيه للعلم فقط — لا شاشة مرتبطة به.")
            return
        }
        onOpen(route)
    }

    // تنبيه المالك العامّ (email فارغ) يعود لكلّ المشرفين، وحذفه يمحوه عنهم
    // جميعاً — لا عن صاحب الضغطة وحده، فيلزم تأكيد صريح.
    confirmPublicDelete?.let { alert ->
        ConfirmDialog(
            title = "حذف تنبيه عامّ",
            body = "هذا تنبيه عامّ — سيختفي عن جميع المشرفين لا عنك وحدك، " +
                "ولا يمكن التراجع.",
            confirmLabel = "حذف للجميع",
            confirmColor = MaterialTheme.colorScheme.error,
            onDismiss = { confirmPublicDelete = null },
            onConfirm = {
                confirmPublicDelete = null
                deleteNow(alert)
            },
        )
    }

    AdminScaffold(
        title = "تنبيهاتك",
        onBack = onBack,
        actions = {
            if (!markedAll && alerts.any { !it.isReadBy(me) }) {
                IconButton(
                    onClick = {
                        val unread = alerts.filter { !it.isReadBy(me) }
                        AdminAlertsFeed.markAllRead(unread)
                        unread.forEach { marked.add(it.id) }
                        markedAll = true
                        snack("مُيّزت التنبيهات مقروءة.")
                    },
                ) {
                    Icon(Icons.Filled.DoneAll, contentDescription = "تمييز الكل مقروءاً")
                }
            }
        },
    ) { padding ->
        if (alerts.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "لا تنبيهات — كلّ شيء تحت السيطرة ✅",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@AdminScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(alerts.size) { index ->
                val alert = alerts[index]
                val unread = !alert.isReadBy(me)
                val deletable = AdminAlertsFeed.canDelete(alert, isOwner)

                // 👁️ الظهور الفعلي = القراءة: لا يُميَّز إلّا ما رُكّب في
                // القائمة وبقي لحظة أمام العين — لا كلّ ما جلبه الاستعلام.
                if (unread && !marked.contains(alert.id)) {
                    LaunchedEffect(alert.id) {
                        delay(900)
                        marked.add(alert.id)
                        AdminAlertsFeed.markRead(alert)
                    }
                }

                // الدائرة الباهتة (المقروء) تُظهر خلفيّة الشاشة من تحتها،
                // فأيقونتها تتبع السطح لا اللون المصمت — وإلّا اختفى الحبر
                // الداكن فوق ذهب الليل الشفّاف.
                val bubble = MaterialTheme.colorScheme.primary
                val bubbleIcon = when {
                    unread -> contentColorOn(bubble)
                    isAdminDarkTheme() -> MaterialTheme.colorScheme.onSurface
                    else -> Color.White
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openTarget(alert) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(
                                if (unread) bubble else bubble.copy(alpha = 0.35f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            iconFor(alert.type),
                            contentDescription = null,
                            tint = bubbleIcon,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            alert.title.ifEmpty { "تنبيه" },
                            fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.Medium,
                        )
                        if (alert.body.isNotEmpty()) {
                            Text(alert.body, lineHeight = 20.sp, fontSize = 13.sp)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            ago(alert.createdAtMs),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (unread) {
                            Spacer(Modifier.size(4.dp))
                            Box(Modifier.size(9.dp).background(adminOrange, CircleShape))
                        }
                    }
                    if (deletable) {
                        IconButton(
                            onClick = {
                                if (alert.email.isEmpty()) {
                                    confirmPublicDelete = alert
                                } else {
                                    deleteNow(alert)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "حذف التنبيه",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    "submission" -> Icons.Filled.HowToVote
    "transcript" -> Icons.AutoMirrored.Filled.MenuBook
    "milestone" -> Icons.Filled.EmojiEvents
    "owner_code" -> Icons.Filled.VerifiedUser
    "digest", "weekly_digest" -> Icons.Filled.Insights
    "engagement" -> Icons.AutoMirrored.Filled.TrendingUp
    "suspicious_lesson", "suspicious_scan" -> Icons.Filled.GppMaybe
    "featured_expiring" -> Icons.Filled.Star
    // 🔇 تنبيه الفحص الأسبوعيّ لروابط الصوت الميتة — أيقونته صوت مقطوع
    // ليميّزه المشرف عن بقيّة التنبيهات من نظرة واحدة.
    "dead_audio_scan" -> Icons.Filled.VolumeOff
    else -> Icons.Filled.Campaign
}

private fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val diff = System.currentTimeMillis() - ms
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "الآن"
        minutes < 60 -> "قبل $minutes د"
        hours < 24 -> "قبل $hours س"
        else -> "قبل $days ي"
    }
}
