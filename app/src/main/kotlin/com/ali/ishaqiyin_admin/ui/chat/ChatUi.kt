package com.ali.ishaqiyin_admin.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.ali.ishaqiyin_admin.ui.kDanger
import com.ali.ishaqiyin_admin.ui.kTeal
import com.ali.ishaqiyin_admin.ui.kTealDark
import com.ali.ishaqiyin_admin.util.openExternalUri

/**
 * ألوان دردشة الإدارة — مكيَّفة لسمة منبر الفاتحة (المصدر: لوحة نبراس
 * الداكنة، فاستُبدلت القيم بما يقرأ جيّداً على خلفيّة فاتحة).
 */
object ChatColors {
    /** خلفية واتساب الفاتحة الدافئة، لا أبيض منبر المزرق. */
    val bg = Color(0xFFEFEAE2)
    val surface = Color.White
    val surfaceAlt = Color(0xFFF0F2F5)
    val border = Color(0xFFD8DEE2)
    val textMuted = Color(0xFF667781)
    val accent = kTeal
    val accentDark = kTealDark
    val amber = Color(0xFFB45309)
    val rose = kDanger
    val highlight = Color(0xFFD9FDD3)
    val online = Color(0xFF16A34A)

    /**
     * أزرق واتساب الفعليّ لعلامتَي القراءة ✓✓ وشارة «استُمع إليها».
     * (كان `0xFF0284C7` أزرق مكتبيّ داكناً باهتاً لا يشبه واتساب.) يُرسم
     * دائماً كأيقونة مصمتة صغيرة فوق الفقاعة الخضراء `mineBubble` أو فوق
     * الأبيض، وكلاهما فاتح فيبقى الأزرق مميَّزاً عنهما.
     */
    val readBlue = Color(0xFF53BDEB)

    /** فقاعة رسائلي (أخضر فاتح بنمط واتساب) وحدودها. */
    val mineBubble = Color(0xFFD9FDD3)
    val mineBubbleBorder = Color.Transparent
    val wallpaperInk = Color(0xFF6B7C83).copy(alpha = 0.12f)
}

/**
 * خلفية دردشة خفيفة بنمط رسومات واتساب المتكرّرة. تُرسم بالـCanvas فلا
 * تضيف صورة كبيرة إلى APK، ويستعملها الخاص والمجموعة من المصدر نفسه.
 */
@Composable
fun WhatsAppChatBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(ChatColors.bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val tile = 58.dp.toPx()
            val stroke = 1.dp.toPx()
            val icon = 23.dp.toPx()
            val rows = (size.height / tile).toInt() + 2
            val columns = (size.width / tile).toInt() + 2
            for (row in -1..rows) {
                for (column in -1..columns) {
                    val x = column * tile + if ((row and 1) == 0) 0f else tile / 2f
                    val y = row * tile
                    val center = Offset(x + tile / 2f, y + tile / 2f)
                    when (kotlin.math.abs(row * 7 + column) % 4) {
                        0 -> {
                            val topLeft = Offset(center.x - icon / 2f, center.y - icon * 0.36f)
                            drawRoundRect(
                                color = ChatColors.wallpaperInk,
                                topLeft = topLeft,
                                size = Size(icon, icon * 0.72f),
                                cornerRadius = CornerRadius(icon * 0.16f),
                                style = Stroke(stroke),
                            )
                            drawLine(
                                ChatColors.wallpaperInk,
                                Offset(topLeft.x + icon * 0.22f, topLeft.y + icon * 0.72f),
                                Offset(topLeft.x + icon * 0.12f, topLeft.y + icon * 0.9f),
                                stroke,
                            )
                        }
                        1 -> {
                            drawCircle(
                                color = ChatColors.wallpaperInk,
                                radius = icon * 0.38f,
                                center = center,
                                style = Stroke(stroke),
                            )
                            drawLine(
                                ChatColors.wallpaperInk,
                                Offset(center.x - icon * 0.27f, center.y + icon * 0.27f),
                                Offset(center.x + icon * 0.27f, center.y - icon * 0.27f),
                                stroke,
                            )
                        }
                        2 -> {
                            val star = Path()
                            repeat(10) { point ->
                                val angle = -Math.PI / 2 + point * Math.PI / 5
                                val radius = if (point % 2 == 0) icon * 0.42f else icon * 0.18f
                                val px = center.x + kotlin.math.cos(angle).toFloat() * radius
                                val py = center.y + kotlin.math.sin(angle).toFloat() * radius
                                if (point == 0) star.moveTo(px, py) else star.lineTo(px, py)
                            }
                            star.close()
                            drawPath(star, ChatColors.wallpaperInk, style = Stroke(stroke))
                        }
                        else -> {
                            val plane = Path().apply {
                                moveTo(center.x - icon * 0.43f, center.y - icon * 0.25f)
                                lineTo(center.x + icon * 0.44f, center.y)
                                lineTo(center.x - icon * 0.43f, center.y + icon * 0.25f)
                                lineTo(center.x - icon * 0.12f, center.y)
                                close()
                            }
                            drawPath(plane, ChatColors.wallpaperInk, style = Stroke(stroke))
                        }
                    }
                }
            }
        }
        content()
    }
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
