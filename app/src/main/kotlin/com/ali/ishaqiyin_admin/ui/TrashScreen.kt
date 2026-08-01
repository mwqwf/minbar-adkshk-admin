package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.TrashRepository
import com.ali.ishaqiyin_admin.data.TrashedLesson
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🗑️ «سلة المحذوفات» — كل درس حُذف (منفرداً أو بحذف قسم كامل) يبقى هنا
 * 30 يوماً بملفه الصوتي ونصّه المشروح: استمع للتأكد، ثم استعد بنقرة أو
 * احذف نهائياً. ما تجاوز مهلته يُنظَّف تلقائياً كل ليلة.
 */
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<TrashedLesson?>(null) }
    var purging by remember { mutableStateOf<TrashedLesson?>(null) }

    val items by remember { TrashRepository.watchAll() }
        .collectAsState(initial = emptyList())

    fun run(doneMsg: String, action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
                if (doneMsg.isNotEmpty()) snack(doneMsg)
            } catch (_: Exception) {
                snack("تعذّر تنفيذ العملية. حاول مجدداً.")
            }
            busy = false
        }
    }

    restoring?.let { item ->
        ConfirmDialog(
            title = "استعادة الدرس؟",
            body = "«${item.title.ifEmpty { "بدون عنوان" }}»\n" +
                "يعود الدرس إلى مكانه في التطبيق فوراً" +
                if (item.hasTranscript) " مع نصّه المشروح." else ".",
            confirmLabel = "استعادة",
            onDismiss = { restoring = null },
            onConfirm = {
                restoring = null
                run("استُعيد الدرس. ✅") { TrashRepository.restore(item) }
            },
        )
    }

    purging?.let { item ->
        ConfirmDialog(
            title = "حذف نهائي؟",
            body = "«${item.title.ifEmpty { "بدون عنوان" }}»\n" +
                "⚠️ يُحذف الدرس وملفه الصوتي نهائياً ولا يمكن استعادته بعدها أبداً.",
            confirmLabel = "حذف نهائياً",
            onDismiss = { purging = null },
            onConfirm = {
                purging = null
                run("حُذف نهائياً.") { TrashRepository.purge(item) }
            },
        )
    }

    AdminScaffold(title = "سلة المحذوفات", onBack = onBack) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "السلة فارغة.\nكل درس تحذفه يبقى هنا 30 يوماً قابلاً " +
                        "للاستعادة قبل حذفه النهائي تلقائياً.",
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                    color = kMuted,
                )
            }
            return@AdminScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "الدروس المحذوفة تبقى 30 يوماً ثم تُحذف نهائياً تلقائياً.",
                    fontSize = 12.sp,
                    color = kMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            items(items.size) { index ->
                val item = items[index]
                val isPlaying = player.playingId == item.id && player.playing
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(kBoxBg, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(
                                    onClick = { player.toggle(item.id, item.audioUrl) },
                                    enabled = item.audioUrl.isNotEmpty(),
                                ) {
                                    Icon(
                                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = "استماع",
                                        tint = kTeal,
                                    )
                                }
                            }
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title.ifEmpty { "بدون عنوان" },
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val section = listOf(item.categoryName, item.subcategoryName)
                                    .filter(String::isNotBlank)
                                    .joinToString(" ← ")
                                if (section.isNotEmpty()) {
                                    Text(section, fontSize = 12.sp, color = kMuted)
                                }
                            }
                            Box(
                                Modifier
                                    .background(
                                        kOrange.copy(alpha = 0.12f),
                                        RoundedCornerShape(999.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "${item.daysLeft} يوماً",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = kOrange,
                                )
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            buildString {
                                append("حذفه ${item.deletedBy.ifEmpty { "غير معروف" }}")
                                if (item.deletedAtMs > 0) {
                                    append(
                                        " • " + SimpleDateFormat(
                                            "yyyy/MM/dd HH:mm",
                                            Locale.ENGLISH,
                                        ).format(Date(item.deletedAtMs)),
                                    )
                                }
                                if (item.hasTranscript) append(" • معه نص مشروح 📖")
                            },
                            fontSize = 12.sp,
                            color = kMuted,
                        )
                        Spacer(Modifier.size(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { restoring = item },
                                enabled = !busy,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = kGreen),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Filled.RestoreFromTrash,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("استعادة")
                            }
                            OutlinedButton(
                                onClick = { purging = item },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Filled.DeleteForever,
                                    contentDescription = null,
                                    tint = kDanger,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("حذف نهائي", color = kDanger)
                            }
                        }
                    }
                }
            }
        }
    }
}
