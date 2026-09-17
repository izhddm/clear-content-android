package com.clearcontent.app.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.clearcontent.app.R
import com.clearcontent.app.media.CleanMethod
import com.clearcontent.app.media.CleanOutput
import com.clearcontent.app.queue.ItemStatus
import com.clearcontent.app.queue.QueueItem
import com.clearcontent.app.system.Clipboard
import com.clearcontent.app.system.Share
import com.clearcontent.app.ui.Labels
import com.clearcontent.app.ui.theme.LocalStatusColors
import com.clearcontent.app.ui.theme.MonoStyle
import com.clearcontent.core.media.Finding
import com.clearcontent.core.media.FindingKind
import com.clearcontent.core.media.Severity

@Composable
fun MediaScreen(
    items: List<QueueItem>,
    contentPadding: PaddingValues,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onClipboard: () -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit,
    onMessage: (String) -> Unit,
    replaceable: Int = 0,
    replaceMode: Boolean = false,
    onDeleteOriginals: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (items.isEmpty()) {
            item { EmptyHero() }
            item { SourceButtons(onGallery, onFiles, onClipboard, prominent = true) }
            item { QuickTips() }
        } else {
            item { SourceButtons(onGallery, onFiles, onClipboard, prominent = false) }
            item { BatchBar(items, onClear, onMessage, replaceable, onDeleteOriginals) }
            items(items, key = { it.id }) { item ->
                MediaItemCard(item, replaceMode, onRetry = { onRetry(item.id) }, onRemove = { onRemove(item.id) }, onMessage = onMessage)
            }
        }
    }
}

@Composable
private fun EmptyHero() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.illustration_media_empty),
            contentDescription = null,
            modifier = Modifier.width(220.dp).height(165.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Уберите скрытые метки из фото и видео",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Content Credentials (C2PA), IPTC-метка «создано ИИ», XMP, EXIF, GPS и параметры генерации. " +
                "JPEG, PNG, WebP, GIF, MP4 и MOV очищаются без потери качества, HEIC и AVIF перекодируются.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SourceButtons(onGallery: () -> Unit, onFiles: () -> Unit, onClipboard: () -> Unit, prominent: Boolean) {
    val tight = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val height = if (prominent) 52.dp else 44.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (prominent) {
            Button(onClick = onGallery, modifier = Modifier.fillMaxWidth().height(height)) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Выбрать фото и видео")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!prominent) {
                FilledTonalButton(onClick = onGallery, contentPadding = tight, modifier = Modifier.weight(1f).height(height)) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Галерея", maxLines = 1)
                }
            }
            OutlinedButton(onClick = onFiles, contentPadding = tight, modifier = Modifier.weight(1f).height(height)) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Файлы", maxLines = 1)
            }
            OutlinedButton(onClick = onClipboard, contentPadding = tight, modifier = Modifier.weight(1f).height(height)) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (prominent) "Из буфера" else "Буфер", maxLines = 1)
            }
        }
    }
}

@Composable
private fun QuickTips() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Быстрее всего", style = MaterialTheme.typography.titleMedium)
            Tip(Icons.Outlined.Share, "«Поделиться» → Clear Content", "Из галереи, ChatGPT, Gemini или Telegram — файл очистится и сохранится сам.")
            Tip(Icons.Outlined.ContentPaste, "Плитка «Очистить буфер»", "Добавьте её в шторку в настройках: копируете, жмёте плитку, вставляете.")
            Tip(Icons.Outlined.AutoAwesome, "Выделите текст → «Очистить текст»", "Пункт появляется в меню выделения любого приложения.")
        }
    }
}

@Composable
private fun Tip(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BatchBar(
    items: List<QueueItem>,
    onClear: () -> Unit,
    onMessage: (String) -> Unit,
    replaceable: Int,
    onDeleteOriginals: () -> Unit,
) {
    val context = LocalContext.current
    val done = items.filter { it.status == ItemStatus.DONE }
    val active = items.count { it.status == ItemStatus.QUEUED || it.status == ItemStatus.WORKING }
    val withAi = done.count { it.result?.before?.hasAiMarkers == true }
    val outputs = done.mapNotNull { it.result?.output }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active == 0 && done.isNotEmpty()) {
                    Image(
                        painter = painterResource(R.drawable.illustration_done),
                        contentDescription = null,
                        modifier = Modifier.width(72.dp).height(54.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (active > 0) "Обработка: осталось $active" else "Готово: ${done.size} из ${items.size}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (withAi > 0) "ИИ-метки найдены и удалены в $withAi" else "Результаты сохраняются в Pictures/ClearContent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClear, enabled = active < items.size) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Очистить список")
                }
            }
            if (outputs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val mime = Share.mimeFor(outputs.map { it.mimeType })
                            if (!Share.toInstagram(context, outputs.map { it.shareUri }, mime)) onMessage("Instagram не установлен")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (outputs.size > 1) "Instagram (${outputs.size})" else "Instagram")
                    }
                    FilledTonalButton(
                        onClick = { Share.shareMedia(context, outputs.map { it.shareUri }, Share.mimeFor(outputs.map { it.mimeType })) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Поделиться")
                    }
                }
            }
            if (replaceable > 0 && active == 0) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDeleteOriginals) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Удалить оригиналы из галереи ($replaceable)")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaItemCard(item: QueueItem, replaceMode: Boolean, onRetry: () -> Unit, onRemove: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val result = item.result
    val before = result?.before
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(item, result?.output)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        result?.output?.let { out -> out.finalName?.takeIf { item.originalDeleted } ?: out.file.name }
                            ?: item.source.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        buildString {
                            before?.let { append(Labels.format(it.format)).append(" · ") }
                            append(Labels.size(item.source.sizeBytes))
                            result?.output?.let {
                                append(" → ")
                                if (it.format != before?.format) append(Labels.format(it.format)).append(" ")
                                append(Labels.size(it.sizeBytes))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    StatusLine(item)
                }
                if (item.status == ItemStatus.DONE || item.status == ItemStatus.FAILED) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = "Подробнее")
                    }
                }
            }

            if (item.status == ItemStatus.WORKING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            }

            if (before != null) {
                val chips = before.findings
                    .filter { it.kind.severity != Severity.INFO }
                    .distinctBy { Labels.chip(it) }
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.sortedBy { it.kind.severity }.take(10).forEach { FindingChip(it) }
                        if (chips.size > 10) FindingLabel("+${chips.size - 10}", Severity.INFO)
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Меток не было — файл пересохранён на всякий случай",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.status == ItemStatus.FAILED) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Повторить")
                    }
                    TextButton(onClick = onRemove) { Text("Убрать") }
                }
            }

            val output = result?.output
            if (output != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (!Share.toInstagram(context, listOf(output.shareUri), output.mimeType)) onMessage("Instagram не установлен")
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Instagram")
                    }
                    TextButton(onClick = { Share.shareMedia(context, listOf(output.shareUri), output.mimeType) }) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Отправить")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        Clipboard.copyMedia(context, output.shareUri)
                        onMessage("Файл скопирован в буфер")
                    }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать файл") }
                    IconButton(onClick = { Share.view(context, output.galleryUri ?: output.shareUri, output.mimeType) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Открыть")
                    }
                }
            }

            AnimatedVisibility(expanded) {
                Details(item, replaceMode)
            }
        }
    }
}

@Composable
private fun Thumbnail(item: QueueItem, output: CleanOutput?) {
    val isVideo = output?.isVideo ?: (item.source.mimeType?.startsWith("video/") == true)
    Box(
        modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = output?.file ?: item.source.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            error = { Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        if (isVideo) {
            Icon(
                Icons.Outlined.PlayCircle,
                null,
                tint = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(item: QueueItem) {
    val status = LocalStatusColors.current
    when (item.status) {
        ItemStatus.QUEUED -> StatusText("В очереди", MaterialTheme.colorScheme.onSurfaceVariant, null)
        ItemStatus.WORKING -> StatusText((item.stage ?: "Обработка") + "…", MaterialTheme.colorScheme.primary, null)
        ItemStatus.FAILED -> StatusText(item.error ?: "Ошибка", MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)
        ItemStatus.DONE -> {
            val r = item.result ?: return
            val saved = if (r.output.galleryUri != null) " · в галерее" else ""
            StatusText("Чисто · ${Labels.methodShort(r.method)}$saved", status.success, Icons.Outlined.CheckCircle)
        }
    }
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color, icon: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FindingChip(f: Finding) = FindingLabel(Labels.chip(f), f.kind.severity)

@Composable
private fun FindingLabel(text: String, severity: Severity) {
    val c = LocalStatusColors.current
    val (bg, fg) = when (severity) {
        Severity.AI -> c.ai to c.onAi
        Severity.PRIVACY -> c.privacy to c.onPrivacy
        Severity.INFO -> c.info to c.onInfo
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Details(item: QueueItem, replaceMode: Boolean) {
    val result = item.result
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (result == null) {
            Text("Источник: ${item.source.displayName}", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Section("Что было в файле") {
            if (result.before.findings.isEmpty()) {
                Text("Ничего подозрительного", style = MaterialTheme.typography.bodySmall)
            }
            result.before.findings.sortedBy { it.kind.severity }.forEach { f ->
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        Labels.findingTitle(f.kind),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (f.kind.severity == Severity.AI) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    val detail = listOfNotNull(f.location, Labels.findingDetail(f)).joinToString(" · ")
                    Text(detail, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (result.removed.isNotEmpty()) {
            Section("Удалено") {
                Text(
                    result.removed.joinToString("\n") { r ->
                        buildString {
                            append("• ").append(r.location)
                            if (r.bytes > 0) append(" (").append(Labels.size(r.bytes)).append(")")
                        }
                    },
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (result.notes.isNotEmpty()) {
            Section("Примечания") {
                result.notes.distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Section("Проверка результата") {
            val kept = result.after.findings.filter {
                it.kind == FindingKind.ICC_PROFILE || it.kind == FindingKind.ORIENTATION || it.kind == FindingKind.CAPTURE_DATE
            }
            Text(
                "Повторный анализ: меток ИИ и личных данных нет" +
                    if (kept.isNotEmpty()) ". Оставлено: " + kept.joinToString { Labels.findingTitle(it.kind).lowercase() } else "",
                style = MaterialTheme.typography.bodySmall,
                color = LocalStatusColors.current.success,
            )
            result.output.galleryError?.let {
                Text("Не сохранено в галерею: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (result.output.galleryUri != null) {
                val folder = item.source.relativePath?.takeIf { replaceMode && item.source.galleryItem != null }
                    ?: if (result.output.isVideo) "Movies/ClearContent/" else "Pictures/ClearContent/"
                val name = if (item.originalDeleted) result.output.finalName ?: result.output.file.name else result.output.file.name
                Text("Сохранено: $folder$name", style = MaterialTheme.typography.bodySmall)
            }
            when {
                item.originalDeleted -> Text("Оригинал удалён — очищенная копия заняла его место", style = MaterialTheme.typography.bodySmall)
                replaceMode && item.source.galleryItem == null ->
                    Text("Оригинал не из галереи — удалять нечего", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.method != CleanMethod.REENCODED) {
                Text(
                    if (result.output.isVideo) "Видео и звук не перекодировались" else "Пиксели не изменялись",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
