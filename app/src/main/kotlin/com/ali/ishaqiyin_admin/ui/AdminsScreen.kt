package com.ali.ishaqiyin_admin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.BuildConfig
import com.ali.ishaqiyin_admin.core.AppConfig
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.AuthService
import com.ali.ishaqiyin_admin.data.PendingOwnerCode
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * شاشة الحساب والصلاحية (مالك / مشرف). المالك وحده يرى بطاقة الرمز المعلَّق
 * الحيّة؛ وقائمة «المشرفون» يفتحها الجميع (أفعال المالك داخلها له وحده).
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIcon(
                        if (isOwner) Icons.Filled.VerifiedUser else Icons.Filled.Person,
                        MaterialTheme.colorScheme.primary,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (isOwner) {
                Spacer(Modifier.height(12.dp))
                PendingOwnerCodeCards()
            }
            Spacer(Modifier.height(12.dp))
            // 👥 قائمة المشرفين يراها الجميع؛ أفعال المالك تظهر له وحده داخلها.
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSupervisors),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIcon(Icons.Filled.SupervisorAccount, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("المشرفون")
                        Text(
                            if (isOwner) "الحضور والرتب والحظر والطرد" else "من معك في الإدارة وحضورهم",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ‏RTL: الرمز تلقائي الانعكاس، فـRight يُرسم يساراً —
                    // وإلّا صار سهم «ادخل» مطابقاً لسهم «ارجع» في الشريط.
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(
                    if (isOwner) {
                        "أنت المالك (${AppConfig.OWNER_EMAIL}). أي حساب Google جديد " +
                            "يسجّل الدخول ويطلب رمز اعتماد — يظهر لك حيّاً أعلاه " +
                            "فتُبلّغ به صاحبه (مكالمة/واتساب)، فيُدخله ويصبح مشرفاً " +
                            "تلقائياً. لا حاجة لإضافة بريده يدوياً."
                    } else {
                        "صلاحياتك كمشرف تشمل إضافة وتعديل المحتوى ونقله إلى السلة. " +
                            "الإتلاف النهائيّ والحظر والطرد للمالك وحده."
                    },
                    lineHeight = 24.sp,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 🔗 رمز ربط جهاز جديد لنفسي (10 دقائق) — يُدخَل في شاشة الدخول هناك.
            var linkCode by remember { mutableStateOf<AdminRepository.AccessCode?>(null) }
            var linking by remember { mutableStateOf(false) }
            val snack = LocalSnack.current
            linkCode?.let {
                AccessCodeDialog(
                    title = "ربط جهاز جديد",
                    code = it.code,
                    note = "أدخله في «لديّ رمز دخول» على الجهاز الآخر — صالح 10 دقائق، مرّة واحدة.",
                    onDismiss = { linkCode = null },
                )
            }
            OutlinedButton(
                onClick = {
                    linking = true
                    scope.launch {
                        runCatching { AdminRepository.createLinkCode() }
                            .onSuccess { linkCode = it }
                            .onFailure { snack("تعذّر إصدار الرمز: ${it.arabicReason()}") }
                        linking = false
                    }
                },
                enabled = !linking,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (linking) Spin(size = 16) else Icon(Icons.Filled.Link, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("ربط جهاز جديد")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { scope.launch { AuthService.signOut(context) } },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                Text("تسجيل الخروج", color = MaterialTheme.colorScheme.error)
            }
            AccountFooter()
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

    // 🔋 كانت حلقة دائمة تُبطل التركيب 60 مرّة/دقيقة حتى بلا رمز واحد
    // معلَّق. الآن تعمل فقط ما دام هناك رمز لم تنتهِ مدّته (العدّاد بالثواني
    // فتبقى الفترة ثانية واحدة ما دام يعمل).
    val counting = codes.any { it.expiresAtMs > now }
    LaunchedEffect(counting) {
        while (counting) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    confirmCancel?.let { code ->
        ConfirmDialog(
            title = "إلغاء الرمز",
            body = "سيُبطَل رمز «${code.candidateEmail}» نهائياً ولن يقبله الخادم. متابعة؟",
            confirmLabel = "إلغاء الرمز",
            confirmColor = MaterialTheme.colorScheme.error,
            onDismiss = { confirmCancel = null },
            onConfirm = {
                confirmCancel = null
                scope.launch {
                    runCatching { AdminRepository.cancelOwnerCode(code) }
                        .onFailure { snack("تعذّر إلغاء الرمز: ${it.arabicReason()}") }
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LockClock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "طلب انضمام مشرف معلَّق",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$minutes:${seconds.toString().padStart(2, '0')}",
                            color = MaterialTheme.colorScheme.primary,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            pending.code,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "بلّغ المرشّح بهذا الرمز (مكالمة/واتساب) ليُدخله في تطبيقه " +
                            "ويُعتمَد مشرفاً فوراً.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("إلغاء", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/**
 * تذييل هادئ: سياسة الخصوصية ورقم الإصدار.
 *
 * موضعه في هذه الشاشة مقصود — Play يشترط رابطاً للسياسة داخل التطبيق لا في
 * صفحة المتجر وحدها، وهذه شاشة حساب لا يفتحها المشرف إلا قاصداً، فلا تزاحم
 * العمل اليومي في اللوحة الرئيسية. **وهو خارج بطاقات الرموز المعلَّقة** لأن
 * الشرط أن يظهر دائماً: كان بداخل حلقتها فيختفي بلا رموز (الحالة الغالبة)
 * ويتكرّر مع كلّ مرشّح.
 */
@Composable
private fun AccountFooter() {
    val context = LocalContext.current
    val snack = LocalSnack.current
    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "الإصدار ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
                if (!opened) {
                    copyToClipboard(context, PRIVACY_POLICY_URL)
                    snack("تعذّر فتح المتصفّح — نُسخ الرابط.")
                }
            },
        ) {
            Icon(
                Icons.Filled.PrivacyTip,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text("سياسة الخصوصية", style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** سياسة خصوصية **لوحة الإدارة** — غير سياسة التطبيق العام (‏/privacy). */
private const val PRIVACY_POLICY_URL =
    "https://minbar-adkassahk.vercel.app/admin-privacy"

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("code", text))
}
