/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Firmware of the tracker: the release site published by the firmware CI (tools/make_web_assets.py), with the web installer
 * (USB, esp-web-tools) at its root and one ota/<board>.json per board: {"version","size","sha256","elfSha","url","ota"}.
 */
object FirmwareSite {
    const val BASE = "https://ea7jrs.github.io/APRS-LoRa-Plus/"
    const val INSTALLER = BASE
    const val RELEASES = "https://github.com/EA7JRS/APRS-LoRa-Plus/releases"

    data class Release(
        val version: String, val size: Long, val sha256: String, val url: String, val secondSlot: Boolean,
        /** app_elf_sha256 of the published build (tools/make_web_assets.py); null on sites published before it existed. */
        val elfSha: String? = null,
    )

    /** Whether the firmware in the tracker is the build published by EA7JRS (informative: a modified firmware could fake it). */
    enum class Origin { OFFICIAL, CUSTOM, UNKNOWN }

    /**
     * Compares the ELF hash the tracker reports (event info "elf") with the published one. Only the latest release is published,
     * so another version cannot be checked (UNKNOWN), and neither can a tracker or a site without the hash.
     */
    fun origin(installedVersion: String, trackerElf: String, latest: Release?): Origin {
        val pub = latest?.elfSha ?: return Origin.UNKNOWN
        if (!trackerElf.lowercase().matches(Regex("[0-9a-f]{64}"))) return Origin.UNKNOWN
        if (versionNumber(installedVersion) != versionNumber(latest.version)) return Origin.UNKNOWN
        return if (trackerElf.equals(pub, ignoreCase = true)) Origin.OFFICIAL else Origin.CUSTOM
    }

    fun manifestUrl(board: String): String = BASE + "ota/" + java.net.URLEncoder.encode(board, "UTF-8") + ".json"

    fun absolute(url: String): String = if (url.startsWith("http://") || url.startsWith("https://")) url else BASE + url.trimStart('/')

    fun parse(json: String): Release? {
        val o = Json.parseObject(json) ?: return null
        val version = o.str("version") ?: return null
        val size = o.long("size") ?: return null
        val sha = o.str("sha256")?.lowercase() ?: return null
        val url = o.str("url") ?: return null
        if (!sha.matches(Regex("[0-9a-f]{64}")) || size < 100_000) return null
        val elf = o.str("elfSha")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        return Release(version, size, sha, absolute(url), o.bool("ota") ?: false, elf)
    }

    /** "5.3.2" -> 5003002, the same rule as the firmware (three numbers, the rest ignored). */
    fun versionNumber(v: String): Long {
        val parts = Regex("""\d+""").findAll(v.substringBefore('-')).map { it.value.toLongOrNull() ?: 0 }.take(3).toList()
        return (parts.getOrElse(0) { 0 } * 1_000_000) + (parts.getOrElse(1) { 0 } * 1_000) + parts.getOrElse(2) { 0 }
    }

    fun isNewer(latest: String, installed: String): Boolean = versionNumber(latest) > versionNumber(installed)

    fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/**
 * Flow control of a firmware image sent over BLE: the tracker confirms every [block] bytes written to the flash and the phone
 * never has more than [window] blocks unconfirmed (the tracker's buffer holds 16 KB, the window is 8 KB).
 */
class OtaWindow(val total: Int, private val block: Int = 4096, private val window: Int = 2) {
    var sent = 0
        private set
    var confirmed = 0
        private set

    /** The next piece to send (at most [piece] bytes), or null if the window is full or everything was sent. */
    fun next(piece: Int): IntRange? {
        if (sent >= total) return null
        val limit = minOf(total, confirmed + window * block)
        if (sent >= limit) return null
        val end = minOf(limit, sent + piece)
        val r = sent until end
        sent = end
        return r
    }

    fun confirm(offset: Int) { if (offset > confirmed) confirmed = minOf(offset, total) }

    val complete: Boolean get() = confirmed >= total
    val percent: Int get() = if (total == 0) 0 else (confirmed * 100L / total).toInt()
}
