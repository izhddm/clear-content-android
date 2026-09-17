package com.clearcontent.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.clearcontent.app.media.MediaCleaner
import com.clearcontent.app.media.OutputStore
import com.clearcontent.app.media.SourceReader
import com.clearcontent.app.queue.CleanQueue
import com.clearcontent.app.queue.ProcessingService
import com.clearcontent.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency graph; everything here is process-scoped. */
class AppGraph(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val reader = SourceReader(context)
    val outputs = OutputStore(context)
    val cleaner = MediaCleaner(context, reader, outputs)
    val queue = CleanQueue(appScope, reader, cleaner, settings) { ProcessingService.start(context) }

    companion object {
        fun get(context: Context): AppGraph = (context.applicationContext as ClearContentApp).graph
    }
}

class ClearContentApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.appScope.launch(Dispatchers.IO) { graph.outputs.cleanup() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
