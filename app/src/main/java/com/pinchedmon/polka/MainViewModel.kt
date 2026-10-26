package com.pinchedmon.polka

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

/** [dir] overrides the book folder; [replace] deletes an existing file of the same name first. */
class UploadItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val dir: String? = null,
    val replace: Boolean = false,
) {
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

    // --- sleep screens ---
    var sleepFiles by mutableStateOf<List<RemoteFile>>(emptyList())
        private set
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    /** Name of the /sleep picture that is also copied to /sleep.bmp (shown every time), or null = random. */
    var pinned by mutableStateOf(prefs.getString("pinned", null))
        private set
    var hasRootSleep by mutableStateOf(false)
        private set
    /** Firmware "sleepScreen" setting; 2 = Custom, 4 = Cover + Custom. */
    var sleepMode by mutableStateOf<Int?>(null)
        private set
    val customOn get() = sleepMode == SLEEP_CUSTOM || sleepMode == SLEEP_COVER_CUSTOM

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
                refreshSleep()
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

    fun refreshSleep() {
        val h = host ?: return
        viewModelScope.launch {
            sleepFiles = runCatching { api.list(h, SLEEP_DIR) }.getOrDefault(emptyList())
                .filter { !it.isDirectory && it.name.endsWith(".bmp", ignoreCase = true) }
            hasRootSleep = runCatching { api.list(h, "/") }.getOrDefault(emptyList())
                .any { !it.isDirectory && it.name.equals("sleep.bmp", ignoreCase = true) }
            val pinPending = queue.any { it.dir == "/" && it.state in listOf(UploadState.Waiting, UploadState.Sending) }
            if (!hasRootSleep && pinned != null && !pinPending) savePinned(null)
            sleepMode = runCatching { api.setting(h, "sleepScreen") }.getOrNull()
            thumbs.keys.retainAll(sleepFiles.map { it.name }.toSet())
            for (f in sleepFiles) {
                if (f.name in thumbs) continue
                runCatching { api.download(h, "$SLEEP_DIR/${f.name}") }
                    .mapCatching { withContext(Dispatchers.Default) { SleepImage.thumb(it)!!.asImageBitmap() } }
                    .onSuccess { thumbs[f.name] = it }
            }
        }
    }

    /** Converts pictures to 480×800 BMP and sends them to /sleep. With [pin], the first one also becomes /sleep.bmp. */
    fun addWallpapers(items: List<Pair<Uri, SleepImage.Framing?>>, pin: Boolean) {
        if (items.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val taken = sleepFiles.map { it.name.lowercase() }.toMutableSet()
            items.forEachIndexed { i, (uri, framing) ->
                val file = runCatching {
                    withContext(Dispatchers.Default) {
                        val bmp = SleepImage.toBmp(SleepImage.load(app, uri).let { src ->
                            SleepImage.render(src, framing ?: SleepImage.Framing.preset(src.width, src.height, 0, fill = true))
                        })
                        File.createTempFile("sleep", ".bmp", app.cacheDir).apply { writeBytes(bmp) }
                    }
                }.getOrElse {
                    _toast.tryEmit("Не получилось открыть картинку")
                    return@forEachIndexed
                }
                val base = sanitize(displayName(uri).substringBeforeLast('.')).take(40)
                var name = "$base.bmp"
                var n = 2
                while (name.lowercase() in taken) name = "$base-${n++}.bmp"
                taken += name.lowercase()
                enqueue(UploadItem(nextId++, Uri.fromFile(file), name, file.length(), dir = SLEEP_DIR))
                if (pin && i == 0) {
                    enqueue(UploadItem(nextId++, Uri.fromFile(file), "sleep.bmp", file.length(), dir = "/", replace = true))
                    savePinned(name)
                }
            }
        }
    }

    /** Show this picture every time instead of a random one. */
    fun pin(name: String) {
        val h = host ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                val bytes = api.download(h, "$SLEEP_DIR/$name")
                val file = File.createTempFile("sleep", ".bmp", app.cacheDir).apply { writeBytes(bytes) }
                enqueue(UploadItem(nextId++, Uri.fromFile(file), "sleep.bmp", file.length(), dir = "/", replace = true))
                savePinned(name)
            }.onFailure { _toast.tryEmit("Не вышло скачать $name") }
        }
    }

    /** Back to a random picture from /sleep on every sleep. */
    fun unpin() {
        val h = host ?: return
        viewModelScope.launch {
            runCatching { api.delete(h, "/sleep.bmp") }
            savePinned(null)
            hasRootSleep = false
            _toast.tryEmit("Теперь каждый раз случайная из /sleep")
        }
    }

    fun deleteWallpaper(name: String) {
        val h = host ?: return
        viewModelScope.launch {
            runCatching { api.delete(h, "$SLEEP_DIR/$name") }
                .onFailure { _toast.tryEmit(it.message ?: "Не вышло удалить") }
            if (name == pinned) unpin()
            refreshSleep()
        }
    }

    fun enableCustom() {
        val h = host ?: return
        viewModelScope.launch {
            runCatching { api.setSetting(h, "sleepScreen", SLEEP_CUSTOM) }
                .onSuccess { sleepMode = SLEEP_CUSTOM; _toast.tryEmit("На читалке включён режим «Своя картинка»") }
                .onFailure { _toast.tryEmit("Не вышло: ${it.message}") }
        }
    }

    private fun savePinned(name: String?) {
        pinned = name
        prefs.edit().putString("pinned", name).apply()
    }

    private fun enqueue(item: UploadItem) {
        queue.add(0, item)
        work.trySend(item)
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
        }
        return "sleep-${System.currentTimeMillis() % 100000}"
    }

    fun clearFinished() {
        queue.removeAll { it.state == UploadState.Done || it.state == UploadState.AlreadyThere }
    }

    /** Re-queue anything that failed because the reader was offline. */
    private fun kick() {
        queue.filter { it.state == UploadState.Failed }.forEach { retry(it) }
    }

    private val foldersReady = mutableSetOf<String>()

    private suspend fun runUpload(item: UploadItem) {
        while (host == null) delay(500)
        val h = host!!
        item.state = UploadState.Sending
        item.progress = 0f
        val app = getApplication<Application>()
        try {
            val dir = item.dir ?: folder
            ensureFolder(h, dir)
            if (item.replace) runCatching { api.delete(h, join(dir, item.name)) }
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
                host = h, dir = dir, fileName = item.name, size = size,
                open = { spool?.inputStream() ?: app.contentResolver.openInputStream(item.uri)!! },
                onProgress = { item.progress = it },
            )
            spool?.delete()
            item.progress = 1f
            item.state = UploadState.Done
            _landed.tryEmit(item.name)
            if (item.dir != null) {
                if (!customOn && sleepMode != null) enableCustom()
                refreshSleep()
            }
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
        if ("$h$path" in foldersReady || path == "/") return
        var parent = "/"
        for (part in path.trim('/').split('/')) {
            val existing = runCatching { api.list(h, parent) }.getOrDefault(emptyList())
            if (existing.none { it.isDirectory && it.name == part }) {
                runCatching { api.mkdir(h, parent, part) }
            }
            parent = join(parent, part)
        }
        foldersReady += "$h$path"
    }

    companion object {
        const val SLEEP_DIR = "/sleep"
        const val SLEEP_CUSTOM = 2
        const val SLEEP_COVER_CUSTOM = 4
    }

    private fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "-").trim().ifEmpty { "book" }
}
