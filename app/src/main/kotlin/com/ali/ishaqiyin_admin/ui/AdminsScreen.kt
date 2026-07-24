package com.ali.ishaqiyin_admin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.core.AppConfig
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.PendingOwnerCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * شاشة الحساب والصلاحية (مطابقة لنمط لوحة نبراس: مالك / مشرف).
 * المالك فقط يرى بطاقة الرمز المعلَّق الحيّة ويفتح «إدارة المشرفين».
 * الكتابة مفروضة في قواعد Firestore على الخادم.
 */
@Composable
fun AdminsScreen(isOwner: Boolean, onBack: () -> Unit, onOpenSupervisors: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val email = AuthService.currentUser?.email ?: "—"

    AdminScaffold(title = "الحساب والصلاحية", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIcon(
                        if (isOwner) Icons.Filled.VerifiedUser else Icons.Filled.Person,
                        kTeal,
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(email, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isOwner) {
                                "المالك — صلاحية كاملة"
                            } else {
                                "مشرف — صلاحية إدارة المحتوى"
                            },
                            fontSize = 12.sp,
                            color = kMuted,
                        )
                    }
                }
            }
            if (isOwner) {
                Spacer(Modifier.height(12.dp))
                PendingOwnerCodeCards()
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSupervisors),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleIcon(Icons.Filled.SupervisorAccount, kTeal)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("إدارة المشرفين")
                            Text(
                                "حظر مؤقّت/نهائي/إلغاء حظر/حذف",
                                fontSize = 12.sp,
                                color = kMuted,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = kMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(kBoxBg, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(
                    if (isOwner) {
                        "أنت المالك (${AppConfig.OWNER_EMAIL}). أي حساب Google جديد " +
                            "يسجّل الدخول ويطلب رمز اعتماد — يظهر لك حيّاً أعلاه " +
                            "فتُبلّغ به صاحبه (مكالمة/واتساب)، فيُدخله ويصبح مشرفاً " +
                            "تلقائياً. لا حاجة لإضافة بريده يدوياً."
                    } else {
                        "صلاحياتك كمشرف تشمل إضافة وتعديل وحذف المحتوى. " +
                            "إدارة المشرفين متاحة للمالك فقط."
                    },
                    lineHeight = 24.sp,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { scope.launch { AuthService.signOut(context) } },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = kDanger,
                )
                Spacer(Modifier.size(8.dp))
                Text("تسجيل الخروج", color = kDanger)
            }
        }
    }
}

/**
 * بطاقات حيّة تعرض للمالك رموز الاعتماد المعلَّقة — بديل بريد نبراس
 * الإلكتروني لتوصيل الرمز.
 */
@Composable
private fun PendingOwnerCodeCards() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val context = LocalContext.current
    val codes by remember { AdminRepository.watchPendingOwnerCodes() }
        .collectAsState(initial = emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmCancel by remember { mutableStateOf<PendingOwnerCode?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    confirmCancel?.let { code ->
        ConfirmDialog(
            title = "إلغاء الرمز",
            body = "سيُبطَل رمز «${code.candidateEmail}» نهائياً ولن يقبله الخادم. متابعة؟",
            confirmLabel = "إلغاء الرمز",
            confirmColor = kDanger,
            onDismiss = { confirmCancel = null },
            onConfirm = {
                confirmCancel = null
                scope.launch {
                    runCatching { AdminRepository.cancelOwnerCode(code) }
                        .onFailure { snack("تعذّر إلغاء الرمز: ${it.message ?: it}") }
                }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        codes.forEach { pending ->
            val remaining = (pending.expiresAtMs - now).coerceAtLeast(0)
            val minutes = (remaining / 60_000).coerceIn(0, 99)
            val seconds = ((remaining / 1000) % 60).coerceIn(0, 59)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = kTeal.copy(alpha = 0.08f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, kTeal.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LockClock, contentDescription = null, tint = kTeal)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "طلب انضمام مشرف معلَّق",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$minutes:${seconds.toString().padStart(2, '0')}",
                            color = kTeal,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (pending.candidateName.isNotEmpty()) {
                            "${pending.candidateName} — ${pending.candidateEmail}"
                        } else {
                            pending.candidateEmail
                        },
                        fontSize = 13.sp,
                        color = kMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            pending.code,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 10.sp,
                            color = kTeal,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "بلّغ المرشّح بهذا الرمز (مكالمة/واتساب) ليُدخله في تطبيقه " +
                            "ويُعتمَد مشرفاً فوراً.",
                        fontSize = 12.sp,
                        color = kMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, pending.code)
                                snack("تم نسخ الرمز.")
                            },
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("نسخ")
                        }
                        OutlinedButton(onClick = { confirmCancel = pending }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = kDanger,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("إلغاء", color = kDanger)
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("code", text))
}
