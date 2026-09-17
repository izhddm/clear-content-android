package com.clearcontent.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clearcontent.app.AppGraph
import com.clearcontent.app.media.GalleryOriginals
import com.clearcontent.app.queue.Origin
import com.clearcontent.app.queue.QueueItem
import com.clearcontent.app.settings.AppSettings
import com.clearcontent.app.system.Clipboard
import com.clearcontent.core.text.HiddenChar
import com.clearcontent.core.text.TextCleanOptions
import com.clearcontent.core.text.TextCleanResult
import com.clearcontent.core.text.TextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Tab { MEDIA, TEXT, SETTINGS }

data class TextState(val input: String, val result: TextCleanResult?, val hidden: List<HiddenChar>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = AppGraph.get(app)

    val items = graph.queue.items
    val settings: StateFlow<AppSettings> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT)

    var tab by mutableStateOf(Tab.MEDIA)
    var textInput by mutableStateOf("")
        private set
    /** Set when the text came from "Share" so the result is copied automatically once ready. */
    private var autoCopyPending = false

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _pickRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pickRequests: SharedFlow<Unit> = _pickRequests.asSharedFlow()

    @OptIn(FlowPreview::class)
    val text: StateFlow<TextState> = combine(
        snapshotFlow { textInput }.debounce { if (it.length > 20_000) 250 else 60 },
        settings.map { it.text }.distinctUntilChanged(),
    ) { input, options -> compute(input, options) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TextState("", null, emptyList()))

    init {
        viewModelScope.launch {
            text.collect { state ->
                val result = state.result ?: return@collect
                if (autoCopyPending && state.input == textInput) {
                    autoCopyPending = false
                    Clipboard.copyText(getApplication(), result.text)
                    _messages.emit("Очищенный текст скопирован. " + Labels.textSummary(result))
                }
            }
        }
    }

    private fun compute(input: String, options: TextCleanOptions): TextState {
        if (input.isEmpty()) return TextState(input, null, emptyList())
        return TextState(input, TextSanitizer.clean(input, options), TextSanitizer.findHidden(input))
    }

    fun onTextChange(value: String) {
        textInput = value
    }

    fun setIncomingText(value: String) {
        tab = Tab.TEXT
        val current = text.value
        if (value == textInput && current.input == value && current.result != null) {
            // Same text again: the pipeline will not emit, so copy the ready result right away.
            if (settings.value.textAutoCopy) {
                Clipboard.copyText(getApplication(), current.result.text)
                message("Очищенный текст скопирован. " + Labels.textSummary(current.result))
            }
            return
        }
        textInput = value
        autoCopyPending = settings.value.textAutoCopy
    }

    fun pasteText() {
        when (val c = Clipboard.read(getApplication())) {
            is Clipboard.Content.Text -> textInput = c.text
            is Clipboard.Content.Media -> {
                enqueue(c.uris, Origin.CLIPBOARD)
                tab = Tab.MEDIA
            }
            Clipboard.Content.Empty -> message("Буфер обмена пуст")
        }
    }

    fun pasteMedia() {
        when (val c = Clipboard.read(getApplication())) {
            is Clipboard.Content.Media -> enqueue(c.uris, Origin.CLIPBOARD)
            is Clipboard.Content.Text -> {
                setIncomingText(c.text)
                message("В буфере текст — открыта вкладка «Текст»")
            }
            Clipboard.Content.Empty -> message("В буфере нет фото или видео")
        }
    }

    fun copyResult() {
        val result = text.value.result ?: return
        Clipboard.copyText(getApplication(), result.text)
        message("Скопировано")
    }

    fun enqueue(uris: List<Uri>, origin: Origin) {
        if (uris.isEmpty()) return
        tab = Tab.MEDIA
        graph.queue.enqueue(uris, origin)
    }

    fun requestPicker() {
        tab = Tab.MEDIA
        _pickRequests.tryEmit(Unit)
    }

    /** Item ids whose originals are waiting for the system delete confirmation (survives rotation). */
    var pendingDeletion: Set<Long> = emptySet()

    fun replaceableItems(from: List<QueueItem> = items.value): List<QueueItem> =
        if (settings.value.replaceOriginals && GalleryOriginals.supported) from.filter { it.canReplaceOriginal } else emptyList()

    fun onDeleteConfirmed(confirmed: Boolean) {
        val ids = pendingDeletion
        pendingDeletion = emptySet()
        if (ids.isEmpty()) return
        if (!confirmed) {
            message("Оригиналы оставлены. Их можно удалить позже кнопкой в списке")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // The originals are gone now, so the clean copies can take over their file names.
            items.value.filter { it.id in ids }.forEach { item ->
                val out = item.result?.output ?: return@forEach
                val uri = out.galleryUri ?: return@forEach
                val name = out.finalName ?: return@forEach
                runCatching { graph.outputs.rename(uri, name) }
            }
            graph.queue.markOriginalsDeleted(ids)
            message("Оригиналы удалены: ${ids.size}. Очищенные копии на их месте")
        }
    }

    fun retry(id: Long) = graph.queue.retry(id)
    fun remove(id: Long) = graph.queue.remove(id)
    fun clearFinished() = graph.queue.clearFinished()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    fun updateText(transform: (TextCleanOptions) -> TextCleanOptions) {
        viewModelScope.launch { graph.settings.updateText(transform) }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }
}
