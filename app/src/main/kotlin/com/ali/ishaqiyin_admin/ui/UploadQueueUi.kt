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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.ali.ishaqiyin_admin.data.UploadQueue
import com.ali.ishaqiyin_admin.data.formatBytes

/**
 * 📤 شريط طابور الرفع — يظهر وحده متى كان هناك درس قيد الرفع أو بانتظار
 * الشبكة، ويختفي وحده عند الفراغ. النقر يفتح تفاصيل الطابور.
 */
@Composable
fun UploadQueueBanner(modifier: Modifier = Modifier) {
    val items by UploadQueue.items.collectAsState()
    val progress by UploadQueue.progress.collectAsState()
    val online by NetworkMonitor.online.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    if (items.isEmpty()) return

    val waiting = !online || progress?.waitingForNetwork == true
    val current = progress
    val accent = if (waiting) kOrange else kTeal

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
                    if (waiting) Icons.Filled.CloudOff else Icons.Filled.CloudUpload,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.title?.takeIf { it.isNotBlank() }
                            ?: items.firstOrNull()?.title.orEmpty(),
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
                            else -> "بانتظار الدور"
                        },
                        fontSize = 11.sp,
                        color = kMuted,
                    )
                }
                if (items.size > 1) {
                    Box(
                        Modifier
                            .background(accent, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "${items.size}",
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
        }
    }

    if (showSheet) UploadQueueSheet(onDismiss = { showSheet = false })
}

/** تفاصيل الطابور: ترتيب الرفع، حالة كلّ درس، وإلغاء أو إعادة محاولة. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadQueueSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items by UploadQueue.items.collectAsState()
    val progress by UploadQueue.progress.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                    "التطبيق العام. يمكنك إغلاق التطبيق — يكمل الرفع وحده.",
                fontSize = 11.5.sp,
                color = kMuted,
                lineHeight = 18.sp,
            )
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
                            if (item.lastError != null) {
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = kDanger,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        "تعذّر الرفع — أعد المحاولة",
                                        fontSize = 10.5.sp,
                                        color = kDanger,
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
                        if (item.lastError != null) {
                            IconButton(onClick = { LessonUploadWorker.kick(context) }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "إعادة المحاولة",
                                    tint = kTeal,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        IconButton(onClick = { UploadQueue.cancel(item.id) }) {
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
}
