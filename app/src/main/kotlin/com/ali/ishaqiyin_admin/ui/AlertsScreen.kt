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
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AdminAlert
import com.ali.ishaqiyin_admin.data.AdminAlertsFeed
import kotlinx.coroutines.launch

/**
 * شاشة «تنبيهاتك» الكاملة: قائمة حيّة، غير المقروء بارز، تمييز بالقراءة
 * عند النقر، وحذف (حسب الصلاحيّة).
 */
@Composable
fun AlertsScreen(isOwner: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val alerts by remember(isOwner) { AdminAlertsFeed.stream(isOwner) }
        .collectAsState(initial = emptyList())
    val me = AdminAlertsFeed.myEmail
    val hasUnread = alerts.any { !it.isReadBy(me) }

    AdminScaffold(
        title = "تنبيهاتك",
        onBack = onBack,
        actions = {
            if (hasUnread) {
                IconButton(onClick = { scope.launch { AdminAlertsFeed.markAllRead(alerts) } }) {
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
                Text("لا تنبيهات — كلّ شيء تحت السيطرة ✅", fontSize = 16.sp, color = kMuted)
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { AdminAlertsFeed.markRead(alert) } }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(
                                if (unread) kTeal else kTeal.copy(alpha = 0.35f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            iconFor(alert.type),
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White,
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
                        Text(ago(alert.createdAtMs), fontSize = 11.sp, color = kMuted)
                        if (unread) {
                            Spacer(Modifier.size(4.dp))
                            Box(Modifier.size(9.dp).background(kOrange, CircleShape))
                        }
                    }
                    if (deletable) {
                        IconButton(onClick = { scope.launch { AdminAlertsFeed.delete(alert) } }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "حذف التنبيه",
                                tint = kDanger,
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
    "milestone" -> Icons.Filled.EmojiEvents
    "owner_code" -> Icons.Filled.VerifiedUser
    "digest", "weekly_digest" -> Icons.Filled.Insights
    "engagement" -> Icons.Filled.TrendingUp
    "suspicious_lesson" -> Icons.Filled.GppMaybe
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
