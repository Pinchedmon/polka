package com.pinchedmon.polka

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class DeviceStatus(
    val host: String,
    val version: String,
    val device: String,
    val mode: String,
    val rssi: Int,
)

data class RemoteFile(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val isEpub: Boolean,
)

/** Firmware reports board names like "xteink_x4_pro"; show "X4 Pro". */
private fun prettyDevice(raw: String): String {
    val r = raw.lowercase()
    val model = Regex("x\\d").find(r)?.value?.uppercase() ?: return raw
    return if ("pro" in r) "$model Pro" else model
}

class X4Exception(message: String) : IOException(message)

/** HTTP client for the CrossPoint firmware web server (File Transfer mode on the reader). */
class X4Api(network: Network?) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .apply { if (network != null) socketFactory(network.socketFactory) }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val quick: OkHttpClient = client.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private fun base(host: String): HttpUrl = "http://$host/".toHttpUrl()

    suspend fun status(host: String, fast: Boolean = false): DeviceStatus = withContext(Dispatchers.IO) {
        val url = base(host).newBuilder().addPathSegments("api/status").build()
        (if (fast) quick else client).newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw X4Exception("HTTP ${r.code}")
            val j = JSONObject(r.body!!.string())
            DeviceStatus(
                host = host,
                version = j.optString("version", "?"),
                device = prettyDevice(j.optString("device", "X4")),
                mode = j.optString("mode", "STA"),
                rssi = j.optInt("rssi", 0),
            )
        }
    }

    suspend fun list(host: String, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val url = base(host).newBuilder().addPathSegments("api/files")
            .addQueryParameter("path", path).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw X4Exception("HTTP ${r.code}")
            val arr = JSONArray(r.body!!.string())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteFile(
                    name = o.getString("name"),
                    size = o.optLong("size"),
                    isDirectory = o.optBoolean("isDirectory"),
                    isEpub = o.optBoolean("isEpub"),
                )
            }.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun mkdir(host: String, parent: String, name: String) = post(
        host, "mkdir", FormBody.Builder().add("path", parent).add("name", name).build()
    )

    suspend fun delete(host: String, path: String) = post(
        host, "delete", FormBody.Builder().add("path", path).build()
    )

    suspend fun download(host: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = base(host).newBuilder().addPathSegments("download")
            .addQueryParameter("path", path).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw X4Exception("HTTP ${r.code}")
            r.body!!.bytes()
        }
    }

    /** Current value of one firmware setting (e.g. "sleepScreen"), or null if the firmware doesn't expose it. */
    suspend fun setting(host: String, key: String): Int? = withContext(Dispatchers.IO) {
        val url = base(host).newBuilder().addPathSegments("api/settings").build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val arr = JSONArray(r.body!!.string())
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("key") == key }
                ?.takeIf { it.has("value") }?.optInt("value")
        }
    }

    suspend fun setSetting(host: String, key: String, value: Int) = post(
        host, "api/settings",
        JSONObject().put(key, value).toString().toRequestBody("application/json".toMediaType())
    )

    private suspend fun post(host: String, endpoint: String, body: RequestBody) = withContext(Dispatchers.IO) {
        val url = base(host).newBuilder().addPathSegments(endpoint).build()
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw X4Exception(r.body?.string()?.trim().orEmpty().ifEmpty { "HTTP ${r.code}" })
        }
    }

    /**
     * Uploads one file. Throws [AlreadyThereException] when the reader already has a file with that name
     * (firmware refuses to overwrite).
     */
    suspend fun upload(
        host: String,
        dir: String,
        fileName: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = base(host).newBuilder().addPathSegments("upload")
            .addQueryParameter("path", dir).build()
        val filePart = ProgressBody("application/octet-stream".toMediaType(), size, open, onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, filePart)
            .build()
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            if (!r.isSuccessful) {
                val msg = r.body?.string()?.trim().orEmpty()
                if (msg.startsWith("File already exists")) throw AlreadyThereException()
                throw X4Exception(msg.ifEmpty { "HTTP ${r.code}" })
            }
        }
    }

    class AlreadyThereException : IOException("already exists")

    private class ProgressBody(
        private val type: MediaType,
        private val size: Long,
        private val open: () -> InputStream,
        private val onProgress: (Float) -> Unit,
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = size
        override fun isOneShot() = false
        override fun writeTo(sink: BufferedSink) {
            open().source().use { src ->
                var sent = 0L
                val buf = okio.Buffer()
                while (true) {
                    val n = src.read(buf, 16 * 1024)
                    if (n == -1L) break
                    sink.write(buf, n)
                    sent += n
                    if (size > 0) onProgress((sent.toFloat() / size).coerceAtMost(1f))
                }
            }
        }
    }
}
