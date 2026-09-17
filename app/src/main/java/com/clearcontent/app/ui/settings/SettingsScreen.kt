package com.clearcontent.app.ui.settings

import android.app.StatusBarManager
import android.content.Intent
import android.net.Uri
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clearcontent.app.BuildConfig
import com.clearcontent.app.R
import com.clearcontent.app.media.GalleryOriginals
import com.clearcontent.app.settings.AppSettings
import com.clearcontent.app.settings.ImageMode
import com.clearcontent.app.settings.OutputFormat
import com.clearcontent.app.settings.ShareFlow
import com.clearcontent.app.ui.CleanClipboardTileService
import com.clearcontent.app.ui.Labels
import com.clearcontent.core.text.TextCleanOptions
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val t = settings.text
    fun text(transform: (TextCleanOptions) -> TextCleanOptions) = onUpdate { it.copy(text = transform(it.text)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Group("Быстрый доступ") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Плитка «Очистить буфер» в шторке чистит скопированный текст или картинку одним нажатием.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = { requestTile(context, onMessage) }) {
                        Icon(Icons.Outlined.AddBox, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить плитку в шторку")
                    }
                    Text(
                        "Также: «Поделиться» → Clear Content, выделение текста → «Очистить текст», долгое нажатие на иконку приложения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Group("Сохранение и отправка") {
                SwitchRow("Сохранять в галерею", "Pictures/ClearContent и Movies/ClearContent", settings.autoSaveToGallery) { v ->
                    onUpdate { it.copy(autoSaveToGallery = v, replaceOriginals = it.replaceOriginals && v) }
                }
                SwitchRow(
                    "Заменять оригиналы в галерее",
                    if (GalleryOriginals.supported) {
                        "Копия встаёт на место исходника: тот же альбом и дата съёмки (из метаданных остаётся только она), исходник удаляется после подтверждения — без дублей"
                    } else {
                        "Доступно на Android 11 и новее"
                    },
                    settings.replaceOriginals,
                    enabled = GalleryOriginals.supported,
                ) { v -> onUpdate { it.copy(replaceOriginals = v, autoSaveToGallery = it.autoSaveToGallery || v) } }
                SwitchRow("Нейтральные имена файлов", "IMG_20260917_120000.jpg вместо «ChatGPT Image…»", settings.neutralFileNames) { v ->
                    onUpdate { it.copy(neutralFileNames = v) }
                }
                ChoiceRow("После «Поделиться»", settings.shareFlow, ShareFlow.entries, Labels::shareFlow) { v ->
                    onUpdate { it.copy(shareFlow = v) }
                }
            }
        }
        item {
            Group("Фото") {
                ChoiceRow("Режим очистки", settings.imageMode, ImageMode.entries, Labels::imageMode) { v -> onUpdate { it.copy(imageMode = v) } }
                ChoiceRow("Формат результата", settings.outputFormat, OutputFormat.entries, Labels::outputFormat) { v ->
                    onUpdate { it.copy(outputFormat = v) }
                }
                ChoiceRow("Макс. размер", settings.maxDimension, AppSettings.MAX_DIMENSIONS, Labels::maxDimension) { v ->
                    onUpdate { it.copy(maxDimension = v) }
                }
                QualityRow(settings.jpegQuality) { v -> onUpdate { it.copy(jpegQuality = v) } }
                SwitchRow("Сохранять цветовой профиль", "Точные цвета для фото в Display P3. Профиль не содержит меток", settings.keepColorProfile) { v ->
                    onUpdate { it.copy(keepColorProfile = v) }
                }
                SwitchRow("Сохранять ориентацию", "Оставляет только тег поворота, чтобы фото не легло набок", settings.keepOrientation) { v ->
                    onUpdate { it.copy(keepOrientation = v) }
                }
            }
        }
        item {
            Group("Видео") {
                SwitchRow(
                    "Глубокая очистка",
                    "Пересобирать контейнер из аудио и видео дорожек. Медленнее, но убирает и служебные дорожки",
                    settings.deepVideoClean,
                ) { v -> onUpdate { it.copy(deepVideoClean = v) } }
            }
        }
        item {
            Group("Текст") {
                SwitchRow("Скрытые символы", "Zero-width, BOM, теги Unicode, bidi, служебные символы", t.removeInvisible) { v -> text { it.copy(removeInvisible = v) } }
                SwitchRow("Особые пробелы", "U+202F, U+00A0 и другие → обычный пробел", t.normalizeSpaces) { v -> text { it.copy(normalizeSpaces = v) } }
                SwitchRow("Артефакты цитат ИИ", "citeturn…, 【4:0†source】", t.removeAiArtifacts) { v -> text { it.copy(removeAiArtifacts = v) } }
                SwitchRow("Метки ИИ в ссылках", "utm_source=chatgpt.com, perplexity, copilot…", t.removeTrackingParams) { v -> text { it.copy(removeTrackingParams = v) } }
                SwitchRow("Markdown → простой текст", "Instagram не показывает **жирный** и # заголовки", t.stripMarkdown) { v -> text { it.copy(stripMarkdown = v) } }
                SwitchRow("Длинные тире → дефис", null, t.normalizeDashes) { v -> text { it.copy(normalizeDashes = v) } }
                SwitchRow("Типографские кавычки → прямые", "«Ёлочки» не трогаются", t.normalizeQuotes) { v -> text { it.copy(normalizeQuotes = v) } }
                SwitchRow("Многоточие … → ...", null, t.normalizeEllipsis) { v -> text { it.copy(normalizeEllipsis = v) } }
                SwitchRow("Стилизованные буквы → обычные", "𝐁𝐨𝐥𝐝, ｆｕｌｌｗｉｄｔｈ, ⓒⓘⓡⓒⓛⓔ", t.normalizeFancyLetters) { v -> text { it.copy(normalizeFancyLetters = v) } }
                SwitchRow("Удалять сноски [1]", "Номера источников Perplexity/Copilot", t.removeCitationNumbers) { v -> text { it.copy(removeCitationNumbers = v) } }
                SwitchRow("Убирать лишние пробелы", "В концах строк и по краям текста", t.trimLines) { v -> text { it.copy(trimLines = v) } }
                SwitchRow("Сжимать пустые строки", "Не больше одной пустой строки подряд", t.collapseBlankLines) { v -> text { it.copy(collapseBlankLines = v) } }
                SwitchRow("Копировать результат автоматически", "Когда текст пришёл через «Поделиться»", settings.textAutoCopy) { v ->
                    onUpdate { it.copy(textAutoCopy = v) }
                }
            }
        }
        item {
            Group("О приложении") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Clear Content ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Открытый исходный код, лицензия Apache-2.0",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("github.com/izhddm/clear-content-android") }
                    Text(
                        "Работает полностью офлайн: у приложения нет разрешения на доступ в интернет.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Удаляются метаданные файла (C2PA, IPTC, XMP, EXIF, GPS, параметры генерации, метаданные видео) " +
                            "и скрытые символы в тексте. Водяные знаки, встроенные в сами пиксели или слова (например, SynthID), " +
                            "приложение не удаляет и не изменяет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val SOURCE_URL = "https://github.com/izhddm/clear-content-android"

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp),
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
            Column { content() }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, value: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(label(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = option == value, role = Role.RadioButton) {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun QualityRow(quality: Int, onChange: (Int) -> Unit) {
    var local by remember(quality) { mutableFloatStateOf(quality.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Качество при перекодировании", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${local.roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.roundToInt()) },
            valueRange = 70f..100f,
            steps = 29,
        )
    }
}

private fun requestTile(context: Context, onMessage: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onMessage("Откройте шторку → карандаш → перетащите плитку «Очистить буфер»")
        return
    }
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
    sbm.requestAddTileService(
        ComponentName(context, CleanClipboardTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Плитка добавлена"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Плитка уже в шторке"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Плитка не добавлена"
            else -> "Добавьте плитку вручную: шторка → карандаш"
        }
        onMessage(message)
    }
}
