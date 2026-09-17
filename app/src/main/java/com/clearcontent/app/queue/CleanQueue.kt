package com.clearcontent.app.queue

import android.net.Uri
import com.clearcontent.app.media.CleanResult
import com.clearcontent.app.media.MediaCleaner
import com.clearcontent.app.media.SourceInfo
import com.clearcontent.app.media.SourceReader
import com.clearcontent.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class ItemStatus { QUEUED, WORKING, DONE, FAILED }

data class QueueItem(
    val id: Long,
    val batchId: Long,
    val source: SourceInfo,
    val status: ItemStatus = ItemStatus.QUEUED,
    val stage: String? = null,
    val result: CleanResult? = null,
    val error: String? = null,
    val originalDeleted: Boolean = false,
) {
    /** Done, safely saved to the gallery, and the source is a gallery item that still exists. */
    val canReplaceOriginal: Boolean
        get() = status == ItemStatus.DONE && result?.output?.galleryUri != null && source.galleryItem != null && !originalDeleted
}

enum class Origin { PICKER, SHARE, CLIPBOARD }

sealed interface QueueEvent {
    data class BatchFinished(val batchId: Long, val origin: Origin, val items: List<QueueItem>) : QueueEvent
}

/**
 * App-scoped, sequential processing queue. Living outside any Activity keeps work alive across
 * configuration changes and lets every entry point (share, picker, clipboard) show the same list.
 */
class CleanQueue(
    private val scope: CoroutineScope,
    private val reader: SourceReader,
    private val cleaner: MediaCleaner,
    private val settings: SettingsRepository,
    /** Invoked synchronously when work is added, while the caller is still in the foreground. */
    private val onWorkAdded: () -> Unit = {},
) {
    private val ids = AtomicLong()
    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    private val _events = MutableSharedFlow<QueueEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<QueueEvent> = _events.asSharedFlow()

    /** Batches being prepared plus items waiting or running; drives the foreground service. */
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val work = Channel<Long>(Channel.UNLIMITED)
    private val batchOrigins = HashMap<Long, Origin>()

    init {
        scope.launch(Dispatchers.IO) {
            for (id in work) {
                try {
                    process(id)
                } catch (e: Throwable) {
                    // Never let one hostile file stop the worker (e.g. StackOverflowError, OOM).
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    mutate(id) { it.copy(status = ItemStatus.FAILED, stage = null, error = "Не удалось обработать файл") }
                } finally {
                    _pending.update { it - 1 }
                }
            }
        }
    }

    /** Adds [uris] as one batch; returns the batch id. */
    fun enqueue(uris: List<Uri>, origin: Origin): Long {
        val batch = ids.incrementAndGet()
        synchronized(batchOrigins) { batchOrigins[batch] = origin }
        _pending.update { it + 1 }
        onWorkAdded()
        scope.launch(Dispatchers.IO) {
            var added = 0
            try {
                prepare(batch, uris, origin).also { added = it }
            } finally {
                _pending.update { it + added - 1 }
            }
        }
        return batch
    }

    /** Describes and spools the batch, publishes the items and hands them to the worker. */
    private suspend fun prepare(batch: Long, uris: List<Uri>, origin: Origin): Int {
        val newItems = uris.distinct().map { uri ->
            val info = reader.describe(uri)
            // Grants from "Share" and the clipboard end with the sending activity, so take a private copy now.
            val source = if (origin == Origin.PICKER) info else runCatching { reader.spool(info) }.getOrDefault(info)
            QueueItem(id = ids.incrementAndGet(), batchId = batch, source = source)
        }
        _items.update { newItems.reversed() + it }
        newItems.forEach { work.send(it.id) }
        return newItems.size
    }

    fun retry(id: Long) {
        _items.update { list -> list.map { if (it.id == id) it.copy(status = ItemStatus.QUEUED, error = null, stage = null) else it } }
        _pending.update { it + 1 }
        onWorkAdded()
        work.trySend(id)
    }

    fun remove(id: Long) {
        _items.update { list -> list.filterNot { it.id == id && it.status != ItemStatus.WORKING } }
    }

    fun markOriginalsDeleted(ids: Set<Long>) {
        _items.update { list -> list.map { if (it.id in ids) it.copy(originalDeleted = true) else it } }
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.status == ItemStatus.QUEUED || it.status == ItemStatus.WORKING } }
    }

    private suspend fun process(id: Long) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status != ItemStatus.QUEUED) return
        mutate(id) { it.copy(status = ItemStatus.WORKING, stage = "Подготовка") }
        val current = settings.current()
        val updated = try {
            val result = cleaner.clean(item.source, current) { stage -> mutate(id) { it.copy(stage = stage) } }
            reader.releaseSpool(item.source)
            item.copy(status = ItemStatus.DONE, result = result, stage = null, error = null)
        } catch (e: OutOfMemoryError) {
            item.copy(status = ItemStatus.FAILED, error = "Недостаточно памяти", stage = null)
        } catch (e: Exception) {
            item.copy(status = ItemStatus.FAILED, error = describe(e), stage = null)
        }
        mutate(id) { updated }
        finishBatchIfDone(item.batchId)
    }

    private fun describe(e: Exception): String = when (e) {
        is SecurityException -> "Нет доступа к файлу. Поделитесь им ещё раз или выберите через «Галерея»"
        is java.io.FileNotFoundException -> "Файл не найден — возможно, он удалён или перемещён"
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun finishBatchIfDone(batch: Long) {
        val batchItems = _items.value.filter { it.batchId == batch }
        if (batchItems.isEmpty() || batchItems.any { it.status == ItemStatus.QUEUED || it.status == ItemStatus.WORKING }) return
        val origin = synchronized(batchOrigins) { batchOrigins.remove(batch) } ?: return
        _events.emit(QueueEvent.BatchFinished(batch, origin, batchItems))
    }

    private fun mutate(id: Long, transform: (QueueItem) -> QueueItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
