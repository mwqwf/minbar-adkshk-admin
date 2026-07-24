package com.ali.ishaqiyin_admin.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.ishaqiyin_admin.ui.RemoteImage
import com.ali.ishaqiyin_admin.ui.kBg
import com.ali.ishaqiyin_admin.ui.kBoxBg
import com.ali.ishaqiyin_admin.ui.kDanger
import com.ali.ishaqiyin_admin.ui.kTeal
import com.ali.ishaqiyin_admin.ui.kTealDark
import com.ali.ishaqiyin_admin.util.openExternalUri

/**
 * ألوان دردشة الإدارة — مكيَّفة لسمة منبر الفاتحة (المصدر: لوحة نبراس
 * الداكنة، فاستُبدلت القيم بما يقرأ جيّداً على خلفيّة فاتحة).
 */
object ChatColors {
    val bg = kBg
    val surface = Color.White
    val surfaceAlt = kBoxBg
    val border = Color(0xFFCCE3E3)
    val textMuted = Color(0xFF64748B)
    val accent = kTeal
    val accentDark = kTealDark
    val amber = Color(0xFFB45309)
    val rose = kDanger
    val highlight = Color(0xFFDFF0EE)
    val online = Color(0xFF16A34A)
    val readBlue = Color(0xFF0284C7)

    /** فقاعة رسائلي (أخضر فاتح بنمط واتساب) وحدودها. */
    val mineBubble = Color(0xFFD9F2E7)
    val mineBubbleBorder = Color(0xFFB8E3D2)
}

/**
 * ألوان أسماء المرسِلين (مثل واتساب — لون ثابت لكلّ عضو)، دَرَجات داكنة
 * تقرأ جيّداً على سمة منبر الفاتحة.
 */
private val senderColors = listOf(
    Color(0xFF059669),
    Color(0xFF2563EB),
    Color(0xFFDB2777),
    Color(0xFFD97706),
    Color(0xFF7C3AED),
    Color(0xFFDC2626),
    Color(0xFF0D9488),
    Color(0xFFEA580C),
)

fun senderColor(uid: String): Color =
    senderColors[kotlin.math.abs(uid.hashCode()) % senderColors.size]

/**
 * صورة عضو دائريّة (المخصّصة أولاً ثم Google ثم الحرف الأوّل) مع نقطة
 * «متصل الآن» اختياريّة.
 */
@Composable
fun MemberAvatar(
    uid: String,
    name: String,
    photo: String,
    radius: Int = 16,
    showOnline: Boolean = false,
    online: Boolean = false,
) {
    val diameter = radius * 2
    Box {
        Box(
            Modifier
                .size(diameter.dp)
                .background(senderColor(uid).copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (photo.isNotEmpty()) {
                RemoteImage(photo, Modifier.size(diameter.dp).clip(CircleShape))
            } else {
                Text(
                    name.trim().firstOrNull()?.uppercase() ?: "?",
                    color = senderColor(uid),
                    fontSize = (radius * 0.85).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (showOnline) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size((radius * 0.62).dp)
                    .background(
                        if (online) ChatColors.online else ChatColors.textMuted,
                        CircleShape,
                    )
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}

/**
 * 🔗 نصّ رسالة مع روابط قابلة للنقر (نمط واتساب): يتعرّف على الروابط
 * (https/http/www) والبُرد الإلكترونيّة وأرقام الهواتف، فيلوّنها ويسطّرها؛
 * النقر يفتحها بالتطبيق المناسب.
 */
private val linkPattern = Regex(
    "(https?://[^\\s<>\"]+)" +
        "|(www\\.[^\\s<>\"]+)" +
        "|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})" +
        "|(\\+[0-9][0-9\\s\\-]{7,}[0-9])",
    RegexOption.IGNORE_CASE,
)

private fun uriFor(raw: String): Uri? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    return when {
        s.startsWith("http://", true) || s.startsWith("https://", true) -> Uri.parse(s)
        s.startsWith("www.", true) -> Uri.parse("https://$s")
        s.contains('@') -> Uri.parse("mailto:$s")
        s.startsWith("+") -> Uri.parse("tel:${s.replace(Regex("[\\s-]"), "")}")
        else -> Uri.parse(s)
    }
}

@Composable
fun ChatLinkText(
    text: String,
    style: TextStyle = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
    linkColor: Color = ChatColors.accentDark,
) {
    val context = LocalContext.current
    val matches = remember(text) { linkPattern.findAll(text).toList() }
    if (matches.isEmpty()) {
        Text(text, style = style)
        return
    }
    val annotated = buildAnnotatedString {
        var cursor = 0
        for (match in matches) {
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val raw = match.value
            withLink(
                LinkAnnotation.Clickable(
                    tag = "link",
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ),
                    linkInteractionListener = {
                        uriFor(raw)?.let { context.openExternalUri(it) }
                    },
                ),
            ) { append(raw) }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
    Text(annotated, style = style)
}

fun copyText(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("text", text))
}
