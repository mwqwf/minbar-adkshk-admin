package com.ali.ishaqiyin_admin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ali.ishaqiyin_admin.data.AdminRepository
import com.ali.ishaqiyin_admin.data.Category
import com.ali.ishaqiyin_admin.data.Lesson
import com.ali.ishaqiyin_admin.data.Subcategory
import com.ali.ishaqiyin_admin.data.arabicReason
import kotlinx.coroutines.launch

/**
 * حوار «ماذا تريد أن تفعل بالقسم؟» — يسبق الحذف التعاقبيّ ولا يستبدله.
 *
 * ⚠️ لماذا وُجد: أكثر ما يُسمّيه المشرف «حذف قسم» هو في الحقيقة إعادة
 * تنظيم — يريد الدروس أن تبقى في مكان آخر. الحذف التعاقبيّ كان الطريق
 * الوحيد، فكان يمحو الصوتيّات كلّها بلا رجعة. لذلك صار **نقل الدروس ثمّ
 * حذف القسم فارغاً** هو الخيار الأوّل والأبرز، والحذف بكل شيء خياراً
 * ثانياً أقلّ بروزاً.
 *
 * [loadLessons] تُجلب الدروس **لحظة بدء النقل** لا قبله: القائمة المحفوظة
 * في الشاشة قد تكون قديمة، ونقل قائمة قديمة يترك دروساً في قسم يُظنّ فارغاً.
 */
@Composable
fun SectionDeleteFlowDialog(
    sectionName: String,
    /** true للقسم الرئيسيّ، false للفرعيّ — يغيّر النصوص فقط. */
    isMainCategory: Boolean,
    /** عدد الدروس المعروف عند فتح الحوار (للعرض فقط). */
    knownLessonCount: Int,
    /** ⛔ false = مدى الحذف مجهول، فلا يُتاح الحذف التعاقبيّ. */
    scopeKnown: Boolean,
    categories: List<Category>,
    subcategories: List<Subcategory>,
    /** أقسام فرعيّة لا تصلح وجهةً (القسم نفسه أو فروع القسم المحذوف). */
    excludedSubIds: Set<String>,
    loadLessons: suspend () -> List<Lesson>,
    onDismiss: () -> Unit,
    /** الحذف التعاقبيّ بالمسار الموجود (حوار تأكيد بالأسماء عند المستدعي). */
    onChooseCascade: () -> Unit,
    /** حذف القسم بعد أن صار فارغاً فعلاً. */
    onDeleteEmptied: () -> Unit,
    /** نُقل درس أو أكثر — لتحديث قوائم الشاشة ولو لم يكتمل النقل. */
    onSomethingMoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // خطوات الحوار بالترتيب — لا قوائم منسدلة ولا خيارات متشابكة.
    var step by remember { mutableStateOf(Step.CHOICE) }
    var chosenCategory by remember { mutableStateOf<Category?>(null) }
    var chosenSub by remember { mutableStateOf<Subcategory?>(null) }

    var busy by remember { mutableStateOf(false) }
    var movedCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(knownLessonCount) }
    // ما تعذّر نقله: يبقى في الحالة كي تكون «أعد المحاولة» على ما بقي فقط.
    var remaining by remember { mutableStateOf<List<Lesson>>(emptyList()) }
    var problem by remember { mutableStateOf("") }

    val label = if (isMainCategory) "القسم الرئيسيّ" else "القسم الفرعيّ"

    // الوجهات: كلّ قسم فرعيّ خارج ما سيُحذف — ولا يُعرض رئيسيّ بلا وجهة تحته.
    val destinations = remember(subcategories, excludedSubIds) {
        subcategories.filter { it.id !in excludedSubIds }
    }
    val pickableCategories = remember(categories, destinations) {
        val withDestinations = destinations.map { it.categoryId }.toSet()
        categories.filter { it.id in withDestinations }
    }

    /** ينقل ما في [batch] درساً درساً ويحدّث العدّاد بعد كلّ نجاح. */
    fun runMove(batch: List<Lesson>, target: Subcategory, targetCategoryName: String) {
        busy = true
        problem = ""
        remaining = emptyList()
        step = Step.MOVING
        scope.launch {
            val failed = mutableListOf<Lesson>()
            var failureReason = ""
            for (lesson in batch) {
                val outcome = runCatching {
                    AdminRepository.moveLessonToSubcategory(
                        lesson = lesson,
                        target = target,
                        targetCategoryName = targetCategoryName,
                    )
                }
                if (outcome.isSuccess) {
                    movedCount += 1
                } else {
                    failed += lesson
                    if (failureReason.isEmpty()) {
                        failureReason = outcome.exceptionOrNull()?.arabicReason().orEmpty()
                    }
                }
            }
            if (movedCount > 0) onSomethingMoved()
            busy = false
            if (failed.isEmpty()) {
                // ⛔ الحذف لا يقع إلا بعد نقل الجميع — قسمٌ نصف مفرَّغ
                // يُحذف يعني ضياع ما بقي فيه.
                step = Step.DONE
            } else {
                remaining = failed
                problem = failureReason
                step = Step.PARTIAL
            }
        }
    }

    /** يجلب الدروس الطازجة ثمّ يبدأ النقل. */
    fun startMove(target: Subcategory, targetCategoryName: String) {
        busy = true
        problem = ""
        movedCount = 0
        step = Step.MOVING
        scope.launch {
            val fresh = runCatching { loadLessons() }
            if (fresh.isFailure) {
                busy = false
                problem = fresh.exceptionOrNull()?.arabicReason().orEmpty()
                remaining = emptyList()
                step = Step.LOAD_FAILED
                return@launch
            }
            val list = fresh.getOrDefault(emptyList())
            totalCount = list.size
            if (list.isEmpty()) {
                // القسم فارغ أصلاً — لا نقل، والحذف بلا خطر.
                busy = false
                step = Step.DONE
                return@launch
            }
            runMove(list, target, targetCategoryName)
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(0.94f).padding(vertical = 24.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // رجوع خطوة واحدة بدل إغلاق كلّ شيء وإعادة البدء.
                    if (!busy && (step == Step.PICK_CATEGORY || step == Step.PICK_SUB ||
                            step == Step.REVIEW)
                    ) {
                        IconButton(
                            onClick = {
                                when (step) {
                                    Step.PICK_CATEGORY -> step = Step.CHOICE
                                    Step.PICK_SUB -> {
                                        chosenCategory = null
                                        step = Step.PICK_CATEGORY
                                    }
                                    else -> {
                                        chosenSub = null
                                        step = Step.PICK_SUB
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(
                        sectionName.ifBlank { "بدون اسم" },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))

                when (step) {
                    Step.CHOICE -> {
                        Text(
                            // العدد لا يُذكر إن كان مجهولاً: «0 دروس» كاذبة تطمئن
                            // المشرف على قسم مليء.
                            if (scopeKnown) {
                                "فيه ${lessonsCountLabel(knownLessonCount)}. ماذا تريد؟"
                            } else {
                                "لم نستطع معرفة عدد دروسه. ماذا تريد؟"
                            },
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        // الخيار الأوّل والأبرز: لا يضيع صوت ولا نصّ مشروح.
                        BigChoiceCard(
                            title = "انقل دروسه إلى قسم آخر ثمّ احذف $label فارغاً",
                            subtitle = "الدروس تبقى كما هي: الصوت والنصّ المشروح " +
                                "وعدد الاستماع لا يضيع منها شيء.",
                            highlighted = true,
                            enabled = destinations.isNotEmpty(),
                            onClick = { step = Step.PICK_CATEGORY },
                        )
                        if (destinations.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "لا يوجد قسم آخر تُنقل إليه الدروس. أنشئ قسماً " +
                                    "فرعيّاً جديداً أوّلاً من شاشة «إدارة الأقسام».",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        // الخيار الثاني، أقلّ بروزاً: نصّ لا بطاقة.
                        TextButton(
                            onClick = onChooseCascade,
                            enabled = scopeKnown,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "احذف $label ودروسه كلّها نهائيّاً",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!scopeKnown) {
                            Text(
                                "لم نستطع معرفة ما سيُحذف بالضبط — الحذف مغلق " +
                                    "حتى ينجح تحميل المحتوى.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Step.PICK_CATEGORY -> {
                        StepHint("الخطوة 1 من 2 — اختر القسم الرئيسيّ الذي تُنقل إليه الدروس")
                        LazyColumn(
                            Modifier.heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(pickableCategories.size) { i ->
                                val c = pickableCategories[i]
                                val count = destinations.count { it.categoryId == c.id }
                                BigChoiceCard(
                                    title = c.name.ifBlank { "بدون اسم" },
                                    subtitle = arabicCount(
                                        count,
                                        "قسم فرعيّ واحد",
                                        "قسمان فرعيّان",
                                        "أقسام فرعيّة",
                                        "قسماً فرعيّاً",
                                    ),
                                    onClick = { chosenCategory = c; step = Step.PICK_SUB },
                                )
                            }
                        }
                    }

                    Step.PICK_SUB -> {
                        StepHint("الخطوة 2 من 2 — اختر القسم الفرعيّ")
                        val subs = destinations.filter { it.categoryId == chosenCategory?.id }
                        LazyColumn(
                            Modifier.heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(subs.size) { i ->
                                val s = subs[i]
                                BigChoiceCard(
                                    title = s.name.ifBlank { "بدون اسم" },
                                    subtitle = chosenCategory?.name.orEmpty(),
                                    onClick = { chosenSub = s; step = Step.REVIEW },
                                )
                            }
                        }
                    }

                    Step.REVIEW -> {
                        StepHint("راجِع قبل التأكيد")
                        Text(
                            "تُنقل دروس «${sectionName.ifBlank { "بدون اسم" }}» كلّها " +
                                "إلى «${chosenCategory?.name.orEmpty()} ← " +
                                "${chosenSub?.name.orEmpty()}»، ثمّ يُحذف $label " +
                                "بعد أن يصير فارغاً.",
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "النقل يأخذ وقتاً على الإنترنت الضعيف — لا تغلق " +
                                "الشاشة حتى ينتهي.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Step.MOVING -> {
                        StepHint("جارٍ النقل…")
                        Text(
                            "نُقل $movedCount من $totalCount",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "لا تغلق الشاشة. لا يضيع شيء إن انقطع الإنترنت — " +
                                "ما لم يُنقل يبقى في مكانه.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Step.PARTIAL -> {
                        StepHint("لم يكتمل النقل")
                        Text(
                            "نُقل $movedCount من $totalCount. " +
                                "بقي ${lessonsCountLabel(remaining.size)} في مكانه.",
                            fontSize = 15.sp,
                        )
                        if (problem.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "السبب: $problem",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // ⛔ القسم لم يُحذف: حذفه الآن يمحو ما بقي فيه.
                        Text(
                            "لم يُحذف $label. تحقّق من الإنترنت وأعد المحاولة " +
                                "على ما بقي.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Step.LOAD_FAILED -> {
                        StepHint("تعذّر تحميل الدروس")
                        Text(
                            "لم نستطع قراءة دروس $label، فلم يُنقل شيء ولم " +
                                "يُحذف شيء." + if (problem.isNotBlank()) " السبب: $problem" else "",
                            fontSize = 15.sp,
                        )
                    }

                    Step.DONE -> {
                        StepHint("تمّ النقل")
                        Text(
                            if (totalCount == 0) {
                                "$label فارغ — يمكن حذفه الآن بلا فقد أيّ درس."
                            } else {
                                "نُقل ${lessonsCountLabel(totalCount)} بنجاح. " +
                                    "$label صار فارغاً — احذفه الآن."
                            },
                            fontSize = 15.sp,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (step == Step.DONE || step == Step.PARTIAL) "إغلاق" else "إلغاء")
                    }
                    Spacer(Modifier.size(8.dp))
                    when (step) {
                        Step.REVIEW -> PrimaryAction("ابدأ النقل") {
                            val sub = chosenSub ?: return@PrimaryAction
                            startMove(sub, chosenCategory?.name.orEmpty())
                        }

                        Step.PARTIAL -> PrimaryAction("أعد المحاولة على ما بقي") {
                            val sub = chosenSub ?: return@PrimaryAction
                            totalCount = movedCount + remaining.size
                            runMove(remaining, sub, chosenCategory?.name.orEmpty())
                        }

                        Step.LOAD_FAILED -> PrimaryAction("أعد المحاولة") {
                            val sub = chosenSub ?: return@PrimaryAction
                            startMove(sub, chosenCategory?.name.orEmpty())
                        }

                        Step.DONE -> PrimaryAction("احذف $label الفارغ", danger = true) {
                            onDeleteEmptied()
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}

private enum class Step { CHOICE, PICK_CATEGORY, PICK_SUB, REVIEW, MOVING, PARTIAL, LOAD_FAILED, DONE }

@Composable
private fun PrimaryAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val color =
        if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color,
            contentColor = contentColorOn(color),
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(text) }
}

@Composable
private fun StepHint(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * بطاقة اختيار كبيرة (٦٤dp فأكثر): الإصبع لا يُخطئها، والاسم كامل.
 * [highlighted] تجعلها الخيار المقصود بصريّاً بلا كلمة زائدة.
 */
@Composable
private fun BigChoiceCard(
    title: String,
    subtitle: String,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (highlighted) {
                Icon(
                    Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = if (highlighted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
