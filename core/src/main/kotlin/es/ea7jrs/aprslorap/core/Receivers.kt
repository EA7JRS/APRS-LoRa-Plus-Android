/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * More trackers used as receivers besides the main one (which keeps the control service and transmits): each one listens in
 * its own mode and frequency (APRS LoRa, APRS FSK...) and its KISS frames go to Stations and Messages.
 * Reached over Bluetooth LE (KISS service only, no PIN) or WiFi (the tracker's KISS TCP server, port 8001 by default).
 */
object Receivers {
    const val KISS_TCP_PORT = 8001

    enum class Kind { BLE, TCP }

    data class Entry(val kind: Kind, val address: String, val name: String, val enabled: Boolean = true) {
        /** Stable key: "ble:MAC" or "tcp:host:port". */
        val key: String get() = kind.name.lowercase() + ":" + if (kind == Kind.BLE) address.uppercase() else address
        val label: String get() = name.ifBlank { address }
        /** TCP: host and port. */
        fun hostPort(): Pair<String, Int>? {
            if (kind != Kind.TCP) return null
            val i = address.lastIndexOf(':')
            if (i <= 0) return address.trim() to KISS_TCP_PORT
            val p = address.substring(i + 1).toIntOrNull() ?: return null
            return address.substring(0, i).trim() to p
        }
    }

    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    /** Checks and normalises what the user typed: a Bluetooth address, or host[:port]. Null if neither. */
    fun entry(address: String, name: String): Entry? {
        val a = address.trim()
        if (MAC.matches(a)) return Entry(Kind.BLE, a.uppercase(), name.trim())
        if (a.isEmpty() || a.contains(' ') || a.contains('|')) return null
        val e = Entry(Kind.TCP, a, name.trim())
        val hp = e.hostPort() ?: return null
        if (hp.first.isEmpty() || hp.second !in 1..65535) return null
        return Entry(Kind.TCP, "${hp.first}:${hp.second}", name.trim())
    }

    /** One line per receiver: kind|address|enabled|name. */
    fun encode(list: List<Entry>): String = list.joinToString("\n") { "${it.kind}|${it.address}|${if (it.enabled) 1 else 0}|${it.name.replace('\n', ' ')}" }

    fun decode(text: String): List<Entry> = text.lines().mapNotNull { l ->
        val p = l.split('|', limit = 4)
        if (p.size < 3) return@mapNotNull null
        val k = runCatching { Kind.valueOf(p[0]) }.getOrNull() ?: return@mapNotNull null
        Entry(k, p[1], p.getOrElse(3) { "" }, p[2] == "1")
    }
}
