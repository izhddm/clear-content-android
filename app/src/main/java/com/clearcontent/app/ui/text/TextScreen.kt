package com.clearcontent.app.ui.text

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearcontent.app.R
import com.clearcontent.app.system.Share
import com.clearcontent.app.ui.Labels
import com.clearcontent.app.ui.TextState
import com.clearcontent.app.ui.theme.LocalStatusColors
import com.clearcontent.app.ui.theme.MonoStyle
import com.clearcontent.core.text.HiddenChar
import com.clearcontent.core.text.TextCleanOptions
import com.clearcontent.core.text.TextCleanResult

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TextScreen(
    input: String,
    state: TextState,
    options: TextCleanOptions,
    contentPadding: PaddingValues,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onOptions: ((TextCleanOptions) -> TextCleanOptions) -> Unit,
) {
    val context = LocalContext.current
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val result = state.result
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 320.dp),
            placeholder = { Text("Вставьте текст из ChatGPT, Gemini, Claude, DeepSeek, Copilot…") },
            label = { Text("Исходный текст") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onPaste) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Вставить")
            }
            if (input.isNotEmpty()) {
                TextButton(onClick = { onInput("") }) {
                    Icon(Icons.Outlined.Clear, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Стереть")
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.hidden.isNotEmpty()) {
                TextButton(onClick = { showHidden = !showHidden }) {
                    Icon(if (showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Скрытые: ${state.hidden.size}")
                }
            }
        }

        AnimatedVisibility(showHidden && state.hidden.isNotEmpty()) {
            HiddenPreview(state.input, state.hidden)
        }

        Text("Дополнительно", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip("Markdown → текст", options.stripMarkdown) { v -> onOptions { it.copy(stripMarkdown = v) } }
            OptionChip("Тире → -", options.normalizeDashes) { v -> onOptions { it.copy(normalizeDashes = v) } }
            OptionChip("“Кавычки” → \"", options.normalizeQuotes) { v -> onOptions { it.copy(normalizeQuotes = v) } }
            OptionChip("… → ...", options.normalizeEllipsis) { v -> onOptions { it.copy(normalizeEllipsis = v) } }
            OptionChip("𝐒𝐭𝐲𝐥𝐞 → Style", options.normalizeFancyLetters) { v -> onOptions { it.copy(normalizeFancyLetters = v) } }
            OptionChip("Сноски [1]", options.removeCitationNumbers) { v -> onOptions { it.copy(removeCitationNumbers = v) } }
            OptionChip("Пустые строки", options.collapseBlankLines) { v -> onOptions { it.copy(collapseBlankLines = v) } }
        }

        if (result != null && input.isNotEmpty()) {
            ResultCard(
                result = result,
                onCopy = onCopy,
                onShare = { Share.shareText(context, result.text) },
            )
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Image(
                        painter = painterResource(R.drawable.illustration_text_empty),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.CenterHorizontally).width(200.dp).height(150.dp),
                    )
                    Text("Что удаляется всегда", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Невидимые символы (U+200B, U+2060, U+FEFF…), узкие неразрывные пробелы U+202F, скрытые теги Unicode, " +
                            "символы управления направлением, служебные символы и остатки цитат ChatGPT (citeturn0search0, 【4:0†source】), " +
                            "метки utm_source=chatgpt.com в ссылках. Эмодзи, флаги и языки с ZWJ не ломаются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionChip(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(selected = selected, onClick = { onChange(!selected) }, label = { Text(label) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(result: TextCleanResult, onCopy: () -> Unit, onShare: () -> Unit) {
    val status = LocalStatusColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = status.success, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (result.changed) "Очищено: ${result.totalChanges}" else "Скрытых меток нет",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (result.changed) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.counts.forEach { (issue, n) ->
                        val hidden = issue in TextCleanResult.HIDDEN_ISSUES
                        Surface(color = if (hidden) status.ai else status.info, shape = MaterialTheme.shapes.small) {
                            Text(
                                "${Labels.textIssue(issue)} ×$n",
                                color = if (hidden) status.onAi else status.onInfo,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (result.hiddenCodePoints.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            result.hiddenCodePoints.entries.joinToString("   ") { (cp, n) -> "U+%04X×%d".format(cp, n) },
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                SelectionContainer {
                    Text(
                        result.text.ifEmpty { "(пусто)" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Копировать")
                }
                FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Поделиться")
                }
            }
        }
    }
}

/** The original text with each hidden character rendered as a visible, highlighted token. */
@Composable
private fun HiddenPreview(input: String, hidden: List<HiddenChar>) {
    val status = LocalStatusColors.current
    val annotated: AnnotatedString = remember(input, hidden) {
        buildAnnotatedString {
            var pos = 0
            val limit = 6000
            for (h in hidden) {
                if (h.start >= limit) break
                append(input, pos, h.start)
                withStyle(SpanStyle(background = status.ai, color = status.onAi, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)) {
                    append("⟨" + shortName(h.codePoint) + "⟩")
                }
                pos = h.end
            }
            append(input, pos, minOf(input.length, maxOf(pos, limit)))
            if (input.length > limit) append("…")
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Скрытые символы в исходном тексте", style = MaterialTheme.typography.labelLarge)
            Text(annotated, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
        }
    }
}

private fun shortName(cp: Int): String = when (cp) {
    0x200B -> "ZWSP"
    0x200C -> "ZWNJ"
    0x200D -> "ZWJ"
    0x200E -> "LRM"
    0x200F -> "RLM"
    0x2060 -> "WJ"
    0xFEFF -> "BOM"
    0x00A0 -> "NBSP"
    0x202F -> "NNBSP"
    0x00AD -> "SHY"
    0x202E -> "RLO"
    0x202D -> "LRO"
    in 0xE0000..0xE007F -> "TAG " + if (cp in 0xE0020..0xE007E) (cp - 0xE0000).toChar().toString() else "%X".format(cp)
    in 0xFE00..0xFE0F, in 0xE0100..0xE01EF -> "VS"
    else -> "U+%04X".format(cp)
}
