package com.clearcontent.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clearcontent.core.text.TextCleanOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ImageMode { SMART, REENCODE }

enum class OutputFormat { ORIGINAL, JPEG, PNG, WEBP }

enum class ShareFlow { SHOW_RESULT, SHARE_SHEET, INSTAGRAM }

data class AppSettings(
    val imageMode: ImageMode = ImageMode.SMART,
    val outputFormat: OutputFormat = OutputFormat.ORIGINAL,
    val jpegQuality: Int = 95,
    val maxDimension: Int = 0,
    val keepColorProfile: Boolean = true,
    val keepOrientation: Boolean = true,
    val deepVideoClean: Boolean = false,
    val autoSaveToGallery: Boolean = true,
    val neutralFileNames: Boolean = true,
    /** Save the cleaned copy next to the original (same album and date) and delete the original after confirmation. */
    val replaceOriginals: Boolean = false,
    val shareFlow: ShareFlow = ShareFlow.SHOW_RESULT,
    val text: TextCleanOptions = TextCleanOptions(stripMarkdown = true),
    val textAutoCopy: Boolean = true,
) {
    companion object {
        val MAX_DIMENSIONS = listOf(0, 4096, 2160, 1440, 1080)
        val DEFAULT = AppSettings()
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val imageMode = stringPreferencesKey("image_mode")
        val outputFormat = stringPreferencesKey("output_format")
        val jpegQuality = intPreferencesKey("jpeg_quality")
        val maxDimension = intPreferencesKey("max_dimension")
        val keepColorProfile = booleanPreferencesKey("keep_color_profile")
        val keepOrientation = booleanPreferencesKey("keep_orientation")
        val deepVideoClean = booleanPreferencesKey("deep_video_clean")
        val autoSave = booleanPreferencesKey("auto_save")
        val neutralNames = booleanPreferencesKey("neutral_names")
        val replaceOriginals = booleanPreferencesKey("replace_originals")
        val shareFlow = stringPreferencesKey("share_flow")
        val textAutoCopy = booleanPreferencesKey("text_auto_copy")
        val removeInvisible = booleanPreferencesKey("t_invisible")
        val normalizeSpaces = booleanPreferencesKey("t_spaces")
        val removeAiArtifacts = booleanPreferencesKey("t_artifacts")
        val removeTracking = booleanPreferencesKey("t_tracking")
        val removeCitationNumbers = booleanPreferencesKey("t_citation_numbers")
        val stripMarkdown = booleanPreferencesKey("t_markdown")
        val dashes = booleanPreferencesKey("t_dashes")
        val quotes = booleanPreferencesKey("t_quotes")
        val ellipsis = booleanPreferencesKey("t_ellipsis")
        val fancy = booleanPreferencesKey("t_fancy")
        val trimLines = booleanPreferencesKey("t_trim")
        val collapseBlank = booleanPreferencesKey("t_collapse")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[Keys.imageMode] = s.imageMode.name
            p[Keys.outputFormat] = s.outputFormat.name
            p[Keys.jpegQuality] = s.jpegQuality
            p[Keys.maxDimension] = s.maxDimension
            p[Keys.keepColorProfile] = s.keepColorProfile
            p[Keys.keepOrientation] = s.keepOrientation
            p[Keys.deepVideoClean] = s.deepVideoClean
            p[Keys.autoSave] = s.autoSaveToGallery
            p[Keys.neutralNames] = s.neutralFileNames
            p[Keys.replaceOriginals] = s.replaceOriginals && s.autoSaveToGallery
            p[Keys.shareFlow] = s.shareFlow.name
            p[Keys.textAutoCopy] = s.textAutoCopy
            val t = s.text
            p[Keys.removeInvisible] = t.removeInvisible
            p[Keys.normalizeSpaces] = t.normalizeSpaces
            p[Keys.removeAiArtifacts] = t.removeAiArtifacts
            p[Keys.removeTracking] = t.removeTrackingParams
            p[Keys.removeCitationNumbers] = t.removeCitationNumbers
            p[Keys.stripMarkdown] = t.stripMarkdown
            p[Keys.dashes] = t.normalizeDashes
            p[Keys.quotes] = t.normalizeQuotes
            p[Keys.ellipsis] = t.normalizeEllipsis
            p[Keys.fancy] = t.normalizeFancyLetters
            p[Keys.trimLines] = t.trimLines
            p[Keys.collapseBlank] = t.collapseBlankLines
        }
    }

    suspend fun updateText(transform: (TextCleanOptions) -> TextCleanOptions) =
        update { it.copy(text = transform(it.text)) }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings.DEFAULT
        val dt = d.text
        return AppSettings(
            imageMode = enumOr(this[Keys.imageMode], d.imageMode),
            outputFormat = enumOr(this[Keys.outputFormat], d.outputFormat),
            jpegQuality = (this[Keys.jpegQuality] ?: d.jpegQuality).coerceIn(50, 100),
            maxDimension = this[Keys.maxDimension]?.takeIf { it in AppSettings.MAX_DIMENSIONS } ?: d.maxDimension,
            keepColorProfile = this[Keys.keepColorProfile] ?: d.keepColorProfile,
            keepOrientation = this[Keys.keepOrientation] ?: d.keepOrientation,
            deepVideoClean = this[Keys.deepVideoClean] ?: d.deepVideoClean,
            autoSaveToGallery = this[Keys.autoSave] ?: d.autoSaveToGallery,
            neutralFileNames = this[Keys.neutralNames] ?: d.neutralFileNames,
            replaceOriginals = this[Keys.replaceOriginals] ?: d.replaceOriginals,
            shareFlow = enumOr(this[Keys.shareFlow], d.shareFlow),
            textAutoCopy = this[Keys.textAutoCopy] ?: d.textAutoCopy,
            text = TextCleanOptions(
                removeInvisible = this[Keys.removeInvisible] ?: dt.removeInvisible,
                normalizeSpaces = this[Keys.normalizeSpaces] ?: dt.normalizeSpaces,
                removeAiArtifacts = this[Keys.removeAiArtifacts] ?: dt.removeAiArtifacts,
                removeTrackingParams = this[Keys.removeTracking] ?: dt.removeTrackingParams,
                removeCitationNumbers = this[Keys.removeCitationNumbers] ?: dt.removeCitationNumbers,
                stripMarkdown = this[Keys.stripMarkdown] ?: dt.stripMarkdown,
                normalizeDashes = this[Keys.dashes] ?: dt.normalizeDashes,
                normalizeQuotes = this[Keys.quotes] ?: dt.normalizeQuotes,
                normalizeEllipsis = this[Keys.ellipsis] ?: dt.normalizeEllipsis,
                normalizeFancyLetters = this[Keys.fancy] ?: dt.normalizeFancyLetters,
                trimLines = this[Keys.trimLines] ?: dt.trimLines,
                collapseBlankLines = this[Keys.collapseBlank] ?: dt.collapseBlankLines,
            ),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
