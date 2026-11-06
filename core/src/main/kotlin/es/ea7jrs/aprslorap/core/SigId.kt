/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale
import kotlin.math.abs

/**
 * Signal identification with the Artemis database (AresValley, built from sigidwiki.com):
 * - the release (`config/release-info.json` of Artemis) is a tar whose first file is `data.sqlite` (~1 MB): only that part is
 *   downloaded, with an HTTP range;
 * - spectrum images and audio come one by one, when opened, from the Artemis-Database repository (`static/<pageid>/media/`),
 *   whose `static/index.json` maps the signal names to page IDs.
 * The search runs in memory over [Signal] (about 600 signals).
 */
object SigId {
    const val RELEASE_INFO_URL = "https://raw.githubusercontent.com/AresValley/Artemis/master/config/release-info.json"
    const val INDEX_URL = "https://raw.githubusercontent.com/AresValley/Artemis-Database/master/static/index.json"
    const val ATTRIBUTION = "Artemis database (AresValley), from sigidwiki.com"
    fun mediaUrl(pageId: String, ext: String) = "https://raw.githubusercontent.com/AresValley/Artemis-Database/master/static/$pageId/media/1.$ext"

    data class Release(val version: Int, val url: String, val sha256: String, val totalBytes: Long)

    fun parseRelease(json: String): Release? {
        val o = (try { Json.parseObject(json) } catch (_: Exception) { null })?.obj("sigID_DB") ?: return null
        return Release(o.int("version") ?: return null, o.str("url") ?: return null, o.str("sha256_hash") ?: "", o.long("total_bytes") ?: 0)
    }

    /** Signal name -> page ID, from `static/index.json`. */
    fun parseIndex(json: String): Map<String, String> {
        val o = try { Json.parseObject(json) } catch (_: Exception) { null } ?: return emptyMap()
        val m = HashMap<String, String>()
        for ((k, v) in o) (v as? Map<*, *>)?.get("title")?.let { if (it is String) m[it] = k }
        return m
    }

    // ------------------------------------------------------------------ tar

    data class TarEntry(val name: String, val size: Long, val type: Char)

    /** One 512-byte ustar header; null if it is not one (bad checksum) or is the end of the archive (all zeros). */
    fun tarHeader(b: ByteArray, off: Int = 0): TarEntry? {
        if (b.size < off + 512) return null
        var zero = true
        for (i in 0 until 512) if (b[off + i].toInt() != 0) { zero = false; break }
        if (zero) return null
        fun str(o: Int, n: Int): String { var e = o; while (e < o + n && b[off + e].toInt() != 0) e++; return String(b, off + o, e - o, Charsets.ISO_8859_1) }
        fun oct(o: Int, n: Int): Long = str(o, n).trim().ifEmpty { "0" }.toLong(8)
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) 32 else (b[off + i].toInt() and 0xFF)
        if (sum != oct(148, 8)) return null
        val prefix = if (str(257, 6).startsWith("ustar")) str(345, 155) else ""
        val name = (if (prefix.isNotEmpty()) "$prefix/" else "") + str(0, 100)
        val t = b[off + 156].toInt().toChar().let { if (it == '\u0000') '0' else it }
        return TarEntry(name, oct(124, 12), t)
    }

    /**
     * Walks the headers in the first bytes of a tar ([head], from offset 0) and returns the data offset and size of the member
     * whose name ends with [suffix] (pax extended headers and directories are skipped). Null if not in [head].
     */
    fun findMember(head: ByteArray, suffix: String): Pair<Long, Long>? {
        var off = 0L
        while (off + 512 <= head.size) {
            val e = tarHeader(head, off.toInt()) ?: return null
            val data = off + 512
            if ((e.type == '0' || e.type == '7') && e.name.endsWith(suffix)) return data to e.size
            off = data + (e.size + 511) / 512 * 512
        }
        return null
    }

    // ------------------------------------------------------------------ signals and search

    data class Doc(val id: Int, val type: String, val ext: String)

    data class Signal(
        val id: Int, val name: String, val url: String, val description: String,
        val frequencies: List<Long>, val bandwidths: List<Long>, val modes: List<String>, val modulations: List<String>,
        val categories: List<String>, val locations: List<String>, val docs: List<Doc> = emptyList(),
    ) {
        val freqText: String get() = when {
            frequencies.isEmpty() -> "—"
            frequencies.size == 2 -> hz(frequencies.min()) + " – " + hz(frequencies.max())
            else -> frequencies.sorted().joinToString(", ") { hz(it) }
        }
        val bwText: String get() = if (bandwidths.isEmpty()) "—" else bandwidths.sorted().joinToString(", ") { hz(it) }
        val hasImage: Boolean get() = docs.any { it.type.equals("Image", true) }
        val hasAudio: Boolean get() = docs.any { it.type.equals("Audio", true) }
    }

    fun hz(v: Long): String = when {
        v >= 1_000_000_000 -> trim(v / 1e9) + " GHz"
        v >= 1_000_000 -> trim(v / 1e6) + " MHz"
        v >= 1_000 -> trim(v / 1e3) + " kHz"
        else -> "$v Hz"
    }
    private fun trim(x: Double) = String.format(Locale.US, "%.4f", x).trimEnd('0').trimEnd('.')

    /**
     * How far [f] is from the signal's frequencies (relative, 0 = inside). Two values are a band (lower, upper); one or more
     * than two are spot frequencies or band markers, matched within [tol] (relative). Null = no match.
     */
    fun freqDistance(values: List<Long>, f: Long, tol: Double): Double? {
        if (values.isEmpty() || f <= 0) return null
        if (values.size == 2) {
            val lo = values.min(); val hi = values.max()
            if (f in lo..hi) return 0.0
            val d = if (f < lo) (lo - f).toDouble() / lo else (f - hi).toDouble() / hi
            return if (d <= tol) d else null
        }
        val d = values.minOf { abs(it - f).toDouble() / it }
        return if (d <= tol) d else null
    }

    data class Query(
        val text: String = "", val freqHz: Long? = null, val freqTol: Double = 0.02, val bandwidthHz: Long? = null, val bwTol: Double = 0.3,
        val mode: String = "", val category: String = "",
    )

    /** Signals matching [q], best first (frequency distance, then bandwidth distance, then name). */
    fun search(signals: List<Signal>, q: Query): List<Signal> {
        val words = q.text.lowercase(Locale.ROOT).split(' ', ',').filter { it.isNotBlank() }
        val scored = ArrayList<Pair<Signal, Double>>()
        for (s in signals) {
            var score = 0.0
            if (words.isNotEmpty()) {
                val hay = (s.name + " " + s.description + " " + s.modes.joinToString(" ") + " " + s.modulations.joinToString(" ")).lowercase(Locale.ROOT)
                if (!words.all { hay.contains(it) }) continue
                if (!words.all { s.name.lowercase(Locale.ROOT).contains(it) }) score += 0.5
            }
            if (q.freqHz != null) score += freqDistance(s.frequencies, q.freqHz, q.freqTol)?.times(10) ?: continue
            if (q.bandwidthHz != null) {
                if (s.bandwidths.isEmpty()) continue
                val d = s.bandwidths.minOf { abs(it - q.bandwidthHz).toDouble() / maxOf(it, 1) }
                if (d > q.bwTol) continue
                score += d
            }
            if (q.mode.isNotBlank() && (s.modes + s.modulations).none { it.equals(q.mode, true) }) continue
            if (q.category.isNotBlank() && s.categories.none { it.equals(q.category, true) }) continue
            scored.add(s to score)
        }
        return scored.sortedWith(compareBy<Pair<Signal, Double>> { it.second }.thenBy { it.first.name.lowercase(Locale.ROOT) }).map { it.first }
    }

    /** sigidwiki descriptions are Markdown: headings, bold and links made plain. */
    fun plainText(md: String): String = md
        .replace(Regex("(?m)^#{1,6}\\s*(.*)$")) { it.groupValues[1].uppercase(Locale.ROOT) }
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** A database file really is SQLite (header "SQLite format 3"). */
    fun isSqlite(head: ByteArray): Boolean = head.size >= 16 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
}
