package com.clearcontent.app.ui

import com.clearcontent.app.media.CleanMethod
import com.clearcontent.app.settings.ImageMode
import com.clearcontent.app.settings.OutputFormat
import com.clearcontent.app.settings.ShareFlow
import com.clearcontent.core.media.BlockType
import com.clearcontent.core.media.Finding
import com.clearcontent.core.media.FindingKind
import com.clearcontent.core.media.MediaFormat
import com.clearcontent.core.text.TextCleanResult
import com.clearcontent.core.text.TextIssue
import java.util.Locale

/** User-facing Russian wording, kept in one place. */
object Labels {

    fun findingTitle(kind: FindingKind): String = when (kind) {
        FindingKind.C2PA -> "Content Credentials (C2PA)"
        FindingKind.AI_SOURCE_TYPE -> "Метка ИИ (IPTC Digital Source Type)"
        FindingKind.AI_GENERATOR -> "Следы генератора"
        FindingKind.AI_PARAMETERS -> "Параметры генерации (промпт)"
        FindingKind.GPS -> "Геолокация"
        FindingKind.CAMERA -> "Модель устройства"
        FindingKind.EXIF -> "EXIF"
        FindingKind.XMP -> "XMP"
        FindingKind.IPTC -> "IPTC / Photoshop"
        FindingKind.THUMBNAIL -> "Встроенная миниатюра"
        FindingKind.COMMENT -> "Комментарий"
        FindingKind.TEXT -> "Текстовые поля"
        FindingKind.CONTAINER_METADATA -> "Метаданные контейнера"
        FindingKind.TRAILING_DATA -> "Данные после конца файла"
        FindingKind.UNKNOWN_BLOCK -> "Неизвестный блок данных"
        FindingKind.EXTRA_IMAGE -> "Доп. кадры (HDR, глубина)"
        FindingKind.METADATA_TRACK -> "Служебная дорожка"
        FindingKind.ICC_PROFILE -> "Цветовой профиль"
        FindingKind.ORIENTATION -> "Ориентация"
        FindingKind.CAPTURE_DATE -> "Дата съёмки"
        FindingKind.TIMESTAMP -> "Дата и время"
    }

    fun sourceType(value: String?): String = when (value) {
        "trainedAlgorithmicMedia" -> "создано ИИ"
        "compositeWithTrainedAlgorithmicMedia" -> "изменено ИИ"
        "algorithmicMedia" -> "создано алгоритмом"
        "compositeSynthetic" -> "синтетический монтаж"
        "algorithmicallyEnhanced" -> "улучшено алгоритмом"
        "dataDrivenMedia" -> "создано по данным"
        else -> value ?: "ИИ"
    }

    fun chip(f: Finding): String = when (f.kind) {
        FindingKind.C2PA -> "C2PA"
        FindingKind.AI_SOURCE_TYPE -> "ИИ: " + sourceType(f.detail)
        FindingKind.AI_GENERATOR -> f.detail ?: "Генератор"
        FindingKind.AI_PARAMETERS -> "Промпт"
        FindingKind.GPS -> "GPS"
        FindingKind.CAMERA -> "Устройство"
        FindingKind.EXIF -> "EXIF"
        FindingKind.XMP -> "XMP"
        FindingKind.IPTC -> "IPTC"
        FindingKind.THUMBNAIL -> "Миниатюра"
        FindingKind.COMMENT -> "Комментарий"
        FindingKind.TEXT -> "Текст"
        FindingKind.CONTAINER_METADATA -> "Метаданные"
        FindingKind.TRAILING_DATA -> "Хвост файла"
        FindingKind.UNKNOWN_BLOCK -> "Блок ${f.location}"
        FindingKind.EXTRA_IMAGE -> "HDR/доп. кадры"
        FindingKind.METADATA_TRACK -> "Служебная дорожка"
        FindingKind.ICC_PROFILE -> "ICC"
        FindingKind.ORIENTATION -> "Поворот"
        FindingKind.CAPTURE_DATE -> "Дата съёмки"
        FindingKind.TIMESTAMP -> "Дата"
    }

    fun findingDetail(f: Finding): String? = when (f.kind) {
        FindingKind.AI_SOURCE_TYPE -> "${sourceType(f.detail)} (${f.detail})"
        else -> f.detail
    }

    fun blockType(type: BlockType): String = when (type) {
        BlockType.EXIF -> "EXIF"
        BlockType.XMP -> "XMP"
        BlockType.IPTC -> "IPTC"
        BlockType.C2PA -> "C2PA"
        BlockType.ICC -> "ICC"
        BlockType.COMMENT -> "Комментарий"
        BlockType.TEXT -> "Текст"
        BlockType.EXTRA_IMAGE -> "Доп. кадры"
        BlockType.THUMBNAIL -> "Миниатюра"
        BlockType.TRAILER -> "Хвост"
        BlockType.CONTAINER_META -> "Метаданные"
        BlockType.TIMESTAMP -> "Время"
        BlockType.UNKNOWN -> "Блок"
    }

    fun method(m: CleanMethod): String = when (m) {
        CleanMethod.LOSSLESS -> "без потери качества"
        CleanMethod.REENCODED -> "перекодировано"
        CleanMethod.REMUXED -> "перемуксировано"
    }

    fun methodShort(m: CleanMethod): String = when (m) {
        CleanMethod.LOSSLESS -> "без потерь"
        CleanMethod.REENCODED -> "перекодировано"
        CleanMethod.REMUXED -> "пересобрано"
    }

    fun format(f: MediaFormat): String = when (f) {
        MediaFormat.MATROSKA -> "WebM"
        MediaFormat.HEIF -> "HEIC"
        else -> f.name
    }

    fun imageMode(m: ImageMode) = when (m) {
        ImageMode.SMART -> "Умный: без потерь, где возможно"
        ImageMode.REENCODE -> "Всегда перекодировать"
    }

    fun outputFormat(f: OutputFormat) = when (f) {
        OutputFormat.ORIGINAL -> "Как у оригинала (HEIC/AVIF → JPEG)"
        OutputFormat.JPEG -> "JPEG"
        OutputFormat.PNG -> "PNG"
        OutputFormat.WEBP -> "WebP"
    }

    fun shareFlow(f: ShareFlow) = when (f) {
        ShareFlow.SHOW_RESULT -> "Показать результат"
        ShareFlow.SHARE_SHEET -> "Сразу открыть «Поделиться»"
        ShareFlow.INSTAGRAM -> "Сразу отправить в Instagram"
    }

    fun maxDimension(px: Int) = if (px == 0) "Оригинал" else "$px px по длинной стороне"

    fun textIssue(issue: TextIssue): String = when (issue) {
        TextIssue.INVISIBLE -> "Невидимые символы"
        TextIssue.BIDI -> "Управление направлением"
        TextIssue.TAG_CHARS -> "Скрытые теги Unicode"
        TextIssue.VARIATION_SELECTORS -> "Селекторы вариантов"
        TextIssue.SPECIAL_SPACES -> "Особые пробелы"
        TextIssue.CONTROL -> "Управляющие символы"
        TextIssue.PRIVATE_USE -> "Служебные символы"
        TextIssue.AI_CITATIONS -> "Артефакты ссылок ИИ"
        TextIssue.TRACKING -> "Метки ИИ в ссылках"
        TextIssue.CITATION_NUMBERS -> "Сноски [1]"
        TextIssue.MARKDOWN -> "Markdown"
        TextIssue.DASHES -> "Тире"
        TextIssue.QUOTES -> "Кавычки"
        TextIssue.ELLIPSIS -> "Многоточия"
        TextIssue.FANCY_LETTERS -> "Стилизованные буквы"
        TextIssue.WHITESPACE -> "Лишние пробелы"
    }

    fun textSummary(r: TextCleanResult): String {
        if (!r.changed) return "Скрытых меток не найдено"
        val hidden = r.hiddenCount
        val parts = buildList {
            if (hidden > 0) add("скрытых символов: $hidden")
            r.counts.filterKeys { it !in TextCleanResult.HIDDEN_ISSUES }.forEach { (k, v) -> add("${lowerFirst(textIssue(k))}: $v") }
        }
        return parts.joinToString(", ").replaceFirstChar { it.titlecase() }
    }

    /** "Артефакты ссылок ИИ" → "артефакты ссылок ИИ", while "Markdown" stays as is. */
    private fun lowerFirst(s: String): String =
        if (s.length > 1 && s[1].isLowerCase() && s[0] in 'А'..'Я') s.replaceFirstChar { it.lowercase() } else s

    fun size(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "—"
        val units = listOf("Б", "КБ", "МБ", "ГБ")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$bytes ${units[0]}" else String.format(Locale.forLanguageTag("ru"), "%.1f %s", v, units[i])
    }
}
