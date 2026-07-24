package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// هويّة لوحة الإدارة — منقولة حرفياً من theme.dart في نسخة Flutter.
val kTeal = Color(0xFF247172)
val kTealDark = Color(0xFF184B4B)
val kBg = Color(0xFFF8FCFC)
val kBoxBg = Color(0xFFE6F2F2)
val kDanger = Color(0xFFC0392B)
val kGold = Color(0xFFD4AF37)
val kOrange = Color(0xFFE67E22)
val kGreen = Color(0xFF27AE60)
val kBlue = Color(0xFF2980B9)
val kPurple = Color(0xFF7D3C98)

/** حدود الحقول في الأصل (inputDecorationTheme). */
val kFieldBorder = Color(0xFFCCE3E3)
val kMuted = Color(0xFF757575)

private val AdminColors = lightColorScheme(
    primary = kTeal,
    onPrimary = Color.White,
    primaryContainer = kBoxBg,
    onPrimaryContainer = kTealDark,
    secondary = kTealDark,
    onSecondary = Color.White,
    secondaryContainer = kBoxBg,
    onSecondaryContainer = kTealDark,
    tertiary = kGold,
    onTertiary = Color.White,
    background = kBg,
    onBackground = Color(0xFF1A2226),
    surface = Color.White,
    onSurface = Color(0xFF1A2226),
    surfaceVariant = kBoxBg,
    onSurfaceVariant = Color(0xFF47555A),
    surfaceContainer = Color.White,
    surfaceContainerHigh = kBoxBg,
    error = kDanger,
    onError = Color.White,
    outline = Color(0xFF6C7A80),
    outlineVariant = kFieldBorder,
)

private val AdminShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun MinbarAdminTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AdminColors,
        shapes = AdminShapes,
        content = content,
    )
}
