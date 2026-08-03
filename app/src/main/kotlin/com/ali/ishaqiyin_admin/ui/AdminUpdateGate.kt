package com.ali.ishaqiyin_admin.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ali.ishaqiyin_admin.data.AdminAppConfigRepository

/**
 * 🔔 بوّابة تذكير تحديث اللوحة.
 *
 * تُغلّف محتوى اللوحة كلّه: تُظهر شاشة التحديث بملء الشاشة متى كان إصدار
 * المشرف أقدم مما نشره المالك، وإلّا تعرض [content] كما هو.
 *
 * **لماذا ملء الشاشة؟** المشرف الذي لا يحدّث لا يرى المزايا الجديدة ولا
 * إصلاحاتها، والشريط الصغير يُتجاهَل عمليّاً. **ولماذا بلا حجب؟** إيقاف
 * اللوحة عن العمل يوقف النشر والإشراف كلّه — فزرّ المتابعة موجود دائماً.
 */
@Composable
fun AdminUpdateGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val repo = remember { AdminAppConfigRepository.get(context) }
    var status by remember {
        mutableStateOf<AdminAppConfigRepository.Status>(AdminAppConfigRepository.Status.None)
    }
    var deferred by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        status = runCatching { repo.status() }
            .getOrDefault(AdminAppConfigRepository.Status.None)
    }

    val pending = status.takeIf { it !is AdminAppConfigRepository.Status.None }
    if (pending == null || deferred || !repo.shouldPrompt(pending)) {
        content()
        return
    }

    val storeUrl = when (pending) {
        is AdminAppConfigRepository.Status.Required -> pending.storeUrl
        is AdminAppConfigRepository.Status.Optional -> pending.storeUrl
        else -> AdminAppConfigRepository.PLAY_URL
    }
    AdminUpdateScreen(
        status = pending,
        onUpdate = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { failure ->
                if (failure is ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(AdminAppConfigRepository.PLAY_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        },
        onLater = {
            deferred = true
            repo.markPrompted()
            val latest = when (pending) {
                is AdminAppConfigRepository.Status.Optional -> pending.latest
                else -> 0
            }
            // التذكير القويّ يعود كل تشغيل: لا يُصرَف لنسخته.
            if (latest > 0) repo.dismiss(latest)
        },
    )
}

@Composable
private fun AdminUpdateScreen(
    status: AdminAppConfigRepository.Status,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val required = status is AdminAppConfigRepository.Status.Required
    val custom = when (status) {
        is AdminAppConfigRepository.Status.Required -> status.message
        is AdminAppConfigRepository.Status.Optional -> status.message
        else -> ""
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                if (required) "دعم هذا الإصدار يوشك أن يتوقّف" else "صدر إصدار جديد من اللوحة",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                custom.ifBlank {
                    if (required) {
                        "نسختك من لوحة الإدارة قديمة وسيتوقّف دعمها قريباً. " +
                            "حدِّثها من صفحة الاختبار المغلق لتعمل المزايا كلّها كما ينبغي."
                    } else {
                        "حدِّث لوحة الإدارة من صفحة الاختبار المغلق لتصلك المزايا " +
                            "والإصلاحات الجديدة."
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "طابور الرفع ومحادثاتك تبقى كما هي بعد التحديث.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("تحديث الآن", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text(if (required) "متابعة الآن" else "لاحقاً")
            }
        }
    }
}
