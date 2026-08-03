package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** صورة من الشبكة بحوافّ دائرية (بديل CachedNetworkImage). */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * صورة عضو دائريّة: الصورة إن وُجدت، وإلّا الحرف الأوّل من الاسم فوق خلفيّة
 * ملوّنة (أو أيقونة بديلة).
 */
@Composable
fun RemoteAvatar(
    url: String,
    fallbackText: String,
    size: Int = 40,
    background: Color = MaterialTheme.colorScheme.primary,
    // حبر الحرف/الأيقونة يُشتقّ من الخلفيّة نفسها: أبيض فوق الداكنة وحبر
    // داكن فوق الفاتحة (كالذهب في الوضع الداكن).
    textColor: Color = contentColorOn(background),
    fallbackIcon: ImageVector? = null,
) {
    Box(
        Modifier.size(size.dp).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotEmpty()) {
            RemoteImage(url, Modifier.size(size.dp).clip(CircleShape))
        } else if (fallbackIcon != null) {
            Icon(
                fallbackIcon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size((size * 0.5).dp),
            )
        } else {
            Text(
                fallbackText.trim().firstOrNull()?.uppercase() ?: "?",
                color = textColor,
                fontSize = (size * 0.42).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** أيقونة شخص افتراضيّة (تُستعمل عند غياب أيّ بيانات). */
@Composable
fun DefaultPersonAvatar(size: Int = 40) {
    RemoteAvatar(
        url = "",
        fallbackText = "",
        size = size,
        fallbackIcon = Icons.Filled.Person,
    )
}
