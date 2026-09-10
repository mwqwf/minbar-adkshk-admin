package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.MushafakSupport
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 📬 **صندوقُ «رسائل مصحفك» داخل لوحة منبر** — للمالك وحده (أمر المالك 2026-09-10:
 * «اصنع لي مكاناً لاستقبال هذه الرسائل لا يظهر إلا لي أنا كمالك التطبيق، أمّا بقيّةُ مشرفي منبر
 * فلا أريد أن يظهر لهم»).
 *
 * **ولماذا في لوحةِ منبر وتطبيقُ مصحفك تطبيقٌ آخر؟** لأنّ المالك يفتح هذه اللوحةَ كلَّ يومٍ لعمله،
 * ولا يفتح مصحفك بصفة مطوّر — فتأتي الرسائلُ إليه حيث هو. والحسابُ واحدٌ والجهازُ واحد،
 * والفصلُ بين المنتجين لا يعني تفريقَ صاحبهما على صندوقين.
 *
 * ⛔ **حارسان لا واحد**: الشاشةُ لا تُفتح إلا للمالك (‏`AdminApp`)، **والخادمُ لا يجيب إلا بمفتاح**
 * — فلو بلغها مشرفٌ بحيلةٍ لم يجد في جهازه مفتاحاً فلا يرى حرفاً واحداً.
 */
@Composable
fun MushafakInboxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(MushafakSupport.key(ctx) != null) }
    var tickets by remember { mutableStateOf<List<MushafakSupport.Ticket>?>(null) }
    var filter by remember { mutableIntStateOf(0) } // 0 مفتوحة · 1 الكلّ
    var notice by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<String?>(null) }
    var replyText by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(hasKey, reload) {
        if (hasKey) tickets = MushafakSupport.tickets(ctx)
    }

    AdminScaffold(title = "رسائل مصحفك", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!hasKey) {
                // 🔑 المفتاحُ يُدخَل مرّةً في جهاز المالك — ⛔ ولا يُكتب في الشيفرة ولا في المستودع.
                Text(
                    "أدخل مفتاحَ المالك مرّةً واحدة (‏سرُّ الخادم `OWNER_KEY`). يُحفظ في هذا الجهاز وحدَه، " +
                        "ولا يُرسل إلى أحدٍ غير خادم مصحفك.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("مفتاح المالك") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            // ⛔ يُتحقّق قبل الحفظ: مفتاحٌ خاطئٌ محفوظٌ يعني صندوقاً فارغاً بلا سببٍ ظاهر.
                            if (MushafakSupport.verify(keyInput.trim())) {
                                MushafakSupport.setKey(ctx, keyInput.trim())
                                hasKey = true
                                notice = null
                            } else {
                                notice = "المفتاح مرفوض أو الشبكة متعذّرة."
                            }
                        }
                    },
                    enabled = keyInput.trim().length >= 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("تحقّق واحفظ") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(filter == 0, { filter = 0 }, { Text("المفتوحة") })
                    FilterChip(filter == 1, { filter = 1 }, { Text("الكلّ") })
                    TextButton(onClick = { reload++ }) { Text("تحديث") }
                    TextButton(onClick = { MushafakSupport.setKey(ctx, null); hasKey = false; tickets = null }) {
                        Text("امحُ المفتاح")
                    }
                }
                val list = tickets
                when {
                    list == null -> Text(
                        "تعذّر الجلب — تحقّق من الشبكة أو من المفتاح.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        val shown = if (filter == 0) list.filter { it.status == "open" } else list
                        if (shown.isEmpty()) {
                            Text("لا رسائل.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shown, key = { it.id }) { t ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(t.kindLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                            Text(stamp(t.updatedAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(t.body, fontSize = 14.sp)
                                        // ⚠️ السياقُ التقنيّ يُعرض مطويّاً في سطرٍ صغير: هو للمطوّر لا للقارئ،
                                        //    وعرضُه كاملاً يدفع نصَّ المستخدم — وهو المقصود — خارج النظر.
                                        if (t.context.isNotBlank()) {
                                            Text(t.context.take(140), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (replyTo == t.id) {
                                            OutlinedTextField(
                                                value = replyText,
                                                onValueChange = { replyText = it.take(4000) },
                                                label = { Text("ردُّك — يظهر له داخل تطبيقه") },
                                                modifier = Modifier.fillMaxWidth(),
                                                maxLines = 4,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = {
                                                    val body = replyText.trim()
                                                    if (body.isEmpty()) return@Button
                                                    scope.launch {
                                                        val ok = MushafakSupport.reply(ctx, t.id, body)
                                                        notice = if (ok) "أُرسل الردّ." else "تعذّر الإرسال."
                                                        if (ok) { replyTo = null; replyText = ""; reload++ }
                                                    }
                                                }) { Text("أرسل") }
                                                TextButton(onClick = { replyTo = null; replyText = "" }) { Text("إلغاء") }
                                            }
                                        } else {
                                            TextButton(onClick = { replyTo = t.id; replyText = "" }) { Text("ردّ") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            notice?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun stamp(epochSeconds: Long): String =
    if (epochSeconds <= 0) "" else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
