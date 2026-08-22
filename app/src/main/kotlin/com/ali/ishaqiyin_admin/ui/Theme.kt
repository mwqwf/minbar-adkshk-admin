package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.ali.ishaqiyin_admin.ui.chat.ChatColors

// ═══════════════════════════════════════════════════════════════════════════
//  هويّة لوحة الإدارة — مشتقّة من أيقونة اللوحة نفسها.
//  خلفيّة الأيقونة #020E1D (انظر res/values/colors.xml) هي الأدكن والأغنى
//  بالدرجات في العائلة، فالوضع الداكن هو موطنها الطبيعي لا استثناءً عليها.
//  كلّ قيمة أدناه متحقَّق تباينها وفق WCAG AA (4.5:1 للنصّ، 3:1 للعناصر
//  الوظيفيّة) على السطح الذي تُستعمل فوقه.
// ═══════════════════════════════════════════════════════════════════════════

/** ═══ سلّم الحبر (أسطح الوضع الداكن ونصّ الوضع الفاتح) ═══ */
val Ink900 = Color(0xFF020E1D) // خلفيّة الوضع الداكن + نصّ الوضع الفاتح (19.39)
val Ink800 = Color(0xFF031623) // السطح الأساس الداكن
val Ink700 = Color(0xFF05232C) // حاوية سطح داكنة
val Ink600 = Color(0xFF072A33) // حاوية سطح داكنة مرتفعة
val Ink500 = Color(0xFF1A2B3A) // حدّ خافت على الداكن (outlineVariant)
val Ink300 = Color(0xFFA6B8C9) // نصّ ثانوي على الداكن (9.53)
val Ink200 = Color(0xFFC3CFDB) // حدّ خافت على الفاتح
val Ink100 = Color(0xFFE6EDF5) // نصّ أساس على الداكن (16.43 / 15.57)

/**
 * ═══ سلّم الذهب ═══
 * **قاعدة إلزاميّة:** `Gold100`–`Gold800` للوضع الداكن والرسوم الكبيرة فقط.
 * `Gold900 #885618` وحده يصلح **نصّاً على خلفيّة فاتحة** (6.19)، أمّا
 * `Gold800 #B8771B` فيعطي 3.69 على الأبيض — راسب في WCAG AA للنصّ.
 */
val Gold100 = Color(0xFFFBEFCF) // حاوية ذهبيّة على الفاتح
val Gold200 = Color(0xFFF6E1A8)
val Gold300 = Color(0xFFF4BB44) // اللون الأوّل في الوضع الداكن (11.11)
val Gold400 = Color(0xFFE7A62F)
val Gold500 = Color(0xFFD89522)
val Gold600 = Color(0xFFC9861D)
val Gold700 = Color(0xFFC07E1C)
val Gold800 = Color(0xFFB8771B) // رسوم كبيرة فقط — 3.69 على الأبيض
val Gold900 = Color(0xFF885618) // الذهب الوحيد الصالح نصّاً على الفاتح (6.19)

/** ═══ درجات مساعدة (نظائر داكنة/فاتحة للألوان الدلاليّة) ═══ */
val Green300 = Color(0xFF67AF96) // أخضر على الداكن (7.52)
val Green800 = Color(0xFF0B6B42) // أخضر على الفاتح (6.57)
val Orange300 = Color(0xFFF2A365) // برتقالي على الداكن (9.40)
val Orange800 = Color(0xFF8C4A12) // برتقالي على الفاتح (6.77)
val Blue300 = Color(0xFF7FC0E8) // أزرق على الداكن (9.78)
val Blue800 = Color(0xFF1C5E8C) // أزرق على الفاتح (6.93)
val Red300 = Color(0xFFFF9E90) // أحمر على الداكن (9.74)
val Red800 = Color(0xFFA62A1E) // أحمر على الفاتح

// ───────────────────────────────────────────────────────────────────────────
// رموز الهويّة المنقولة من theme.dart — قيمها الآن قيم **الوضع الفاتح**
// المتحقَّقة. كلّها ثوابت عاديّة (لا @Composable) لأنّ بعض الشاشات تستعملها
// خارج سياق التأليف (مثل kindOf في FeedbackScreen).
// للوضع الداكن استعمل المُلحقات المتكيّفة أسفل الملفّ (adminGold… إلخ).
// ───────────────────────────────────────────────────────────────────────────
val kTeal = Color(0xFF247172)
val kTealDark = Color(0xFF184B4B)
val kBg = Color(0xFFF8FCFC)
val kBoxBg = Color(0xFFE6F2F2)
val kDanger = Color(0xFFC0392B)
val kGold = Gold900 // كان #D4AF37 (2.10) — راسب نصّاً على الفاتح
val kOrange = Orange800 // كان #E67E22 (2.85)
val kGreen = Green800 // كان #27AE60 (2.87)
val kBlue = Blue800 // كان #2980B9 (4.30)
val kPurple = Color(0xFF7D3C98)

/**
 * حدود الحقول في الأصل (inputDecorationTheme). كانت #CCE3E3 بتباين 1.34 —
 * وحدّ الحقل عنصر واجهة **وظيفيّ** يخضع لـWCAG 1.4.11 (3:1) ولا يُعفى
 * بوصفه فاصلاً زخرفيّاً.
 */
val kFieldBorder = Color(0xFF6E7F90) // 4.12
val kMuted = Color(0xFF46586A) // كان #757575 (4.03) — 6.41

/** ذهب الشارات الداكن: «المالك» على خلفيّة ذهبيّة شفّافة فاتحة (9.15). */
val kOwnerBadge = Color(0xFF5C3D06)

private val AdminLightColors = lightColorScheme(
    primary = Color(0xFF025864), // 8.13
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EBEC),
    onPrimaryContainer = Color(0xFF034853),
    inversePrimary = Gold300,
    secondary = Gold900, // 6.19
    onSecondary = Color.White,
    secondaryContainer = Gold100,
    onSecondaryContainer = Color(0xFF4A3208),
    tertiary = Color(0xFF085956), // 8.16
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFE7E5),
    onTertiaryContainer = Color(0xFF063F3D),
    background = Color(0xFFF4F7FA),
    onBackground = Ink900, // 19.39
    surface = Color.White,
    onSurface = Ink900,
    surfaceVariant = Color(0xFFE9EFF5),
    onSurfaceVariant = Color(0xFF46586A), // 7.33
    surfaceDim = Color(0xFFDFE7F0),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4F8),
    surfaceContainer = Color(0xFFE9EFF5),
    surfaceContainerHigh = Color(0xFFDFE7F0),
    surfaceContainerHighest = Color(0xFFD5DFEA),
    inverseSurface = Ink800,
    inverseOnSurface = Ink100,
    error = Red800,
    onError = Color.White,
    errorContainer = Color(0xFFFBDDD8),
    onErrorContainer = Color(0xFF7A1E15),
    outline = Color(0xFF6E7F90), // 4.12
    outlineVariant = Ink200,
)

/**
 * الوضع الداكن — هو الأقرب لهويّة الأيقونة (خلفيّتها #020E1D).
 * ⚠️ قيد مُصرَّح به: `tertiary #068F93` يعبر 4.5:1 على `background` و`surface`
 * فقط، ويهبط دون ذلك على الحاويات المرتفعة ⇒ يُستعمل **نصّاً** على الخلفيّة
 * والسطح الأساس وحدهما، و**أيقونةً أو حدّاً** على الأسطح المرتفعة.
 */
private val AdminDarkColors = darkColorScheme(
    primary = Gold300, // 11.11
    onPrimary = Ink900,
    primaryContainer = Color(0xFF3E2F10),
    onPrimaryContainer = Gold300,
    inversePrimary = Color(0xFF025864),
    secondary = Green300, // 7.52
    onSecondary = Ink900,
    secondaryContainer = Color(0xFF0E3D31),
    onSecondaryContainer = Green300,
    tertiary = Color(0xFF068F93), // 4.95 على الخلفيّة
    onTertiary = Ink900,
    tertiaryContainer = Ink700,
    onTertiaryContainer = Color(0xFF9BD9DB),
    background = Ink900,
    onBackground = Ink100, // 16.43
    surface = Ink800,
    onSurface = Ink100, // 15.57
    surfaceVariant = Ink700,
    onSurfaceVariant = Ink300, // 9.53
    surfaceDim = Ink900,
    surfaceBright = Color(0xFF0A343E),
    surfaceContainerLowest = Color(0xFF010913),
    surfaceContainerLow = Color(0xFF03121E),
    surfaceContainer = Ink700,
    surfaceContainerHigh = Ink600,
    surfaceContainerHighest = Color(0xFF0A343E),
    inverseSurface = Ink100,
    inverseOnSurface = Ink800,
    error = Red300, // 9.74
    onError = Ink900,
    errorContainer = Color(0xFF4E1710),
    onErrorContainer = Red300,
    outline = Color(0xFF6B7D8F), // 4.57
    outlineVariant = Ink500,
)

private val AdminShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun MinbarAdminTheme(
    // اللوحة تتبع سمة النظام. كان هذا مثبَّتاً على `false` ريثما تُحوَّل
    // الشاشات من ألوانها الفاتحة الثابتة إلى أدوار السمة؛ اكتمل التحويل
    // (‏~‏400 موضع في كل الشاشات و`ui/chat/`) فرُفع التثبيت.
    // ⚠️ من يضيف شاشة جديدة: استعمل `MaterialTheme.colorScheme` والمُلحقات
    // المتكيّفة (`adminGold` وأخواتها) لا ألواناً ثابتة، وإلا ظهرت بيضاء
    // فوق خلفيّة داكنة.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // مصدر سمة الدردشة صار هنا لا في WhatsAppChatBackground: شاشتا
    // «المحادثات» و«معلومات المجموعة» تقرآن ChatColors بلا المرور بخلفيّة
    // الدردشة، فكانتا تُرسمان بقيم الفاتح في الوضع الداكن (نصّ kTealDark فوق
    // Ink900 ≈ 1.5:1) حتّى تُزار المجموعة فتنقلب الألوان فجأة.
    // السمة سلف كلّ الشاشات فتُكتب القيمة قبل أن يقرأها أيّ مستهلك ⇒ بلا وميض،
    // والشرط يمنع كتابة عقيمة تُعيد التأليف بلا داعٍ.
    if (ChatColors.dark != darkTheme) ChatColors.dark = darkTheme
    MaterialTheme(
        colorScheme = if (darkTheme) AdminDarkColors else AdminLightColors,
        shapes = AdminShapes,
        content = content,
    )
}

/**
 * هل السمة الجارية داكنة؟ يُقرأ من إضاءة الخلفيّة نفسها لا من سمة النظام،
 * فيبقى صحيحاً داخل المعاينات وأيّ سمة تُمرَّر يدويّاً.
 */
@Composable
fun isAdminDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** ═══ مُلحقات متكيّفة: النظير الصحيح للّون في كلا الوضعين ═══ */

/** ذهب المختارات والتمييز. */
val adminGold: Color
    @Composable get() = if (isAdminDarkTheme()) Gold300 else Gold900

/** برتقالي التنبيه والجدولة. */
val adminOrange: Color
    @Composable get() = if (isAdminDarkTheme()) Orange300 else Orange800

/** أخضر النجاح و«نشِط». */
val adminGreen: Color
    @Composable get() = if (isAdminDarkTheme()) Green300 else Green800

/** أزرق الروابط والرسائل. */
val adminBlue: Color
    @Composable get() = if (isAdminDarkTheme()) Blue300 else Blue800

/**
 * لون المحتوى الصحيح فوق أيّ خلفيّة ملوّنة: الأسطح الفاتحة (كالذهب في
 * الوضع الداكن) تحتاج حبراً داكناً لا أبيض.
 */
fun contentColorOn(background: Color): Color =
    if (background.luminance() > 0.45f) Ink900 else Color.White
