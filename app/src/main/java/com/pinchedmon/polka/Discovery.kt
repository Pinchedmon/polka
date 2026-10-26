package com.pinchedmon.polka

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds the reader on the local network:
 *  1. UDP broadcast "hello" on port 8134 — CrossPoint answers "crosspoint (on <host>);<wsPort>".
 *  2. In parallel, probes known hosts over HTTP: last good IP, crosspoint.local, the reader's own hotspot.
 */
class Discovery(private val context: Context) {

    /** The Wi-Fi network, so traffic doesn't leak to mobile data / VPN when the reader's hotspot has no internet. */
    fun wifiNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    suspend fun find(api: X4Api, lastHost: String?): DeviceStatus? = coroutineScope {
        val found = Channel<DeviceStatus>(Channel.UNLIMITED)
        val jobs = mutableListOf(
            launch {
                for (ip in broadcastHello()) {
                    runCatching { api.status(ip, fast = true) }.onSuccess { found.trySend(it) }
                }
            }
        )
        val candidates = listOfNotNull(lastHost, "crosspoint.local", "192.168.4.1").distinct()
        val probes = candidates.map { h ->
            async { runCatching { api.status(h, fast = true) }.getOrNull()?.also { found.trySend(it) } }
        }
        launch {
            probes.awaitAll()
            jobs.forEach { it.join() }
            found.close()
        }
        val result = withTimeoutOrNull(6000) { found.receiveCatching().getOrNull() }
        coroutineContext.cancelChildren()
        result
    }

    private suspend fun broadcastHello(): List<String> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("polka-discovery")?.apply { setReferenceCounted(false); acquire() }
        val replies = linkedSetOf<String>()
        try {
            DatagramSocket().use { sock ->
                wifiNetwork()?.bindSocket(sock)
                sock.broadcast = true
                sock.soTimeout = 700
                val msg = "hello".toByteArray()
                val targets = buildList {
                    add(InetAddress.getByName("255.255.255.255"))
                    subnetBroadcast()?.let { add(it) }
                }
                repeat(3) {
                    for (t in targets) runCatching { sock.send(DatagramPacket(msg, msg.size, t, 8134)) }
                    val deadline = System.currentTimeMillis() + 900
                    while (System.currentTimeMillis() < deadline) {
                        val buf = ByteArray(256)
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(p)
                            val text = String(p.data, 0, p.length)
                            if (text.startsWith("crosspoint")) {
                                p.address.hostAddress?.let { replies += it }
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        }
                    }
                    if (replies.isNotEmpty()) return@use
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        replies.toList()
    }

    private fun subnetBroadcast(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = wifiNetwork() ?: return null
        val link = cm.getLinkProperties(net) ?: return null
        val la = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = la.address.address
        val prefix = la.prefixLength
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val ipInt = ((ip[0].toInt() and 0xff) shl 24) or ((ip[1].toInt() and 0xff) shl 16) or
            ((ip[2].toInt() and 0xff) shl 8) or (ip[3].toInt() and 0xff)
        val b = ipInt or mask.inv()
        return InetAddress.getByAddress(
            byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte())
        )
    }
}
