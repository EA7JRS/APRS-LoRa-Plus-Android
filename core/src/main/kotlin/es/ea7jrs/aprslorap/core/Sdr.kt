/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Remote control of SDR receivers, without their audio (they keep playing it themselves):
 * - [Rigctl]: Hamlib rigctld text protocol: SDR++ (module "Rigctl Server", port 4532; on Android with a USB SDR too),
 *   gqrx (7356), SDR Console, CubicSDR...;
 * - [SdrConnect]: SDRconnect of SDRplay (GUI or headless), WebSocket API 1.0.x on port 5454, JSON;
 * - [SpyServer]: Airspy's SpyServer (Airspy, Airspy HF+, RTL-SDR), binary TCP protocol on port 5555.
 * And the frequency to tune: the tracker's satellite transmitter with Doppler, its LoRa / APRS frequency, a rotor target.
 */
object Sdr {
    enum class Kind(val label: String, val defaultPort: Int) {
        RIGCTL("SDR++ / gqrx (rigctl)", 4532),
        SDRCONNECT("SDRconnect (SDRplay)", 5454),
        SPYSERVER("SpyServer (Airspy)", 5555),
    }

    /** Modes shown in the app; each backend maps them to its own names. */
    val MODES = listOf("NFM", "WFM", "AM", "USB", "LSB", "CW")

    private const val C = 299_792_458.0

    /** Received frequency of a transmitter on [fHz] seen from a satellite moving away at [rangeRateKmS] (km/s, + = away). */
    fun dopplerRx(fHz: Long, rangeRateKmS: Double): Long = Math.round(fHz * (1.0 - rangeRateKmS * 1000.0 / C))

    fun mhz(hz: Long): String = String.format(Locale.US, "%.6f", hz / 1e6)

    /** "145.800", "145,8", "145800000" -> Hz; null if not a frequency. */
    fun parseFreq(text: String): Long? {
        val t = text.trim().replace(',', '.')
        val v = t.toDoubleOrNull() ?: return null
        val hz = if (v < 100_000.0) v * 1e6 else v                    // small numbers are MHz
        return if (hz in 1_000.0..20e9) Math.round(hz) else null
    }

    // ------------------------------------------------------------------ rigctl

    object Rigctl {
        fun setFreq(hz: Long) = "F $hz\n"
        const val GET_FREQ = "f\n"
        /** SDR++ wants "M <mode> <bandwidth>"; 0 = keep the bandwidth. SDR++ calls NFM "FM". */
        fun setMode(mode: String, bwHz: Int = 0) = "M ${modeName(mode)} $bwHz\n"
        fun modeName(mode: String) = when (mode) { "NFM" -> "FM"; else -> mode }
        fun appMode(rig: String) = when (rig.trim().uppercase()) { "FM", "NFM", "PKTFM" -> "NFM"; "WFM", "WFM_ST" -> "WFM"; "USB", "PKTUSB" -> "USB"; "LSB", "PKTLSB" -> "LSB"; "CW", "CWR" -> "CW"; "AM", "SAM", "DSB" -> "AM"; else -> rig.trim().uppercase() }
        const val GET_MODE = "m\n"
        /** SDR++: start / stop its recorder (as for a satellite pass from GPredict). */
        const val AOS = "AOS\n"
        const val LOS = "LOS\n"
        fun reply(line: String): Int? = line.trim().takeIf { it.startsWith("RPRT") }?.substring(4)?.trim()?.toIntOrNull()
    }

    // ------------------------------------------------------------------ SDRconnect

    object SdrConnect {
        const val PATH = "/"
        fun setProperty(property: String, value: String, device: String = "primary") =
            Json.encode(linkedMapOf("event_type" to "set_property", "property" to property, "device" to device, "value" to value))
        fun getProperty(property: String, device: String = "primary") =
            Json.encode(linkedMapOf("event_type" to "get_property", "property" to property, "device" to device, "value" to ""))
        fun event(type: String, value: String) = Json.encode(linkedMapOf("event_type" to type, "property" to "", "value" to value))
        fun setFreq(hz: Long) = setProperty("device_vfo_frequency", hz.toString())
        fun setCenter(hz: Long) = setProperty("device_center_frequency", hz.toString())
        fun setMode(mode: String) = setProperty("demodulator", mode)                   // AM, USB, LSB, CW, SAM, NFM, WFM
        fun setBandwidth(hz: Int) = setProperty("filter_bandwidth", hz.toString())
        fun setGain(lnaState: Int) = setProperty("lna_state", lnaState.toString())
        /** Properties read when connecting. */
        val READ = listOf("api_version", "active_device", "can_control", "started", "device_center_frequency", "device_vfo_frequency",
            "demodulator", "filter_bandwidth", "lna_state", "lna_state_min", "lna_state_max", "device_sample_rate", "signal_power", "signal_snr")

        data class Message(val type: String, val property: String, val value: String, val device: String?)

        fun parse(text: String): Message? {
            val o = try { Json.parseObject(text.trim()) } catch (_: Exception) { null } ?: return null
            val type = o.str("event_type") ?: return null
            val v = when (val x = o["value"]) { is String -> x; is Number -> if (x.toDouble() == Math.floor(x.toDouble())) x.toLong().toString() else x.toString(); is Boolean -> x.toString(); else -> "" }
            return Message(type, o.str("property") ?: "", v, o.str("device"))
        }
    }

    // ------------------------------------------------------------------ minimal WebSocket client (RFC 6455)

    object WebSocket {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun handshake(host: String, port: Int, path: String, key: String): String =
            "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: APRS-LoRa-Plus-Android\r\n\r\n"

        fun accept(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

        /** Checks the server's answer to the handshake (status line and headers). Null = good, else the reason. */
        fun checkResponse(head: String, key: String): String? {
            val lines = head.split("\r\n")
            if (lines.isEmpty() || !lines[0].contains(" 101")) return "not a WebSocket server: " + lines.firstOrNull()?.take(60)
            val acc = lines.firstOrNull { it.lowercase(Locale.US).startsWith("sec-websocket-accept:") }?.substringAfter(':')?.trim()
            return if (acc == accept(key)) null else "bad Sec-WebSocket-Accept"
        }

        /** A client frame (always masked). [opcode]: 1 text, 2 binary, 8 close, 9 ping, 10 pong. */
        fun frame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            val n = payload.size
            when {
                n < 126 -> out.write(0x80 or n)
                n < 65536 -> { out.write(0x80 or 126); out.write(n shr 8); out.write(n and 0xFF) }
                else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((n.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            out.write(mask, 0, 4)
            for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
            return out.toByteArray()
        }

        class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

        private fun readFully(inp: InputStream, b: ByteArray) {
            var o = 0
            while (o < b.size) { val n = inp.read(b, o, b.size - o); if (n < 0) throw java.io.EOFException(); o += n }
        }

        /** Reads one frame from the server. Payloads larger than [maxBytes] are read and dropped (spectrum, audio...). */
        fun read(inp: InputStream, maxBytes: Int = 1 shl 16): Frame {
            val h = ByteArray(2); readFully(inp, h)
            val fin = (h[0].toInt() and 0x80) != 0
            val op = h[0].toInt() and 0x0F
            val masked = (h[1].toInt() and 0x80) != 0
            var len = (h[1].toInt() and 0x7F).toLong()
            if (len == 126L) { val e = ByteArray(2); readFully(inp, e); len = ((e[0].toInt() and 0xFF) shl 8 or (e[1].toInt() and 0xFF)).toLong() }
            else if (len == 127L) { val e = ByteArray(8); readFully(inp, e); len = 0; for (x in e) len = (len shl 8) or (x.toLong() and 0xFF) }
            val mask = if (masked) ByteArray(4).also { readFully(inp, it) } else null
            if (len > maxBytes) {
                var left = len
                val skip = ByteArray(8192)
                while (left > 0) { val n = inp.read(skip, 0, minOf(left, skip.size.toLong()).toInt()); if (n < 0) throw java.io.EOFException(); left -= n }
                return Frame(fin, op, ByteArray(0))
            }
            val p = ByteArray(len.toInt()); readFully(inp, p)
            if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i % 4].toInt()).toByte()
            return Frame(fin, op, p)
        }
    }

    // ------------------------------------------------------------------ SpyServer

    object SpyServer {
        const val PROTOCOL_VERSION = (2 shl 24) or (0 shl 16) or 1700
        const val CMD_HELLO = 0
        const val CMD_SET_SETTING = 2
        const val CMD_PING = 3
        const val SET_STREAMING_MODE = 0
        const val SET_STREAMING_ENABLED = 1
        const val SET_GAIN = 2
        const val SET_IQ_FREQUENCY = 101
        const val SET_FFT_FORMAT = 200
        const val SET_FFT_FREQUENCY = 201
        const val SET_FFT_DECIMATION = 202
        const val SET_FFT_DISPLAY_PIXELS = 205
        const val STREAM_MODE_FFT_ONLY = 4
        const val FORMAT_UINT8 = 1
        const val MSG_DEVICE_INFO = 0
        const val MSG_CLIENT_SYNC = 1
        const val HEADER_BYTES = 20

        private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

        fun command(type: Int, body: ByteArray): ByteArray = le(8 + body.size).putInt(type).putInt(body.size).put(body).array()

        fun hello(appName: String): ByteArray {
            val name = appName.toByteArray(Charsets.US_ASCII)
            return command(CMD_HELLO, le(4 + name.size).putInt(PROTOCOL_VERSION).put(name).array())
        }

        fun setting(setting: Int, value: Int): ByteArray = command(CMD_SET_SETTING, le(8).putInt(setting).putInt(value).array())

        /** Settings that keep the device running with the least traffic: a 100-pixel spectrum, 8 bits. */
        fun lightStream(): List<ByteArray> = listOf(
            setting(SET_STREAMING_MODE, STREAM_MODE_FFT_ONLY), setting(SET_FFT_FORMAT, FORMAT_UINT8),
            setting(SET_FFT_DISPLAY_PIXELS, 100), setting(SET_FFT_DECIMATION, 0), setting(SET_STREAMING_ENABLED, 1),
        )

        fun tune(hz: Long): List<ByteArray> = listOf(setting(SET_IQ_FREQUENCY, hz.toInt()), setting(SET_FFT_FREQUENCY, hz.toInt()))

        data class Header(val protocolId: Int, val messageType: Int, val streamType: Int, val sequence: Int, val bodySize: Int) {
            /** The low 16 bits are the message type proper; the high ones carry flags. */
            val type: Int get() = messageType and 0xFFFF
        }

        fun header(b: ByteArray): Header {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return Header(bb.int, bb.int, bb.int, bb.int, bb.int)
        }

        data class DeviceInfo(val deviceType: Int, val serial: Long, val maxSampleRate: Long, val maxGainIndex: Int, val minHz: Long, val maxHz: Long) {
            val name: String get() = when (deviceType) { 1 -> "Airspy R2 / Mini"; 2 -> "Airspy HF+"; 3 -> "RTL-SDR"; else -> "device $deviceType" }
        }

        fun deviceInfo(b: ByteArray): DeviceInfo? {
            if (b.size < 48) return null
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.int; val serial = bb.int.toLong() and 0xFFFFFFFFL; val maxSr = bb.int.toLong() and 0xFFFFFFFFL
            bb.int; bb.int; bb.int                                       // max bandwidth, decimation stages, gain stages
            val maxGain = bb.int; val minF = bb.int.toLong() and 0xFFFFFFFFL; val maxF = bb.int.toLong() and 0xFFFFFFFFL
            return DeviceInfo(type, serial, maxSr, maxGain, minF, maxF)
        }

        data class Sync(val canControl: Boolean, val gain: Int, val deviceCenterHz: Long, val iqCenterHz: Long)

        fun sync(b: ByteArray): Sync? {
            if (b.size < 16) return null
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return Sync(bb.int != 0, bb.int, bb.int.toLong() and 0xFFFFFFFFL, bb.int.toLong() and 0xFFFFFFFFL)
        }
    }
}
