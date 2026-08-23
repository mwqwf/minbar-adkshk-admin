package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** مرسِل رسائل Snackbar المتاح لكلّ الشاشات (نظير ScaffoldMessenger). */
val LocalSnack = compositionLocalOf<(String) -> Unit> { {} }

/**
 * شريط علوي موحَّد: عنوان موسَّط بلا ظلّ (كما في الأصل)، لكنّه يتكيّف مع
 * الوضعين: في الفاتح لون اللوحة الأوّل بنصّ أبيض، وفي الداكن حاوية سطح
 * مرتفعة بنصّ فاتح — لأنّ شريطاً ذهبيّاً كاملاً في الداكن يبهر البصر.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = isAdminDarkTheme()
    val barColor = if (dark) scheme.surfaceContainerHigh else scheme.primary
    val onBarColor = if (dark) scheme.onSurface else scheme.onPrimary
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = barColor,
                    titleContentColor = onBarColor,
                    navigationIconContentColor = onBarColor,
                    actionIconContentColor = onBarColor,
                ),
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/**
 * ألوان الحقول (نظير inputDecorationTheme الأصلي): حاوية بلون سطح متكيّف
 * وحدّ يعبر 3:1. حدّ الحقل عنصر واجهة **وظيفيّ** يخضع لـWCAG 1.4.11 فلا
 * يُترك بلون باهت كما كان (#CCE3E3 بتباين 1.34).
 */
@Composable
fun adminFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = scheme.surfaceContainer,
        unfocusedContainerColor = scheme.surfaceContainer,
        disabledContainerColor = scheme.surfaceContainer,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline,
        disabledBorderColor = scheme.outline,
    )
}

@Composable
fun AdminTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        leadingIcon = leadingIcon,
        shape = RoundedCornerShape(10.dp),
        colors = adminFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/** قائمة منسدلة (نظير DropdownButtonFormField). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> AdminDropdown(
    label: String,
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected?.let(itemLabel).orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(10.dp),
            colors = adminFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(itemLabel(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    },
                )
            }
        }
    }
}

/**
 * مؤشّر دوّار صغير داخل زرّ (نظير _Spin).
 * لونه الافتراضي حبر ما يحتويه لا أبيضَ ثابتاً: داخل زرّ مصمت يساوي
 * `onPrimary` (أبيض في الفاتح كما كان، وحبر داكن فوق ذهب الليل).
 */
@Composable
fun Spin(color: Color = LocalContentColor.current, size: Int = 22) {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        color = color,
        modifier = Modifier.size(size.dp),
    )
}

/** مربّع إحصائيّة (حاوية سطح مرتفعة + رقم كبير بلون اللوحة الأوّل). */
@Composable
fun StatBox(label: String, value: Int, icon: ImageVector, loading: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(108.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        if (loading) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        } else {
            Text(
                "$value",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            color = scheme.primary,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 4.dp, bottom = 6.dp),
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun FullScreenLoader() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/** حوار تأكيد عامّ (تأكيد الحذف/الحظر…). */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    confirmColor: Color = MaterialTheme.colorScheme.primary,
    // ⛔ يُعطَّل حين يتعذّر على المستدعي حساب مدى الفعل (مثل الحذف التعاقبيّ
    // حين يفشل جلب المحتوى): تأكيدٌ على معلومة ناقصة أسوأ من منع التأكيد.
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            androidx.compose.material3.FilledTonalButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = confirmColor,
                    // النصّ الأبيض يسقط فوق الأسطح الفاتحة (كالذهب في الوضع الداكن).
                    contentColor = contentColorOn(confirmColor),
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        },
    )
}

/** حوار تعديل نصّ واحد (نظير _editDialog). */
@Composable
fun EditTextDialog(
    title: String,
    initial: String,
    hint: String = "الاسم الجديد",
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = adminFieldColors(),
            )
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSave(text.trim()) },
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** شارة عدّ فوق أيقونة (نظير Badge.count). */
@Composable
fun CountBadge(count: Int, content: @Composable () -> Unit) {
    if (count <= 0) {
        content()
    } else {
        BadgedBox(badge = { Badge { Text(if (count > 99) "+99" else "$count") } }) { content() }
    }
}

/** صفّ أفقي بمسافة موحّدة. */
@Composable
fun RowSpaced(
    modifier: Modifier = Modifier,
    spacing: Int = 8,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

/** دائرة ملوّنة تحوي أيقونة (نظير CircleAvatar بأيقونة). */
@Composable
fun CircleIcon(icon: ImageVector, background: Color, size: Int = 40, iconSize: Int = 20) {
    Box(
        modifier = Modifier.size(size.dp).background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            // حبر داكن فوق الخلفيّات الفاتحة، وأبيض فوق الداكنة.
            tint = contentColorOn(background),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

@Composable
fun SnackbarScaffoldHost(state: SnackbarHostState) {
    SnackbarHost(state)
}
