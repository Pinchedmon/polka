package com.pinchedmon.polka

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Link {
    data object Searching : Link
    data object NotFound : Link
    data class Online(val status: DeviceStatus) : Link
}

enum class UploadState { Waiting, Sending, Done, AlreadyThere, Failed }

class UploadItem(val id: Long, val uri: Uri, val name: String, val size: Long) {
    var state by mutableStateOf(UploadState.Waiting)
    var progress by mutableStateOf(0f)
    var error by mutableStateOf<String?>(null)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("polka", 0)
    private val discovery = Discovery(app)
    private var api = X4Api(discovery.wifiNetwork())

    var link by mutableStateOf<Link>(Link.Searching)
        private set
    var folder by mutableStateOf(prefs.getString("folder", "/Books") ?: "/Books")
        private set
    var browsePath by mutableStateOf(folder)
        private set
    var files by mutableStateOf<List<RemoteFile>>(emptyList())
        private set
    var filesLoading by mutableStateOf(false)
        private set
    val queue = mutableStateListOf<UploadItem>()

    /** Fires each time a book lands on the reader — UI uses it for haptics + the "+1" pop. */
    private val _landed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val landed: SharedFlow<String> = _landed

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

    private val work = Channel<UploadItem>(Channel.UNLIMITED)
    private var searchJob: Job? = null
    private var nextId = 0L

    val host: String? get() = (link as? Link.Online)?.status?.host

    init {
        connect()
        viewModelScope.launch { for (item in work) runUpload(item) }
        // Heartbeat: notice when the reader leaves File Transfer mode or comes back.
        viewModelScope.launch {
            while (isActive) {
                delay(8000)
                val h = host
                if (h != null && queue.none { it.state == UploadState.Sending }) {
                    runCatching { api.status(h, fast = true) }
                        .onSuccess { link = Link.Online(it) }
                        .onFailure { link = Link.NotFound }
                } else if (link == Link.NotFound) {
                    connect(quiet = true)
                }
            }
        }
    }

    fun connect(manualHost: String? = null, quiet: Boolean = false) {
        if (searchJob?.isActive == true) return
        searchJob = viewModelScope.launch {
            if (!quiet) link = Link.Searching
            api = X4Api(discovery.wifiNetwork())
            val status = if (manualHost != null) {
                runCatching { api.status(manualHost.trim()) }.getOrNull()
            } else {
                discovery.find(api, prefs.getString("host", null))
            }
            if (status != null) {
                prefs.edit().putString("host", status.host).apply()
                link = Link.Online(status)
                refresh()
                kick()
            } else {
                link = Link.NotFound
                if (manualHost != null) _toast.tryEmit("По адресу $manualHost никого")
            }
        }
    }

    fun refresh() {
        val h = host ?: return
        viewModelScope.launch {
            filesLoading = true
            runCatching { api.list(h, browsePath) }
                .onSuccess { files = it }
                .onFailure {
                    // Target folder doesn't exist yet — that's fine, it'll be created on first upload.
                    files = emptyList()
                }
            filesLoading = false
        }
    }

    fun open(dir: RemoteFile) {
        browsePath = join(browsePath, dir.name)
        refresh()
    }

    fun up() {
        if (browsePath == "/") return
        browsePath = browsePath.substringBeforeLast('/').ifEmpty { "/" }
        refresh()
    }

    /** Make the currently browsed folder the drop target. */
    fun dropHere() {
        folder = browsePath
        prefs.edit().putString("folder", folder).apply()
        _toast.tryEmit("Книги полетят в $folder")
    }

    fun mkdir(name: String) {
        val h = host ?: return
        viewModelScope.launch {
            runCatching { api.mkdir(h, browsePath, name.trim()) }
                .onFailure { _toast.tryEmit(it.message ?: "Не вышло") }
            refresh()
        }
    }

    fun delete(f: RemoteFile) {
        val h = host ?: return
        viewModelScope.launch {
            runCatching { api.delete(h, join(browsePath, f.name)) }
                .onSuccess { _toast.tryEmit("${f.name} — удалено") }
                .onFailure { _toast.tryEmit(it.message ?: "Не вышло удалить") }
            refresh()
        }
    }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val cr = getApplication<Application>().contentResolver
        uris.forEach { uri ->
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "book"
            var size = -1L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val item = UploadItem(nextId++, uri, sanitize(name), size)
            queue.add(0, item)
            work.trySend(item)
        }
    }

    fun retry(item: UploadItem) {
        item.state = UploadState.Waiting
        item.error = null
        item.progress = 0f
        work.trySend(item)
    }

    fun clearFinished() {
        queue.removeAll { it.state == UploadState.Done || it.state == UploadState.AlreadyThere }
    }

    /** Re-queue anything that failed because the reader was offline. */
    private fun kick() {
        queue.filter { it.state == UploadState.Failed }.forEach { retry(it) }
    }

    private var folderReady: String? = null

    private suspend fun runUpload(item: UploadItem) {
        while (host == null) delay(500)
        val h = host!!
        item.state = UploadState.Sending
        item.progress = 0f
        val app = getApplication<Application>()
        try {
            ensureFolder(h, folder)
            // The reader's web server needs a Content-Length, so stream-only sources get spooled first.
            var size = item.size
            var spool: File? = null
            if (size < 0) {
                spool = withContext(Dispatchers.IO) {
                    File.createTempFile("up", ".bin", app.cacheDir).also { f ->
                        app.contentResolver.openInputStream(item.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                    }
                }
                size = spool.length()
            }
            api.upload(
                host = h, dir = folder, fileName = item.name, size = size,
                open = { spool?.inputStream() ?: app.contentResolver.openInputStream(item.uri)!! },
                onProgress = { item.progress = it },
            )
            spool?.delete()
            item.progress = 1f
            item.state = UploadState.Done
            _landed.tryEmit(item.name)
        } catch (e: X4Api.AlreadyThereException) {
            item.state = UploadState.AlreadyThere
        } catch (e: Exception) {
            item.state = UploadState.Failed
            item.error = e.message ?: e.javaClass.simpleName
            runCatching { api.status(h, fast = true) }.onFailure { link = Link.NotFound }
        }
        if (browsePath == folder) refresh()
    }

    private suspend fun ensureFolder(h: String, path: String) {
        if (folderReady == "$h$path" || path == "/") return
        var parent = "/"
        for (part in path.trim('/').split('/')) {
            val existing = runCatching { api.list(h, parent) }.getOrDefault(emptyList())
            if (existing.none { it.isDirectory && it.name == part }) {
                runCatching { api.mkdir(h, parent, part) }
            }
            parent = join(parent, part)
        }
        folderReady = "$h$path"
    }

    private fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "-").trim().ifEmpty { "book" }
}
