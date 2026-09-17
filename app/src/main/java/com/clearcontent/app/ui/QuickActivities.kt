package com.clearcontent.app.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.clearcontent.app.AppGraph
import com.clearcontent.app.R
import com.clearcontent.app.media.SourceInfo
import com.clearcontent.app.queue.Origin
import com.clearcontent.app.system.Clipboard
import com.clearcontent.app.ui.theme.ClearContentTheme
import com.clearcontent.core.text.TextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Select text → Clear Content": replaces the selection in place, or copies when it is read-only. */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (input.isNullOrEmpty()) {
            finish()
            return
        }
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        lifecycleScope.launch {
            val graph = AppGraph.get(this@ProcessTextActivity)
            val settings = graph.settings.current()
            val result = withContext(Dispatchers.Default) { TextSanitizer.clean(input, settings.text) }
            if (readOnly) {
                Clipboard.copyText(this@ProcessTextActivity, result.text)
            } else {
                setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.text))
            }
            val summary = Labels.textSummary(result)
            val message = if (readOnly) "$summary. Скопировано" else summary
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

/**
 * Cleans whatever is on the clipboard. Launched from the Quick Settings tile and the launcher shortcut;
 * the clipboard can only be read once this window has focus.
 */
class ClipboardCleanActivity : ComponentActivity() {
    private var handled = false
    private var status by mutableStateOf("Чтение буфера…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClearContentTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 12.dp) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Text(status, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        lifecycleScope.launch { cleanClipboard() }
    }

    private suspend fun cleanClipboard() {
        val graph = AppGraph.get(this)
        val settings = graph.settings.current()
        val message = when (val content = Clipboard.read(this)) {
            is Clipboard.Content.Text -> {
                val result = withContext(Dispatchers.Default) { TextSanitizer.clean(content.text, settings.text) }
                Clipboard.copyText(this, result.text)
                "Буфер очищен: " + Labels.textSummary(result).replaceFirstChar { it.lowercase() }
            }
            is Clipboard.Content.Media -> {
                status = "Очистка файла…"
                val uri = content.uris.first()
                try {
                    val result = withContext(Dispatchers.IO) {
                        val info: SourceInfo = graph.reader.describe(uri)
                        graph.cleaner.clean(info, settings) { stage -> status = "$stage…" }
                    }
                    Clipboard.copyMedia(this, result.output.shareUri)
                    if (content.uris.size > 1) graph.queue.enqueue(content.uris.drop(1), Origin.CLIPBOARD)
                    "Файл очищен и скопирован" + if (result.output.galleryUri != null) ", сохранён в галерею" else ""
                } catch (e: Exception) {
                    "Не удалось очистить: ${e.message}"
                }
            }
            Clipboard.Content.Empty -> "Буфер обмена пуст"
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }
}

class CleanClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            subtitle = getString(R.string.tile_subtitle)
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardCleanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun { launch() } else launch()
    }
}
