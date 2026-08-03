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
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.OwnerReviewRepository
import com.ali.ishaqiyin_admin.data.SuspiciousLessonReview
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch

/**
 * شاشة لا تُضاف لمسار المشرف إطلاقاً، كما تتحقق ثانيةً من بريد المالك.
 * الحماية الحقيقية تبقى في قواعد Firestore وcallables على الخادم.
 */
@Composable
fun OwnerReviewScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val player = rememberPreviewPlayer()

    var scanning by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<SuspiciousLessonReview, String>?>(null) }
    // الاعتماد الجماعي: تأكيد ثم تقدّم حيّ ثم رسالة حصيلة.
    var confirmBulk by remember { mutableStateOf(false) }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkDone by remember { mutableStateOf(0) }
    var bulkTotal by remember { mutableStateOf(0) }

    val isOwner = AuthService.isOwnerEmail(AuthService.currentUser?.email)
    if (!isOwner) {
        AdminScaffold(title = "مراجعة الدروس المشبوهة", onBack = onBack) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("هذه الصفحة خاصة بمالك التطبيق فقط.")
            }
        }
        return
    }

    val items by remember { OwnerReviewRepository.watchPending() }
        .collectAsState(initial = emptyList())

    pending?.let { (review, action) ->
        val deleting = action == "delete"
        ConfirmDialog(
            title = if (deleting) "حذف الدرس نهائياً؟" else "تأكيد سلامة الدرس؟",
            body = if (deleting) {
                "سيُحذف «${review.title}» وملفه الصوتي نهائياً. لا يمكن التراجع عن ذلك."
            } else {
                "سيُعلَّم «${review.title}» بأنه راجعه المالك وتم التحقق منه."
            },
            confirmLabel = if (deleting) "حذف نهائي" else "تم التحقق",
            confirmColor = if (deleting) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                busyId = review.id
                scope.launch {
                    try {
                        OwnerReviewRepository.resolve(review, action)
                        if (player.playingId == review.id) player.stop()
                        snack(if (deleting) "حُذف الدرس نهائياً." else "وُضعت علامة تم التحقق.")
                    } catch (e: Exception) {
                        snack("تعذّر تنفيذ القرار: ${e.arabicReason()}")
                    }
                    busyId = ""
                }
            },
        )
    }

    if (confirmBulk) {
        val count = items.size
        ConfirmDialog(
            title = "اعتماد كل المعروض؟",
            body = "سيُعلَّم $count درساً بأنك راجعتَها وتحقّقتَ منها دفعة واحدة، "
                + "وتختفي من هذه الشاشة ومن تنبيهاتك. لا يُحذف أيّ درس.",
            confirmLabel = "اعتماد الكل",
            confirmColor = MaterialTheme.colorScheme.primary,
            onDismiss = { confirmBulk = false },
            onConfirm = {
                confirmBulk = false
                val ids = items.map { it.id }
                bulkBusy = true
                bulkDone = 0
                bulkTotal = ids.size
                scope.launch {
                    try {
                        val result = OwnerReviewRepository.bulkVerify(ids) { done, total ->
                            bulkDone = done
                            bulkTotal = total
                        }
                        player.stop()
                        snack(
                            if (result.missingLessons > 0) {
                                "اعتُمد ${result.verified} درساً " +
                                    "(${result.missingLessons} منها لدرس محذوف)."
                            } else {
                                "اعتُمد ${result.verified} درساً دفعة واحدة."
                            },
                        )
                    } catch (e: Exception) {
                        snack("تعذّر الاعتماد الجماعي: ${e.arabicReason()}")
                    }
                    bulkBusy = false
                }
            },
        )
    }

    AdminScaffold(
        title = "مراجعة الدروس المشبوهة",
        onBack = onBack,
        actions = {
            TextButton(
                onClick = {
                    scanning = true
                    scope.launch {
                        try {
                            val count = OwnerReviewRepository.scanAll()
                            snack(
                                if (count > 0) {
                                    "اكتمل الفحص: أضيف $count درساً للمراجعة."
                                } else {
                                    "اكتمل الفحص ولم تُكتشف نتائج جديدة."
                                },
                            )
                        } catch (e: Exception) {
                            snack("تعذّر إكمال الفحص: ${e.arabicReason()}")
                        }
                        scanning = false
                    }
                },
                enabled = !scanning && !bulkBusy,
            ) {
                if (scanning) {
                    // زرّ في الشريط العلوي: يرث حبر الشريط المتكيّف بدل أبيض ثابت.
                    Spin(color = LocalContentColor.current, size = 18)
                } else {
                    Icon(Icons.Filled.Security, contentDescription = null)
                }
                Spacer(Modifier.size(4.dp))
                Text("فحص شامل")
            }
        },
    ) { padding ->
        // أثناء الاعتماد الجماعي تفرغ القائمة تدريجياً من البثّ الحيّ؛ نُبقي
        // الشاشة على وضع القائمة كي لا يختفي شريط التقدّم قبل انتهاء العملية.
        if (items.isEmpty() && !bulkBusy) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = adminGreen,
                        modifier = Modifier.size(58.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "لا توجد دروس مشبوهة معلّقة الآن.",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("يمكنك تشغيل فحص شامل من الزر أعلى الشاشة.", textAlign = TextAlign.Center)
                }
            }
            return@AdminScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${items.size} درساً بانتظار قرارك",
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.size(2.dp))
                                Text(
                                    "راجعتَها ولا شبهة فيها؟ اعتمدها كلّها دفعة واحدة.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            Button(
                                onClick = { confirmBulk = true },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                enabled = !bulkBusy && busyId.isEmpty() && items.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Filled.DoneAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("اعتماد الكل")
                            }
                        }
                        if (bulkBusy) {
                            Spacer(Modifier.size(10.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "جارٍ الاعتماد… $bulkDone من $bulkTotal",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(items.size) { index ->
                val review = items[index]
                val busy = busyId == review.id
                val playing = player.playingId == review.id && player.playing
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconButton(
                                    onClick = { player.toggle(review.id, review.audioUrl) },
                                    enabled = review.audioUrl.isNotEmpty(),
                                ) {
                                    Icon(
                                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (playing) "إيقاف مؤقت" else "معاينة",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                review.title.ifEmpty { "(درس بلا عنوان)" },
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                        RoundedCornerShape(99.dp),
                                    )
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "خطورة ${review.riskScore}",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        // شريط التحكّم للمقطع الجاري: تقدّم وقفز ±٣٠ث وسرعة —
                        // الحكم على درس طويل لا يحتمل سماعه كاملاً ولا الحكم بلا سماع.
                        PreviewPlayerBar(
                            state = player,
                            id = review.id,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (review.reasons.isNotEmpty()) {
                            Spacer(Modifier.size(10.dp))
                            Text("أسباب الاشتباه:", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.size(4.dp))
                            review.reasons.forEach {
                                Text("• $it", modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "معرّف الدرس: ${review.lessonId}" +
                                if (review.addedBy.isEmpty()) {
                                    ""
                                } else {
                                    "\nأضيف بواسطة: ${review.addedBy}"
                                },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { pending = review to "verified" },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    enabled = !bulkBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.Verified,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("تم التحقق")
                                }
                                OutlinedButton(
                                    onClick = { pending = review to "delete" },
                                    enabled = !bulkBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text("حذف نهائي", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
