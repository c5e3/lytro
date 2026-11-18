package app.refocus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import app.refocus.camera.CameraNetwork
import app.refocus.camera.CameraSession
import app.refocus.data.Library
import app.refocus.data.ProcessingStage
import app.refocus.data.Settings
import app.refocus.export.Exporter
import app.refocus.processing.LightFieldCache
import app.refocus.processing.Processor
import app.refocus.service.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RefocusApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TransferService.CHANNEL, getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_transfers_desc) },
        )
    }
}

/** Process-wide singletons. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = Settings(app, scope)
    val library = Library(app)
    val network = CameraNetwork(app) { settings.address.value.host }
    val cache = LightFieldCache(library)
    val processor = Processor(library, cache, scope)
    val session = CameraSession(library, network, settings, scope) { id ->
        library.update(id) { it.copy(processing = ProcessingStage.Queued) }
        processor.kick()
    }
    val exporter = Exporter(app, library)

    /** Downloads or processing are pending: keep the foreground service running. */
    val busy: StateFlow<Boolean> = combine(session.busy, processor.busy) { a, b -> a || b }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Auto-connect is only considered once, when the app starts. */
    var startupCheckDone = false

    init {
        scope.launch {
            settings.address.drop(1).collect { network.reevaluate() }
        }
        scope.launch {
            busy.collect { if (it) startService() }
        }
    }

    fun startService() {
        try {
            TransferService.start(app)
        } catch (e: Exception) {
            // not allowed while the app is in the background; the work still runs while the process lives
            Log.w("AppGraph", "could not start the transfer service", e)
        }
    }
}

val Application.graph: AppGraph get() = (this as RefocusApp).graph
