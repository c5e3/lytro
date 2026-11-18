package app.refocus.camera

import android.net.Network
import android.os.SystemClock
import app.refocus.core.camera.CameraFileMissingException
import app.refocus.core.camera.HardwareInfo
import app.refocus.core.camera.LytroClient
import app.refocus.data.Library
import app.refocus.data.PictureItem
import app.refocus.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val hardware: HardwareInfo) : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
    /** [wasConnected]: the connection dropped (vs. never came up). */
    data class Failed(val message: String, val wasConnected: Boolean) : ConnectionState

    val isLive get() = this is Connected || this is Reconnecting
}

/** Progress of "download all". */
data class BatchProgress(
    val total: Int,
    val done: Int,
    val currentName: String?,
    val etaSeconds: Long?,
    val bytesPerSecond: Double,
)

/**
 * The single connection to the camera and its download queue.
 *
 * All camera I/O runs on one dedicated thread, one command at a time. Work is
 * done in small steps (one 64 KiB chunk of a RAW at a time) and the next step is
 * picked by priority each time, so a picture the user opens preempts "download
 * all" within a fraction of a second; the interrupted RAW resumes later from its
 * partial file.
 */
class CameraSession(
    private val library: Library,
    private val network: CameraNetwork,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val onRawDownloaded: (String) -> Unit,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "camera-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Picture ids the user asked for, most recent first. */
    private val priority = ArrayDeque<String>()
    private val lock = Any()
    private val _batchIds = MutableStateFlow<Set<String>?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var refreshRequested = false
    private val thumbFailed = HashSet<String>()
    private var worker: Job? = null
    private var client: LytroClient? = null
    private var boundNetwork: Network? = null
    @Volatile private var current: String? = null
    private val speed = MutableStateFlow(0.0)
    /** Smoothed RAW download speed in bytes per second (0 when idle). */
    val downloadSpeed: StateFlow<Double> = speed.asStateFlow()
    private var alternate = false

    val batch: StateFlow<BatchProgress?> = combine(_batchIds, library.items, speed) { ids, items, bps ->
        if (ids == null) return@combine null
        val done = ids.count { items[it]?.rawDone == true }
        val remainingBytes = ids.sumOf { id ->
            val it = items[id]
            if (it == null || it.rawDone) 0L else RAW_SIZE - it.rawBytes
        }
        BatchProgress(
            total = ids.size,
            done = done,
            currentName = current?.let { items[it]?.name },
            etaSeconds = if (bps > 1000) (remainingBytes / bps).toLong() else null,
            bytesPerSecond = bps,
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Anything waiting to be downloaded while connected. */
    val busy: StateFlow<Boolean> = combine(_state, library.items) { s, items ->
        s.isLive && items.values.any { it.downloadQueued }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // drop the session when the phone leaves the camera's WiFi
        scope.launch {
            network.cameraNetwork.collect { n ->
                val bound = boundNetwork
                if (bound != null && n != bound && _state.value.isLive) {
                    stop(ConnectionState.Failed("The phone left the camera's WiFi", wasConnected = true))
                } else if (n != null && _state.value is ConnectionState.Failed) {
                    // the camera's WiFi is (back) up: offer Connect instead of an old error.
                    // Connecting stays the user's decision.
                    _state.value = ConnectionState.Disconnected
                }
            }
        }
    }

    // --------------------------------------------------------------- control
    fun connect() {
        val s = _state.value
        if (s is ConnectionState.Connecting || s.isLive) return
        _state.value = ConnectionState.Connecting
        worker = scope.launch(dispatcher) {
            try {
                val address = settings.current()
                boundNetwork = network.cameraNetwork.value
                val c = LytroClient { network.openSocket(address.host, address.port) }
                client = c
                val hw = c.hardwareInfo()
                library.mergeCameraList(c.listPictures())
                _state.value = ConnectionState.Connected(hw)
                runWorker(c, hw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeClient()
                boundNetwork = null
                _state.value = ConnectionState.Failed(describe(e), wasConnected = false)
            }
        }
    }

    fun disconnect() = stop(ConnectionState.Disconnected)

    private fun stop(newState: ConnectionState) {
        worker?.cancel()
        worker = null
        closeClient()
        boundNetwork = null
        current = null
        synchronized(lock) { priority.clear() }
        _batchIds.value = null
        library.updateAll { it.copy(downloadQueued = false, downloading = false) }
        _state.value = newState
    }

    fun refresh() {
        refreshRequested = true
        wake.trySend(Unit)
    }

    /** Download this picture before anything else. */
    fun requestDownload(id: String) {
        val item = library[id] ?: return
        if (item.rawDone || !item.onCamera) return
        synchronized(lock) {
            priority.remove(id)
            priority.addFirst(id)
        }
        library.update(id) { it.copy(downloadQueued = true, error = null) }
        wake.trySend(Unit)
    }

    fun downloadAll() {
        val ids = library.sorted().filter { it.onCamera && !it.rawDone }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        _batchIds.value = (_batchIds.value ?: emptySet()) + ids
        library.updateAll { if (it.id in ids) it.copy(downloadQueued = true, error = null) else it }
        wake.trySend(Unit)
    }

    /** Stops "download all" and any queued downloads; partial files are kept. */
    fun stopDownloads() {
        synchronized(lock) { priority.clear() }
        _batchIds.value = null
        library.updateAll { it.copy(downloadQueued = false, downloading = false) }
    }

    fun cancelDownload(id: String) {
        synchronized(lock) { priority.remove(id) }
        _batchIds.value = _batchIds.value?.minus(id)
        library.update(id) { it.copy(downloadQueued = false, downloading = false) }
    }

    private fun closeClient() {
        runCatching { client?.close() }
        client = null
    }

    // ---------------------------------------------------------------- worker
    private sealed interface Task {
        data object Refresh : Task
        data class Thumbnail(val id: String) : Task
        data class Metadata(val id: String) : Task
        data class Raw(val id: String) : Task
    }

    private fun nextTask(): Task? {
        if (refreshRequested) return Task.Refresh
        val items = library.items.value

        fun downloadTask(item: PictureItem): Task = if (!item.hasMeta) Task.Metadata(item.id) else Task.Raw(item.id)

        val wanted: Task? = synchronized(lock) {
            while (priority.isNotEmpty()) {
                val item = items[priority.first()]
                if (item == null || item.rawDone || !item.onCamera) priority.removeFirst() else break
            }
            priority.firstOrNull()?.let { items[it] }?.let(::downloadTask)
        }
        val thumb = library.sorted().firstOrNull { it.onCamera && it.thumbVersion == 0 && it.id !in thumbFailed }
            ?.let { Task.Thumbnail(it.id) }

        // thumbnails are tiny: interleave them with a requested download
        if (wanted != null && thumb != null) {
            alternate = !alternate
            return if (alternate) thumb else wanted
        }
        if (wanted != null) return wanted
        if (thumb != null) return thumb

        val ids = _batchIds.value ?: return null
        val next = library.sorted().firstOrNull { it.id in ids && it.onCamera && !it.rawDone && it.error == null }
        if (next == null) {
            _batchIds.value = null
            return null
        }
        return downloadTask(next)
    }

    private suspend fun runWorker(c: LytroClient, hw: HardwareInfo) {
        var failures = 0
        while (coroutineContext.isActive) {
            val task = nextTask()
            if (task == null) {
                markCurrent(null)
                speed.value = 0.0
                wake.receive()
                continue
            }
            try {
                perform(c, task)
                if (failures > 0) {
                    failures = 0
                    _state.value = ConnectionState.Connected(hw)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CameraFileMissingException) {
                val id = when (task) {
                    is Task.Thumbnail -> task.id.also { thumbFailed += it }
                    is Task.Metadata -> task.id
                    is Task.Raw -> task.id
                    Task.Refresh -> null
                }
                if (task !is Task.Thumbnail && id != null) {
                    synchronized(lock) { priority.remove(id) }
                    library.update(id) {
                        it.copy(error = "The camera doesn't have this picture any more", downloadQueued = false, downloading = false)
                    }
                }
                if (task == Task.Refresh) refreshRequested = false
            } catch (e: Exception) {
                // connection trouble: back off, then retry (the client reconnects on demand)
                failures++
                c.close()
                if (failures > BACKOFF_MS.size) {
                    stop(ConnectionState.Failed("Lost the connection to the camera", wasConnected = true))
                    return
                }
                _state.value = ConnectionState.Reconnecting(failures)
                delay(BACKOFF_MS[failures - 1])
            }
        }
    }

    private fun markCurrent(id: String?) {
        val prev = current
        if (prev == id) return
        current = id
        if (prev != null) library.update(prev) { it.copy(downloading = false) }
        if (id != null) library.update(id) { it.copy(downloading = true) }
    }

    private fun perform(c: LytroClient, task: Task) {
        when (task) {
            Task.Refresh -> {
                refreshRequested = false
                library.mergeCameraList(c.listPictures())
            }
            is Task.Thumbnail -> {
                val item = library[task.id] ?: return
                val data = c.readFile(item.picture.thumbnailPath)
                library.thumbFile(task.id).writeBytes(data)
                library.update(task.id) { it.copy(thumbVersion = it.thumbVersion + 1) }
            }
            is Task.Metadata -> {
                val item = library[task.id] ?: return
                markCurrent(task.id)
                val data = c.readFile(item.picture.metadataPath)
                library.metaFile(task.id).writeBytes(data)
                library.update(task.id) { it.copy(hasMeta = true) }
            }
            is Task.Raw -> rawStep(c, task.id)
        }
    }

    private fun rawStep(c: LytroClient, id: String) {
        val item = library[id] ?: return
        markCurrent(id)
        val size = c.loadFile(item.picture.rawPath).toLong()
        val part = library.rawPartFile(id)
        var offset = part.length()
        if (offset > size) {
            part.delete()
            offset = 0
        }
        if (offset < size) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val n = minOf(LytroClient.CHUNK.toLong(), size - offset).toInt()
            val chunk = c.readChunk(offset.toInt(), n)
            FileOutputStream(part, true).use { it.write(chunk, 0, minOf(chunk.size, n)) }
            offset += minOf(chunk.size, n)
            val dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
            if (dt > 0) {
                val inst = chunk.size / dt
                speed.value = if (speed.value <= 0) inst else speed.value * 0.85 + inst * 0.15
            }
            library.update(id) { it.copy(rawBytes = offset, rawTotal = size, downloading = true) }
        }
        if (offset >= size) {
            val raw = library.rawFile(id)
            raw.delete()
            if (!part.renameTo(raw)) throw java.io.IOException("Could not store ${item.name}")
            synchronized(lock) { priority.remove(id) }
            current = null
            library.update(id) {
                it.copy(rawDone = true, rawBytes = size, rawTotal = size, downloading = false, downloadQueued = false)
            }
            onRawDownloaded(id)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "The camera didn't answer. Is the phone on the camera's WiFi?"
        is ConnectException, is NoRouteToHostException -> "Couldn't reach the camera. Join its WiFi (lytro.camera.…) and try again."
        else -> e.message ?: "Couldn't connect to the camera"
    }

    /** Waits until the session is idle or [timeoutMs] passes (used by tests and the service). */
    suspend fun awaitIdle(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) {
        while (busy.value) delay(200)
    }

    companion object {
        const val RAW_SIZE = 16_137_600L
        private val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000)
    }
}
