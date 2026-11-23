// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Reads EA7JRS/Wefax (the weather facsimile receiver of the local network): charts, status, stations and VOACAP predictions.
// Plain HTTP over a socket, as with the other programs of the station (rigctl, gpsd); the app only reads.
package es.ea7jrs.aprslorap

import es.ea7jrs.aprslorap.core.Wefax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object WefaxLink {
    val charts = MutableStateFlow<List<Wefax.Chart>>(emptyList())
    val status = MutableStateFlow<Wefax.Status?>(null)
    val stations = MutableStateFlow<Wefax.StationList?>(null)
    val table = MutableStateFlow<Wefax.Table?>(null)
    /** "follow" and "skip" of the receiver, to show its schedule as it will work it. */
    val follow = MutableStateFlow("")
    val skip = MutableStateFlow("")
    val message = MutableStateFlow("")
    @Volatile private var busy = false

    /** One GET. Null when the receiver cannot be reached or the answer is not usable. Never on the main thread. */
    fun get(host: String, port: Int, path: String, maxBytes: Int = 4_000_000): Wefax.Response? {
        if (!Wefax.validHost(host) || port !in 1..65535) return null
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5000)
                s.soTimeout = 15000
                s.getOutputStream().write(Wefax.request(host, port, path))
                s.getOutputStream().flush()
                val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
                val inp = s.getInputStream()
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > maxBytes) return null }
                Wefax.parseResponse(out.toByteArray())
            }
        } catch (e: Exception) { null }
    }

    /** Reads everything the screen shows. Safe to call often: one at a time. */
    suspend fun refresh(settings: Settings) {
        val host = settings.wefaxHost.trim(); val port = settings.wefaxPort
        if (host.isEmpty()) { message.value = "Write the address of the computer that runs Wefax"; return }
        if (!Wefax.validHost(host)) { message.value = "That is not a host name or an address"; return }
        if (busy) return
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val st = get(host, port, "/api/status")
                if (st == null || !st.ok) { message.value = "Wefax does not answer at $host:$port"; status.value = null; return@withContext }
                status.value = Wefax.parseStatus(st.text, System.currentTimeMillis())
                get(host, port, "/api/charts?limit=100")?.takeIf { it.ok }?.let { charts.value = Wefax.parseCharts(it.text) }
                if (stations.value == null) get(host, port, "/estaciones.json")?.takeIf { it.ok }?.let { stations.value = Wefax.parseStations(it.text) }
                get(host, port, "/api/propagation")?.takeIf { it.ok }?.let { table.value = Wefax.parseTable(it.text) }
                get(host, port, "/api/settings")?.takeIf { it.ok }?.let { r ->
                    val o = try { es.ea7jrs.aprslorap.core.Json.parseObject(r.text) } catch (e: Exception) { null }
                    follow.value = (o?.get("follow") as? String) ?: ""
                    skip.value = (o?.get("skip") as? String) ?: ""
                }
                message.value = ""
            }
        } finally { busy = false }
    }

    /** The PNG of a chart, or of the one being received ("/live.png"). */
    suspend fun image(settings: Settings, path: String): ByteArray? = withContext(Dispatchers.IO) {
        get(settings.wefaxHost.trim(), settings.wefaxPort, path, 12_000_000)?.takeIf { it.ok && it.body.size > 100 }?.body
    }

    /** A VOACAP prediction from a transmitter to where the Wefax receiver is. */
    suspend fun circuit(settings: Settings, txLat: Double, txLon: Double, khz: List<Double>, kw: Double): Wefax.Circuit? = withContext(Dispatchers.IO) {
        val path = Wefax.circuitPath(txLat, txLon, khz, kw) ?: return@withContext null
        get(settings.wefaxHost.trim(), settings.wefaxPort, path)?.takeIf { it.ok }?.let { Wefax.parseCircuit(it.text) }
    }
}
