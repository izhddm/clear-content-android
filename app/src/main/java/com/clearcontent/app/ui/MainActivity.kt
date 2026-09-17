package com.clearcontent.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clearcontent.app.AppGraph
import com.clearcontent.app.media.GalleryOriginals
import com.clearcontent.app.queue.ItemStatus
import com.clearcontent.app.queue.QueueItem
import com.clearcontent.app.queue.Origin
import com.clearcontent.app.queue.QueueEvent
import com.clearcontent.app.settings.ShareFlow
import com.clearcontent.app.system.Incoming
import com.clearcontent.app.system.Share
import com.clearcontent.app.ui.media.MediaScreen
import com.clearcontent.app.ui.settings.SettingsScreen
import com.clearcontent.app.ui.text.TextScreen
import com.clearcontent.app.ui.theme.ClearContentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Runs after the delete dialog closes, so sharing never races the confirmation. */
    private var afterDeletion: (() -> Unit)? = null

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onDeleteConfirmed(result.resultCode == RESULT_OK)
        afterDeletion?.invoke()
        afterDeletion = null
    }

    /** Asks Android (one dialog with thumbnails) to delete the originals of cleaned gallery items. */
    fun requestDeleteOriginals(items: List<QueueItem>): Boolean {
        if (!GalleryOriginals.supported || vm.pendingDeletion.isNotEmpty()) return false
        val targets = vm.replaceableItems(items)
        val uris = targets.mapNotNull { it.source.galleryItem }
        if (uris.isEmpty()) return false
        return try {
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            vm.pendingDeletion = targets.map { it.id }.toSet()
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            true
        } catch (e: Exception) {
            vm.pendingDeletion = emptySet()
            vm.message("Не удалось запросить удаление оригиналов: ${e.message}")
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { ClearContentTheme { AppRoot(vm, onDeleteOriginals = { requestDeleteOriginals(vm.items.value) }) } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.get(this@MainActivity).queue.events.collect { event ->
                    if (event is QueueEvent.BatchFinished) onBatchFinished(event)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (val incoming = Incoming.parse(this, intent)) {
            is Incoming.Media -> vm.enqueue(incoming.uris, Origin.SHARE)
            is Incoming.Text -> vm.setIncomingText(incoming.text)
            Incoming.PickMedia -> vm.requestPicker()
            Incoming.OpenText -> vm.tab = Tab.TEXT
            Incoming.None -> Unit
        }
    }

    private fun onBatchFinished(event: QueueEvent.BatchFinished) {
        val done = event.items.filter { it.status == ItemStatus.DONE }.mapNotNull { it.result?.output }
        val failed = event.items.count { it.status == ItemStatus.FAILED }
        if (failed > 0) vm.message("Не удалось очистить файлов: $failed")
        val share = { shareAfterBatch(event, done) }
        // Current item states (the event holds a snapshot) decide what can be replaced.
        val fresh = vm.items.value.filter { it.batchId == event.batchId }
        if (requestDeleteOriginals(fresh)) afterDeletion = share else share()
    }

    private fun shareAfterBatch(event: QueueEvent.BatchFinished, done: List<com.clearcontent.app.media.CleanOutput>) {
        if (event.origin != Origin.SHARE || done.isEmpty()) return
        val uris = done.map { it.shareUri }
        val mime = Share.mimeFor(done.map { it.mimeType })
        when (vm.settings.value.shareFlow) {
            ShareFlow.SHOW_RESULT -> Unit
            ShareFlow.SHARE_SHEET -> Share.shareMedia(this, uris, mime)
            ShareFlow.INSTAGRAM -> if (!Share.toInstagram(this, uris, mime)) {
                vm.message("Instagram не установлен — открываю «Поделиться»")
                Share.shareMedia(this, uris, mime)
            }
        }
    }
}

private data class TabSpec(val tab: Tab, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec(Tab.MEDIA, "Медиа", Icons.Outlined.PhotoLibrary),
    TabSpec(Tab.TEXT, "Текст", Icons.Outlined.TextFields),
    TabSpec(Tab.SETTINGS, "Настройки", Icons.Outlined.Tune),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel, onDeleteOriginals: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val items by vm.items.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val textState by vm.text.collectAsStateWithLifecycle()

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)) { uris ->
        vm.enqueue(uris, Origin.PICKER)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.enqueue(uris, Origin.PICKER)
    }
    val openGallery = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.pickRequests.collect { openGallery() } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Clear Content", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Офлайн · файлы не покидают телефон",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TABS.forEach { spec ->
                    NavigationBarItem(
                        selected = vm.tab == spec.tab,
                        onClick = { vm.tab = spec.tab },
                        icon = { Icon(spec.icon, contentDescription = null) },
                        label = { Text(spec.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (vm.tab) {
            Tab.MEDIA -> MediaScreen(
                items = items,
                contentPadding = padding,
                onGallery = openGallery,
                onFiles = { filePicker.launch(arrayOf("image/*", "video/*")) },
                onClipboard = vm::pasteMedia,
                onRetry = vm::retry,
                onRemove = vm::remove,
                onClear = vm::clearFinished,
                onMessage = vm::message,
                replaceable = vm.replaceableItems(items).size,
                replaceMode = settings.replaceOriginals,
                onDeleteOriginals = onDeleteOriginals,
            )
            Tab.TEXT -> TextScreen(
                input = vm.textInput,
                state = textState,
                options = settings.text,
                contentPadding = padding,
                onInput = vm::onTextChange,
                onPaste = vm::pasteText,
                onCopy = vm::copyResult,
                onOptions = vm::updateText,
            )
            Tab.SETTINGS -> SettingsScreen(
                settings = settings,
                contentPadding = padding,
                onUpdate = vm::updateSettings,
                onMessage = vm::message,
            )
        }
    }
}
