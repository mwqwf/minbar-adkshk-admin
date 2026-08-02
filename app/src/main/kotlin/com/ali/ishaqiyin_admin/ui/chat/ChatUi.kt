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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

    /**
     * حبر الخلفيّة المزخرفة — شفافيّة واتساب الفعليّة (≈0.05): زخرفة تُحسّ
     * ولا تُرى كشبكة. (كانت 0.12 فيظهر التكرار للعين ويتضاعف التباين حيث
     * تتقاطع الأشكال.)
     */
    val wallpaperInk = Color(0xFF6B7C83).copy(alpha = 0.055f)

    /**
     * ألوان الرسالة الصوتيّة بنمط واتساب — رماديّات محايدة **لا** لون
     * السمة التركوازيّ: الأزرق [readBlue] محجوز لعلامتَي القراءة ولميكروفون
     * «سمعها المتلقّي» وحدهما، فوجوده في الموجة يُربكه بمعنى ✓✓.
     */
    val waveRest = Color(0xFFB9C1C6)
    val wavePlayed = Color(0xFF54656F)

    /** ميكروفون رسالة واردة **لم أسمعها بعد** (أخضر واتساب). */
    val micUnheard = Color(0xFF00A884)

    /** ميكروفون محايد: رسالتي قبل أن يسمعها أحد، والواردة بعد سماعها. */
    val micIdle = Color(0xFF8696A0)

    /** نصّ الوقت/المدّة/الحالة تحت الفقاعة. */
    val metaText = Color(0xFF46586A)

    /** اقتباس الردّ: شريط جانبيّ وخلفيّة داخل فقاعتي وفقاعة غيري. */
    val quoteBarMine = Color(0xFF06CF9C)
    val quoteBgMine = Color(0xFFD1F4CC)
    val quoteBgOther = Color.Black.copy(alpha = 0.05f)
}

/** عدد محارف الخلفيّة المشتقّة من هويّة منبر (أيقونة التطبيق واللوحة). */
private const val WALLPAPER_GLYPHS = 8

/**
 * مولّد تشويش **حتميّ بالفهرس**: نفس (الصفّ، العمود، الملح) يعطي نفس القيمة
 * أبداً — فيثبت نمط الخلفيّة بين إعادات التركيب (لا عشوائيّة حقيقيّة تُعيد
 * رسم زخرفة مختلفة مع كلّ رسالة جديدة).
 */
private fun tileNoise(row: Int, column: Int, salt: Int): Float {
    var h = (row * 73856093) xor (column * 19349663) xor (salt * 83492791)
    h = h xor (h ushr 13)
    h *= 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7FFFFFFF) / 2147483647f
}

/**
 * خلفية دردشة بنمط واتساب: نثر كثيف من محارف هويّة منبر (منبر، كتاب مفتوح،
 * ميكروفون، موجات صوت، نجمة ثمانيّة، معيَّن مزخرف، دائرة تشغيل، قوس محراب)
 * بزوايا وأحجام ومواضع متفاوتة، فلا تظهر شبكة متكرّرة للعين. كلّ التفاوت من
 * [tileNoise] الحتميّ. تُرسم بالـCanvas فلا تضيف صورة كبيرة إلى APK،
 * ويستعملها الخاصّ والمجموعة من المصدر نفسه.
 */
@Composable
fun WhatsAppChatBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(ChatColors.bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val tile = 76.dp.toPx()
            val stroke = 1.dp.toPx()
            val jitter = 10.dp.toPx()
            val base = 26.dp.toPx()
            val rows = (size.height / tile).toInt() + 2
            val columns = (size.width / tile).toInt() + 2
            for (row in -1..rows) {
                for (column in -1..columns) {
                    val shift = if ((row and 1) == 0) 0f else tile / 2f
                    val center = Offset(
                        column * tile + tile / 2f + shift +
                            (tileNoise(row, column, 3) - 0.5f) * 2f * jitter,
                        row * tile + tile / 2f +
                            (tileNoise(row, column, 5) - 0.5f) * 2f * jitter,
                    )
                    val glyph = (tileNoise(row, column, 7) * WALLPAPER_GLYPHS)
                        .toInt().coerceIn(0, WALLPAPER_GLYPHS - 1)
                    val angle = (tileNoise(row, column, 11) - 0.5f) * 70f
                    val icon = base * (0.78f + tileNoise(row, column, 13) * 0.5f)
                    rotate(angle, center) {
                        drawWallpaperGlyph(glyph, center, icon, stroke)
                    }
                }
            }
        }
        content()
    }
}

/** محرف خلفيّة واحد بحبر [ChatColors.wallpaperInk] الشفّاف. */
private fun DrawScope.drawWallpaperGlyph(
    index: Int,
    center: Offset,
    s: Float,
    stroke: Float,
) {
    val ink = ChatColors.wallpaperInk
    val line = Stroke(stroke)
    val cx = center.x
    val cy = center.y
    when (index) {
        // منبر بدرجاته.
        0 -> drawPath(
            Path().apply {
                moveTo(cx - s * 0.46f, cy + s * 0.46f)
                lineTo(cx - s * 0.46f, cy + s * 0.12f)
                lineTo(cx - s * 0.14f, cy + s * 0.12f)
                lineTo(cx - s * 0.14f, cy - s * 0.16f)
                lineTo(cx + s * 0.18f, cy - s * 0.16f)
                lineTo(cx + s * 0.18f, cy - s * 0.46f)
                lineTo(cx + s * 0.46f, cy - s * 0.46f)
                lineTo(cx + s * 0.46f, cy + s * 0.46f)
                close()
            },
            ink,
            style = line,
        )
        // كتاب مفتوح بصفحتين وخطّ الكعب.
        1 -> {
            drawPath(
                Path().apply {
                    moveTo(cx, cy - s * 0.3f)
                    lineTo(cx - s * 0.46f, cy - s * 0.16f)
                    lineTo(cx - s * 0.46f, cy + s * 0.32f)
                    lineTo(cx, cy + s * 0.18f)
                    lineTo(cx + s * 0.46f, cy + s * 0.32f)
                    lineTo(cx + s * 0.46f, cy - s * 0.16f)
                    close()
                },
                ink,
                style = line,
            )
            drawLine(ink, Offset(cx, cy - s * 0.3f), Offset(cx, cy + s * 0.18f), stroke)
        }
        // ميكروفون بحامله.
        2 -> {
            drawRoundRect(
                color = ink,
                topLeft = Offset(cx - s * 0.15f, cy - s * 0.46f),
                size = Size(s * 0.3f, s * 0.56f),
                cornerRadius = CornerRadius(s * 0.15f),
                style = line,
            )
            drawArc(
                color = ink,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - s * 0.32f, cy - s * 0.26f),
                size = Size(s * 0.64f, s * 0.56f),
                style = line,
            )
            drawLine(ink, Offset(cx, cy + s * 0.3f), Offset(cx, cy + s * 0.46f), stroke)
        }
        // موجات صوت خارجة من نقطة.
        3 -> {
            repeat(3) { ring ->
                val r = s * (0.18f + 0.14f * ring)
                drawArc(
                    color = ink,
                    startAngle = -55f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = line,
                )
            }
            drawCircle(ink, s * 0.07f, Offset(cx - s * 0.24f, cy))
        }
        // نجمة ثمانيّة (زخرفة الأيقونتين).
        4 -> {
            val star = Path()
            repeat(16) { point ->
                val angle = -Math.PI / 2 + point * Math.PI / 8
                val radius = if (point % 2 == 0) s * 0.46f else s * 0.2f
                val px = cx + kotlin.math.cos(angle).toFloat() * radius
                val py = cy + kotlin.math.sin(angle).toFloat() * radius
                if (point == 0) star.moveTo(px, py) else star.lineTo(px, py)
            }
            star.close()
            drawPath(star, ink, style = line)
        }
        // معيَّن مزخرف بمعيَّن داخليّ.
        5 -> {
            drawPath(
                Path().apply {
                    moveTo(cx, cy - s * 0.46f)
                    lineTo(cx + s * 0.4f, cy)
                    lineTo(cx, cy + s * 0.46f)
                    lineTo(cx - s * 0.4f, cy)
                    close()
                },
                ink,
                style = line,
            )
            drawPath(
                Path().apply {
                    moveTo(cx, cy - s * 0.2f)
                    lineTo(cx + s * 0.17f, cy)
                    lineTo(cx, cy + s * 0.2f)
                    lineTo(cx - s * 0.17f, cy)
                    close()
                },
                ink,
                style = line,
            )
        }
        // دائرة تشغيل.
        6 -> {
            drawCircle(ink, s * 0.4f, center, style = line)
            drawPath(
                Path().apply {
                    moveTo(cx - s * 0.12f, cy - s * 0.19f)
                    lineTo(cx + s * 0.21f, cy)
                    lineTo(cx - s * 0.12f, cy + s * 0.19f)
                    close()
                },
                ink,
                style = line,
            )
        }
        // قوس محراب.
        else -> {
            drawArc(
                color = ink,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - s * 0.32f, cy - s * 0.44f),
                size = Size(s * 0.64f, s * 0.64f),
                style = line,
            )
            drawLine(
                ink,
                Offset(cx - s * 0.32f, cy - s * 0.12f),
                Offset(cx - s * 0.32f, cy + s * 0.44f),
                stroke,
            )
            drawLine(
                ink,
                Offset(cx + s * 0.32f, cy - s * 0.12f),
                Offset(cx + s * 0.32f, cy + s * 0.44f),
                stroke,
            )
            drawLine(
                ink,
                Offset(cx - s * 0.44f, cy + s * 0.44f),
                Offset(cx + s * 0.44f, cy + s * 0.44f),
                stroke,
            )
        }
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
