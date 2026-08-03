package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.LowPriority
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Lesson
import kotlinx.coroutines.launch

/** أول رقم في العنوان (يفهم الأرقام العربية-الهندية ٠-٩) أو null. */
private fun titleNumber(title: String): Long? {
    val normalized = title.map { ch ->
        if (ch in '٠'..'٩') ('0' + (ch - '٠')) else ch
    }.joinToString("")
    return Regex("\\d+").find(normalized)?.value?.toLongOrNull()
}

/**
 * 🔀 إعادة ترتيب دروس قسم فرعي — شاشة كاملة:
 * أسهم فورية، نقل لأعلى/أسفل القائمة، نقل لموضع رقمي محدد، و«ترتيب آلي
 * بالأرقام» يفرز بحسب أول رقم في العنوان (يبقى المعاينة قابلة للتعديل
 * قبل الحفظ). الحفظ يعيد توزيع طوابع الإنشاء على الخادم فيصح الترتيبان
 * (الأقدم/الأحدث) في التطبيق العام كما هما.
 */
@androidx.compose.runtime.Composable
@Suppress("FunctionName")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ReorderLessonsDialog(
    subcategoryId: String,
    subcategoryName: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    // نفس قاعدة AdminScaffold: شريط علويّ بلون اللوحة الأوّل في الفاتح،
    // وحاوية سطح مرتفعة في الداكن — شريط ذهبيّ كامل يبهر البصر هناك.
    val scheme = MaterialTheme.colorScheme
    val dark = isAdminDarkTheme()
    val barColor = if (dark) scheme.surfaceContainerHigh else scheme.primary
    val onBarColor = if (dark) scheme.onSurface else scheme.onPrimary

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableIntStateOf(-1) }
    val items = remember { mutableStateListOf<Lesson>() }
    val listState = rememberLazyListState()

    LaunchedEffect(subcategoryId) {
        // استعلام مقيَّد بالقسم الفرعي بدل جلب **كلّ** دروس القاعدة ثم
        // ترشيحها على الجهاز. الفرز يبقى محليّاً فلا يلزم فهرس مركّب.
        runCatching { AdminRepository.fetchSubcategoryLessons(subcategoryId) }
            .onSuccess { found ->
                items.clear()
                // ترتيب العرض = ترتيب التطبيق الافتراضي (الأقدم أولاً).
                items.addAll(found.sortedBy { it.createdAtMs })
            }
            .onFailure { snack("تعذّر جلب دروس القسم.") }
        loading = false
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices) return
        val bounded = to.coerceIn(0, items.lastIndex)
        val item = items.removeAt(from)
        items.add(bounded, item)
        dirty = true
        scope.launch { listState.animateScrollToItem(bounded.coerceAtLeast(0)) }
    }

    if (moveTarget >= 0) {
        var positionText by remember(moveTarget) {
            mutableStateOf("${moveTarget + 1}")
        }
        AlertDialog(
            onDismissRequest = { moveTarget = -1 },
            title = { Text("نقل إلى الموضع…") },
            text = {
                Column {
                    Text(
                        "«${items.getOrNull(moveTarget)?.title.orEmpty()}»\n" +
                            "أدخل رقم الموضع الجديد (1 إلى ${items.size}).",
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.size(8.dp))
                    AdminTextField(
                        value = positionText,
                        onValueChange = { text ->
                            positionText = text.filter { it.isDigit() }.take(4)
                        },
                        label = "الموضع",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = (positionText.toIntOrNull() ?: 0) - 1
                        val from = moveTarget
                        moveTarget = -1
                        if (target in items.indices) {
                            move(from, target)
                        } else {
                            snack("أدخل موضعاً بين 1 و${items.size}.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                ) { Text("نقل") }
            },
            dismissButton = {
                TextButton(onClick = { moveTarget = -1 }) { Text("إلغاء") }
            },
        )
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("إعادة ترتيب الدروس", fontSize = 17.sp)
                            Text(
                                subcategoryName,
                                fontSize = 12.sp,
                                color = onBarColor.copy(alpha = 0.85f),
                                maxLines = 1,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (!saving) onDismiss() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = onBarColor,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = barColor,
                        titleContentColor = onBarColor,
                    ),
                )
            },
            containerColor = scheme.background,
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxHeight()) {
                if (loading) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = scheme.primary) }
                    return@Column
                }
                if (items.size < 2) {
                    Box(
                        Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "هذا القسم فيه أقل من درسين — لا شيء يُرتَّب.",
                            textAlign = TextAlign.Center,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }
                Text(
                    "استعمل الأسهم أو قائمة النقل لوضع كل درس في مكانه، أو " +
                        "جرّب الترتيب الآلي بالأرقام — لا يُحفظ شيء قبل ضغط «حفظ الترتيب».",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OutlinedButton(
                    onClick = {
                        // فرز مستقر: أصحاب الأرقام تصاعدياً، ومن بلا رقم يبقى
                        // في ذيل القائمة محافظاً على ترتيبه النسبي.
                        val sorted = items.withIndex().sortedWith(
                            compareBy(
                                { (_, lesson) -> titleNumber(lesson.title) == null },
                                { (_, lesson) -> titleNumber(lesson.title) ?: Long.MAX_VALUE },
                                { (index, _) -> index },
                            ),
                        ).map { it.value }
                        if (sorted.map { it.id } == items.map { it.id }) {
                            snack("الترتيب الرقمي سليم أصلاً — لا تغيير.")
                        } else {
                            items.clear()
                            items.addAll(sorted)
                            dirty = true
                            snack("رُتّبت بالأرقام — راجع ثم احفظ.")
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Icon(
                        Icons.Filled.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("ترتيب آلي بحسب الرقم في العنوان")
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items.size, key = { items[it].id }) { index ->
                        val lesson = items[index]
                        var menuOpen by remember(lesson.id) { mutableStateOf(false) }
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = scheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .background(
                                            scheme.primary.copy(alpha = 0.12f),
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${index + 1}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.primary,
                                    )
                                }
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    lesson.title.ifEmpty { "بدون عنوان" },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp,
                                )
                                IconButton(
                                    onClick = { move(index, index - 1) },
                                    enabled = !saving && index > 0,
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = "أعلى",
                                        modifier = Modifier.size(18.dp),
                                        tint = scheme.primary,
                                    )
                                }
                                IconButton(
                                    onClick = { move(index, index + 1) },
                                    enabled = !saving && index < items.lastIndex,
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowDownward,
                                        contentDescription = "أسفل",
                                        modifier = Modifier.size(18.dp),
                                        tint = scheme.primary,
                                    )
                                }
                                Box {
                                    IconButton(
                                        onClick = { menuOpen = true },
                                        enabled = !saving,
                                    ) {
                                        Icon(
                                            Icons.Filled.LowPriority,
                                            contentDescription = "نقل إلى…",
                                            modifier = Modifier.size(18.dp),
                                            tint = scheme.onSurfaceVariant,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("إلى أعلى القائمة") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.VerticalAlignTop,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                menuOpen = false
                                                move(index, 0)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("إلى أسفل القائمة") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.VerticalAlignBottom,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                menuOpen = false
                                                move(index, items.lastIndex)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("إلى موضع محدد…") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.LowPriority,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                menuOpen = false
                                                moveTarget = index
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            runCatching {
                                AdminRepository.reorderSubcategoryLessons(
                                    subcategoryId,
                                    items.map { it.id },
                                )
                            }.onSuccess {
                                snack("حُفظ الترتيب الجديد. ✅")
                                onSaved()
                                onDismiss()
                            }.onFailure {
                                snack(it.message ?: "تعذّر حفظ الترتيب.")
                            }
                            saving = false
                        }
                    },
                    enabled = !saving && dirty,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            // حبر الزرّ المصمت لا أبيضَ ثابتاً.
                            color = scheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("جارٍ الحفظ…")
                    } else {
                        Text(if (dirty) "حفظ الترتيب" else "لا تغيير بعد")
                    }
                }
            }
        }
    }
}
