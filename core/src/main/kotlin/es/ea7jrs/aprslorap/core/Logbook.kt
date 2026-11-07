/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/** ADIF 3 text: reading and writing (field names in upper case). */
object Adif {
    data class File(val header: Map<String, String>, val records: List<Map<String, String>>)

    fun parse(text: String): File {
        val header = LinkedHashMap<String, String>()
        val records = ArrayList<Map<String, String>>()
        var cur = LinkedHashMap<String, String>()
        var inHeader = !text.trimStart().startsWith("<")                 // a header starts with free text, a bare file with "<"
        var i = 0
        while (true) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            val gt = text.indexOf('>', lt)
            if (gt < 0) break
            val spec = text.substring(lt + 1, gt)
            val parts = spec.split(':')
            val name = parts[0].trim().uppercase()
            i = gt + 1
            when (name) {
                "EOH" -> { header.putAll(cur); cur = LinkedHashMap(); inHeader = false; continue }
                "EOR" -> { if (cur.isNotEmpty()) records.add(cur); cur = LinkedHashMap(); inHeader = false; continue }
            }
            val len = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
            val end = (i + len).coerceAtMost(text.length)
            val v = text.substring(i, end)
            i = end
            if (inHeader && header.isEmpty() && name == "ADIF_VER") { header[name] = v; continue }
            cur[name] = v
        }
        if (inHeader) header.putAll(cur)
        return File(header, records)
    }

    fun field(name: String, value: String): String = "<${name.uppercase()}:${value.length}>$value"

    fun record(fields: Map<String, String>): String =
        fields.entries.filter { it.value.isNotEmpty() }.joinToString(" ") { field(it.key, it.value) } + " <EOR>"

    fun file(records: List<Map<String, String>>, programId: String, version: String): String {
        val sb = StringBuilder("ADIF export of $programId\n")
        sb.append(field("ADIF_VER", "3.1.4")).append(' ').append(field("PROGRAMID", programId)).append(' ')
            .append(field("PROGRAMVERSION", version)).append(" <EOH>\n")
        for (r in records) sb.append(record(r)).append('\n')
        return sb.toString()
    }

    /** ADIF band ("20m") of a frequency in Hz; null outside the amateur bands. */
    fun band(hz: Long): String? {
        val mhz = hz / 1e6
        return BANDS.firstOrNull { mhz >= it.second && mhz <= it.third }?.first
    }

    private val BANDS = listOf(
        Triple("2190m", 0.1357, 0.1378), Triple("630m", 0.472, 0.479), Triple("160m", 1.8, 2.0), Triple("80m", 3.5, 4.0),
        Triple("60m", 5.06, 5.45), Triple("40m", 7.0, 7.3), Triple("30m", 10.1, 10.15), Triple("20m", 14.0, 14.35),
        Triple("17m", 18.068, 18.168), Triple("15m", 21.0, 21.45), Triple("12m", 24.89, 24.99), Triple("10m", 28.0, 29.7),
        Triple("8m", 40.0, 45.0), Triple("6m", 50.0, 54.0), Triple("4m", 70.0, 71.0), Triple("2m", 144.0, 148.0),
        Triple("1.25m", 222.0, 225.0), Triple("70cm", 420.0, 450.0), Triple("33cm", 902.0, 928.0), Triple("23cm", 1240.0, 1300.0),
        Triple("13cm", 2300.0, 2450.0), Triple("9cm", 3300.0, 3500.0), Triple("6cm", 5650.0, 5925.0), Triple("3cm", 10000.0, 10500.0),
    )

    /** Modes offered when logging by hand: label to ADIF MODE / SUBMODE. */
    val MODES = listOf(
        "CW" to ("CW" to ""), "USB" to ("SSB" to "USB"), "LSB" to ("SSB" to "LSB"), "FM" to ("FM" to ""), "AM" to ("AM" to ""),
        "FT8" to ("FT8" to ""), "FT4" to ("MFSK" to "FT4"), "RTTY" to ("RTTY" to ""), "PSK31" to ("PSK" to "BPSK31"),
        "FST4" to ("MFSK" to "FST4"), "JS8" to ("MFSK" to "JS8"), "Q65" to ("MFSK" to "Q65"), "C4FM" to ("DIGITALVOICE" to "C4FM"),
        "DSTAR" to ("DIGITALVOICE" to "DSTAR"), "DMR" to ("DIGITALVOICE" to "DMR"),
    )

    /** Default report for a mode. */
    fun defaultRst(mode: String) = when (mode) { "CW", "RTTY", "PSK" -> "599"; "SSB", "FM", "AM", "DIGITALVOICE" -> "59"; else -> "-10" }
}

/** One contact: ADIF fields (upper-case names) plus our own APP_ALP_* fields. */
data class Qso(val fields: Map<String, String>) {
    operator fun get(k: String): String = fields[k] ?: ""
    val id get() = this["APP_ALP_ID"]
    val call get() = this["CALL"].uppercase()
    val date get() = this["QSO_DATE"]
    val time get() = this["TIME_ON"].padEnd(6, '0').take(6)
    val band get() = this["BAND"].lowercase()
    val mode get() = this["MODE"].uppercase()
    val submode get() = this["SUBMODE"].uppercase()
    val freqMHz get() = this["FREQ"]
    /** Label for the screen: the submode if there is one (FT4, USB...), else the mode. */
    val modeLabel get() = submode.ifEmpty { mode }

    val qrzSent get() = this["QRZCOM_QSO_UPLOAD_STATUS"] == "Y"
    val lotwSent get() = this["LOTW_QSL_SENT"] == "Y"
    val eqslSent get() = this["EQSL_QSL_SENT"] == "Y"
    val wrlSent get() = this["APP_ALP_WRL_SENT"] == "Y"
    val qrzConfirmed get() = this["APP_QRZLOG_STATUS"] == "C"
    val lotwConfirmed get() = this["LOTW_QSL_RCVD"] == "Y"
    val eqslConfirmed get() = this["EQSL_QSL_RCVD"] == "Y"

    fun with(vararg kv: Pair<String, String>): Qso = Qso(LinkedHashMap(fields).apply { kv.forEach { put(it.first, it.second) } })

    /** Minutes since 1970 of the start (for matching confirmations). */
    val minutes: Long get() {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.clear()
        val d = date; val t = time
        if (d.length != 8) return 0
        c.set(d.substring(0, 4).toInt(), d.substring(4, 6).toInt() - 1, d.substring(6, 8).toInt(),
            t.substring(0, 2).toIntOrNull() ?: 0, t.substring(2, 4).toIntOrNull() ?: 0, 0)
        return c.timeInMillis / 60_000
    }

    /** The ADIF record to send to others: without our internal fields. */
    fun export(): Map<String, String> = fields.filterKeys { !it.startsWith("APP_ALP_") }

    companion object {
        /** A new contact; [utcMs] start time, [freqHz] 0 = unknown. */
        fun create(id: String, call: String, utcMs: Long, freqHz: Long, mode: String, submode: String, rstSent: String, rstRcvd: String,
                   stationCall: String, myGrid: String, name: String = "", grid: String = "", comment: String = "", txPwr: String = ""): Qso {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
            val m = LinkedHashMap<String, String>()
            m["CALL"] = call.trim().uppercase()
            m["QSO_DATE"] = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
            m["TIME_ON"] = String.format(Locale.US, "%02d%02d%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
            if (freqHz > 0) {
                Adif.band(freqHz)?.let { m["BAND"] = it }
                m["FREQ"] = String.format(Locale.US, "%.6f", freqHz / 1e6)
            }
            m["MODE"] = mode; if (submode.isNotEmpty()) m["SUBMODE"] = submode
            m["RST_SENT"] = rstSent; m["RST_RCVD"] = rstRcvd
            if (name.isNotBlank()) m["NAME"] = name.trim()
            if (grid.isNotBlank()) m["GRIDSQUARE"] = grid.trim()
            if (comment.isNotBlank()) m["COMMENT"] = comment.trim()
            if (txPwr.isNotBlank()) m["TX_PWR"] = txPwr.trim()
            m["STATION_CALLSIGN"] = Spots.stationCall(stationCall)
            if (myGrid.isNotBlank()) m["MY_GRIDSQUARE"] = myGrid
            m["APP_ALP_ID"] = id
            return Qso(m)
        }
    }
}

/** The log: contacts, merging of imported logs and of confirmations. */
class Logbook(initial: List<Qso> = emptyList()) {
    private val list = ArrayList(initial)
    val qsos: List<Qso> get() = synchronized(this) { list.toList() }

    @Synchronized fun add(q: Qso) { list.add(q) }
    @Synchronized fun replace(q: Qso) { val i = list.indexOfFirst { it.id == q.id }; if (i >= 0) list[i] = q else list.add(q) }
    @Synchronized fun remove(id: String) { list.removeAll { it.id == id } }

    /** Same contact: call, band, date-time within [toleranceMin], same mode for LoTW (SSB = USB = LSB...). */
    private fun same(a: Qso, b: Qso, toleranceMin: Long): Boolean =
        a.call == b.call && a.band.isNotEmpty() && a.band == b.band && Math.abs(a.minutes - b.minutes) <= toleranceMin &&
            Lotw.tqslMode(a.mode, a.submode) == Lotw.tqslMode(b.mode, b.submode)

    /** Imports an ADIF log (e.g. from qFT8): new contacts are added, known ones only gain missing fields. Returns added, merged. */
    @Synchronized
    fun import(text: String, newId: () -> String): Pair<Int, Int> {
        var added = 0; var merged = 0
        for (r in Adif.parse(text).records) {
            val q0 = Qso(LinkedHashMap(r.filterKeys { !it.startsWith("APP_ALP_") }))
            if (q0.call.isEmpty() || q0.date.length != 8) continue
            val q = if (q0.band.isEmpty() && q0.freqMHz.isNotEmpty())
                q0.freqMHz.toDoubleOrNull()?.let { f -> Adif.band((f * 1e6).toLong())?.let { q0.with("BAND" to it) } } ?: q0 else q0
            val i = list.indexOfFirst { same(it, q, 2) }
            if (i >= 0) {
                val m = LinkedHashMap(list[i].fields)
                for ((k, v) in q.fields) if (v.isNotEmpty() && (m[k].isNullOrEmpty() || k.endsWith("_RCVD") || k == "APP_QRZLOG_STATUS")) m[k] = v
                list[i] = Qso(m); merged++
            } else { list.add(q.with("APP_ALP_ID" to newId())); added++ }
        }
        return added to merged
    }

    /**
     * Confirmations from LoTW (lotwreport.adi), eQSL (inbox) or QRZ (FETCH): each record that matches one of our contacts
     * (call, band, mode, time within 30 minutes) sets [flags] on it. Returns how many contacts changed.
     */
    @Synchronized
    fun confirm(text: String, flags: (Map<String, String>) -> List<Pair<String, String>>?): Int {
        var n = 0
        for (r in Adif.parse(text).records) {
            val f = flags(r) ?: continue
            val q = Qso(r)
            val i = list.indexOfFirst { same(it, q, 30) }
            if (i < 0) continue
            val before = list[i]
            val after = before.with(*f.toTypedArray())
            if (after != before) { list[i] = after; n++ }
        }
        return n
    }

    fun adif(programId: String, version: String, internal: Boolean): String =
        Adif.file(qsos.sortedBy { it.minutes }.map { if (internal) it.fields else it.export() }, programId, version)

    companion object {
        fun load(text: String) = Logbook(Adif.parse(text).records.map { Qso(it) })

        val LOTW_CONFIRMED: (Map<String, String>) -> List<Pair<String, String>>? = { r ->
            if (r["QSL_RCVD"]?.uppercase() == "Y") listOf("LOTW_QSL_RCVD" to "Y", "LOTW_QSLRDATE" to (r["QSLRDATE"] ?: "")) else null
        }
        val EQSL_CONFIRMED: (Map<String, String>) -> List<Pair<String, String>>? = { r ->
            listOf("EQSL_QSL_RCVD" to "Y", "EQSL_QSLRDATE" to (r["QSLRDATE"] ?: r["QSO_DATE"] ?: ""))
        }
        val QRZ_CONFIRMED: (Map<String, String>) -> List<Pair<String, String>>? = { r ->
            if (r["APP_QRZLOG_STATUS"]?.uppercase() == "C") listOf("APP_QRZLOG_STATUS" to "C", "APP_QRZLOG_QSLDATE" to (r["APP_QRZLOG_QSLDATE"] ?: "")) else null
        }
    }
}

// ------------------------------------------------------------------ QRZ.com logbook

/** QRZ.com Logbook API (needs a QRZ XML subscription; the key is in the logbook's settings at QRZ). */
object QrzLog {
    const val URL = "https://logbook.qrz.com/api"

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    fun insertBody(key: String, q: Qso): String = "KEY=${enc(key.trim())}&ACTION=INSERT&ADIF=${enc(Adif.record(q.export()))}"

    /** Confirmed contacts changed since [sinceDate] (yyyy-MM-dd). */
    fun fetchBody(key: String, sinceDate: String): String =
        "KEY=${enc(key.trim())}&ACTION=FETCH&OPTION=${enc("MODSINCE:$sinceDate;STATUS:CONFIRMED;TYPE:ADIF")}"

    /** The reply: RESULT, REASON, LOGID, COUNT... and ADIF (HTML entities decoded). */
    fun parse(reply: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val a = reply.indexOf("ADIF=")
        val head = if (a >= 0) reply.substring(0, a) else reply
        for (p in head.split('&')) { val k = p.substringBefore('=', ""); if (k.isNotEmpty()) out[k.trim().uppercase()] = java.net.URLDecoder.decode(p.substringAfter('='), "UTF-8") }
        if (a >= 0) out["ADIF"] = reply.substring(a + 5).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        return out
    }

    /** OK (also "already in the logbook"), and the text to show. */
    fun insertResult(reply: String): Pair<Boolean, String> {
        val m = parse(reply)
        val r = m["RESULT"] ?: ""
        val reason = m["REASON"] ?: ""
        return when {
            r == "OK" || r == "REPLACE" -> true to "Log id ${m["LOGID"] ?: m["LOGIDS"] ?: "?"}"
            reason.contains("duplicate", ignoreCase = true) -> true to "Already in the QRZ logbook"
            r == "AUTH" -> false to "QRZ: the key has no access (XML subscription?)"
            else -> false to "QRZ: ${reason.ifEmpty { reply.take(120) }}"
        }
    }
}

// ------------------------------------------------------------------ eQSL.cc

object Eqsl {
    const val UPLOAD = "https://www.eqsl.cc/qslcard/importADIF.cfm"
    const val INBOX = "https://www.eqsl.cc/qslcard/DownloadInBox.cfm"

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    /** GET address that uploads one contact (as Wavelog / other loggers do). */
    fun uploadUrl(user: String, password: String, qthNickname: String, q: Qso): String {
        val f = LinkedHashMap<String, String>()
        f["QSO_DATE"] = q.date; f["TIME_ON"] = q.time.take(4); f["CALL"] = q.call; f["MODE"] = q.mode
        if (q.submode.isNotEmpty()) f["SUBMODE"] = q.submode
        f["BAND"] = q.band; f["RST_SENT"] = q["RST_SENT"]
        for (k in listOf("FREQ", "PROP_MODE", "SAT_NAME", "QSLMSG")) if (q[k].isNotEmpty()) f[k] = q[k]
        if (qthNickname.isNotBlank()) f["APP_EQSL_QTH_NICKNAME"] = qthNickname.trim()
        val adif = "APRSLoRaPlus " + Adif.field("ADIF_VER", "1.00") + " " + Adif.field("EQSL_USER", user.trim()) + " " +
            Adif.field("EQSL_PSWD", password) + " <EOH> " + Adif.record(f)
        return "$UPLOAD?ADIFData=" + enc(adif)
    }

    enum class Result { ADDED, DUPLICATE, LOGIN, ERROR }

    fun uploadResult(reply: String): Result = when {
        reply.contains("Result: 1 out of 1 records added", ignoreCase = true) -> Result.ADDED
        reply.contains("Duplicate", ignoreCase = true) -> Result.DUPLICATE
        reply.contains("No match on eQSL_User/eQSL_Pswd", ignoreCase = true) -> Result.LOGIN
        else -> Result.ERROR
    }

    /** POST body for the inbox (confirmations received since [since], yyyyMMdd). */
    fun inboxBody(user: String, password: String, since: String, qthNickname: String): String =
        "UserName=${enc(user.trim())}&Password=${enc(password)}&RcvdSince=${enc(since)}&QTHNickname=${enc(qthNickname.trim())}&ConfirmedOnly=1"

    /** The inbox page links the ADIF file it has built; null if there is nothing (or an error, see [inboxError]). */
    fun inboxLink(html: String): String? {
        val m = Regex("(?i)<a\\s[^>]*href=\"?([^\" >]+\\.adi)\"?").find(html) ?: return null
        val link = m.groupValues[1]
        return if (link.startsWith("http")) link else "https://www.eqsl.cc/qslcard/" + link.removePrefix("../").removePrefix("/")
    }

    fun inboxError(html: String): String? = when {
        html.contains("You have no log entries", ignoreCase = true) -> null
        html.contains("No such Username/Password", ignoreCase = true) -> "eQSL: wrong user or password"
        html.contains("Your ADIF log file has been built", ignoreCase = true) -> null
        else -> "eQSL: unexpected answer"
    }
}

// ------------------------------------------------------------------ LoTW (TrustedQSL signing)

/**
 * Logbook of The World: contacts signed with the callsign certificate of TrustedQSL (.p12) and sent as a .tq8 file
 * (gzip of a "GAbbi" text). Signed data and record layout as TrustedQSL (sigspec LOTW V2.0) and TaffyQSL (GPL-3).
 */
object Lotw {
    const val UPLOAD = "https://lotw.arrl.org/lotw/upload"
    const val REPORT = "https://lotw.arrl.org/lotwuser/lotwreport.adi"
    const val SIGN_FIELD = "SIGN_LOTW_V2.0"

    data class Station(val call: String, val dxcc: Int, val grid: String, val cqz: String, val ituz: String, val iota: String = "")

    private val STATION_FIELDS = listOf("AU_STATE", "CA_PROVINCE", "CA_US_PARK", "CN_PROVINCE", "CQZ", "DX_US_PARK", "FI_KUNTA", "GRIDSQUARE",
        "IOTA", "ITUZ", "JA_CITY_GUN_KU", "JA_PREFECTURE", "RU_OBLAST", "US_COUNTY", "US_PARK", "US_STATE")

    /** ADIF mode / submode -> TQSL mode (TrustedQSL config: FT4 = MFSK/FT4, USB = SSB...). */
    fun tqslMode(mode: String, submode: String): String {
        val m = mode.uppercase(); val s = submode.uppercase()
        MODE_MAP["$m/$s"]?.let { return it }
        MODE_MAP["$m/"]?.let { return it }
        return m
    }
    private val MODE_MAP: Map<String, String> = (
        "CHIP/CHIP128=CHIP CHIP/CHIP64=CHIP CLO/=CLOVER CW/PCW=CW DOMINO/DOMINOEX=DOMINO DOMINO/DOMINOF=DOMINO HELL/FMHELL=HELL " +
        "HELL/FSKHELL=HELL HELL/HELL80=HELL HELL/HFSK=HFSK HELL/PSKHELL=HELL ISCAT/ISCAT-A=ISCAT ISCAT/ISCAT-B=ISCAT JT4/JT4A=JT4 " +
        "JT4/JT4B=JT4 JT4/JT4C=JT4 JT4/JT4D=JT4 JT4/JT4E=JT4 JT4/JT4F=JT4 JT4/JT4G=JT4 JT65/JT65A=JT65 JT65/JT65B=JT65 JT65/JT65B2=JT65 " +
        "JT65/JT65C=JT65 JT65/JT65C2=JT65 JT9/JT9-1=JT9 JT9/JT9-10=JT9 JT9/JT9-2=JT9 JT9/JT9-30=JT9 JT9/JT9-5=JT9 JT9/JT9A=JT9 JT9/JT9B=JT9 " +
        "JT9/JT9C=JT9 JT9/JT9D=JT9 JT9/JT9E=JT9 JT9/JT9F=JT9 JT9/JT9G=JT9 JT9/JT9H=JT9 MFSK/FSQCALL=DATA MFSK/FST4=FST4 MFSK/FT4=FT4 " +
        "MFSK/JS8=DATA MFSK/MFSK11=DATA MFSK/MFSK128=DATA MFSK/MFSK16=MFSK16 MFSK/MFSK22=DATA MFSK/MFSK31=DATA MFSK/MFSK32=DATA " +
        "MFSK/MFSK4=DATA MFSK/MFSK64=DATA MFSK/MFSK8=MFSK8 MFSK/Q65=Q65 MFSK/=DATA OPERA/OPERA-BEACON=OPERA OPERA/OPERA-QSO=OPERA " +
        "PAC/PAC2=PACTOR PAC/PAC3=PACTOR PAC/PAC4=PACTOR PAC/=PACTOR PAX/PAX2=PAX PKT/=PACKET PSK/BPSK125=PSK125 PSK/BPSK31=PSK31 " +
        "PSK/BPSK63=PSK63 PSK/FSK31=FSK31 PSK/PSK10=PSK10 PSK/PSK1000=DATA PSK/PSK125=PSK125 PSK/PSK250=DATA PSK/PSK31=PSK31 PSK/PSK500=DATA " +
        "PSK/PSK63=PSK63 PSK/PSK63F=PSK63F PSK/PSKAM10=PSKAM PSK/PSKAM31=PSKAM PSK/PSKAM50=PSKAM PSK/PSKFEC31=PSKFEC31 PSK/QPSK125=PSK125 " +
        "PSK/QPSK250=DATA PSK/QPSK31=PSK31 PSK/QPSK500=DATA PSK/QPSK63=PSK63 PSK/SIM31=DATA PSK/=DATA PSK2K/PSK2K=PSK2K QRA64/QRA64A=QRA64 " +
        "QRA64/QRA64B=QRA64 QRA64/QRA64C=QRA64 QRA64/QRA64D=QRA64 QRA64/QRA64E=QRA64 ROS/ROS-EME=ROS ROS/ROS-HF=ROS ROS/ROS-MF=ROS " +
        "RTTY/ASCI=RTTY SSB/LSB=SSB SSB/USB=SSB THRB/THRBX=THROB THRB/=THROB TOR/AMTORFEC=AMTOR TOR/GTOR=GTOR TOR/=AMTOR V4/=DATA " +
        "DIGITALVOICE/C4FM=C4FM DIGITALVOICE/DSTAR=DSTAR DIGITALVOICE/DMR=DIGITALVOICE"
        ).split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun stationFields(st: Station): Map<String, String> = LinkedHashMap<String, String>().apply {
        if (st.grid.isNotBlank()) put("GRIDSQUARE", st.grid.trim().uppercase().take(6))
        if (st.cqz.isNotBlank()) put("CQZ", st.cqz.trim())
        if (st.ituz.isNotBlank()) put("ITUZ", st.ituz.trim())
        if (st.iota.isNotBlank()) put("IOTA", st.iota.trim().uppercase())
    }

    fun stationSigndata(st: Station): String {
        val f = stationFields(st)
        return STATION_FIELDS.mapNotNull { f[it]?.trim()?.uppercase()?.takeIf { v -> v.isNotEmpty() } }.joinToString("")
    }

    /** The contact as LoTW takes it: band, TQSL mode, date and time in TQSL format. Null if it cannot go (no band...). */
    data class Contact(val call: String, val band: String, val mode: String, val freq: String, val date: String, val time: String,
                       val propMode: String, val satName: String)

    fun contact(q: Qso): Contact? {
        if (q.call.isEmpty() || q.date.length != 8 || q.mode.isEmpty()) return null
        val band = q.band.ifEmpty { q.freqMHz.toDoubleOrNull()?.let { Adif.band((it * 1e6).toLong()) } ?: "" }.uppercase()
        if (band.isEmpty()) return null
        val t = q.time
        return Contact(q.call, band, tqslMode(q.mode, q.submode), q.freqMHz,
            "${q.date.substring(0, 4)}-${q.date.substring(4, 6)}-${q.date.substring(6, 8)}",
            "${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}Z", q["PROP_MODE"].uppercase(), q["SAT_NAME"].uppercase())
    }

    /** Signed data: station fields, then BAND, BAND_RX, CALL, FREQ, FREQ_RX, MODE, PROP_MODE, QSO_DATE, QSO_TIME, SAT_NAME. */
    fun contactSigndata(stationSig: String, c: Contact): String {
        val sb = StringBuilder(stationSig)
        for (v in listOf(c.band, "", c.call, c.freq, "", c.mode, c.propMode, c.date, c.time, c.satName)) if (v.isNotBlank()) sb.append(v.trim().uppercase())
        return sb.toString()
    }

    private fun line(sb: StringBuilder, name: String, v: String) { if (v.isNotEmpty()) sb.append("<$name:${v.length}>$v\n") }

    /** The GAbbi text: certificate, station, signed contacts. [certDer]: the callsign certificate (X.509, DER). */
    fun gabbi(certDer: ByteArray, st: Station, signed: List<Triple<Contact, String, String>>, ident: String): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certDer) + "\n"
        val sb = StringBuilder()
        line(sb, "Ident", ident); sb.append('\n')
        sb.append("<Rec_Type:5>tCERT\n"); line(sb, "CERT_UID", "1")
        sb.append("<CERTIFICATE:${b64.length}>").append(b64).append("<eor>\n\n")
        sb.append("<Rec_Type:8>tSTATION\n"); line(sb, "STATION_UID", "1"); line(sb, "CERT_UID", "1")
        line(sb, "CALL", st.call.uppercase()); line(sb, "DXCC", st.dxcc.toString())
        for ((k, v) in stationFields(st)) line(sb, k, v)
        sb.append("<eor>\n\n")
        for ((c, sig, data) in signed) {
            sb.append("<Rec_Type:8>tCONTACT\n"); line(sb, "STATION_UID", "1")
            line(sb, "CALL", c.call); line(sb, "BAND", c.band); line(sb, "MODE", c.mode); line(sb, "FREQ", c.freq)
            line(sb, "PROP_MODE", c.propMode); line(sb, "SAT_NAME", c.satName)
            line(sb, "QSO_DATE", c.date); line(sb, "QSO_TIME", c.time)
            sb.append("<$SIGN_FIELD:${sig.length}:6>").append(sig)
            line(sb, "SIGNDATA", data)
            sb.append("<eor>\n")
        }
        return sb.toString()
    }

    /** Signs [qsos] and returns the .tq8 file and the ids that went in it (those without band or mode are left out). */
    fun sign(qsos: List<Qso>, st: Station, key: PrivateKey, certDer: ByteArray, ident: String): Pair<ByteArray, List<String>> {
        val stSig = stationSigndata(st)
        val signed = ArrayList<Triple<Contact, String, String>>(); val ids = ArrayList<String>()
        for (q in qsos) {
            val c = contact(q) ?: continue
            val data = contactSigndata(stSig, c)
            val s = Signature.getInstance("SHA1withRSA")
            s.initSign(key); s.update(data.toByteArray(Charsets.US_ASCII))
            signed.add(Triple(c, Base64.getEncoder().encodeToString(s.sign()), data)); ids.add(q.id)
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(gabbi(certDer, st, signed, ident).toByteArray(Charsets.US_ASCII)) }
        return out.toByteArray() to ids
    }

    /** LoTW's answer to an upload: "<!-- .UPL. accepted -->" and a message. */
    fun uploadResult(html: String): Pair<Boolean, String> {
        val st = Regex("<!-- \\.UPL\\.\\s*([^-]+)\\s*-->").find(html)?.groupValues?.get(1)?.trim()?.lowercase()
        val msg = Regex("<!-- \\.UPLMESSAGE\\.\\s*(.+?)\\s*-->", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim() ?: ""
        return when (st) {
            null -> false to "LoTW: unexpected answer"
            "accepted" -> true to msg.ifEmpty { "Accepted by LoTW" }
            else -> false to "LoTW: ${msg.ifEmpty { st }}"
        }
    }

    /** Confirmations received since [since] (yyyy-MM-dd) for [ownCall]. */
    fun reportUrl(user: String, password: String, since: String, ownCall: String): String {
        fun e(v: String) = URLEncoder.encode(v, "UTF-8")
        return "$REPORT?login=${e(user.trim())}&password=${e(password)}&qso_query=1&qso_qsl=yes&qso_qslsince=${e(since)}" +
            "&qso_qsldetail=yes" + (if (ownCall.isNotBlank()) "&qso_owncall=${e(ownCall.trim().uppercase())}" else "")
    }

    /** A value of the certificate's subject (RFC 2253 text of Java) for one of the ARRL OIDs: callsign .1.1, DXCC .1.4. */
    fun dnField(dn: String, oid: String): String? {
        val i = dn.indexOf("$oid=")
        if (i < 0) return null
        val raw = dn.substring(i + oid.length + 1).let { v -> v.substring(0, v.indexOf(',').let { if (it < 0) v.length else it }) }
        if (!raw.startsWith("#")) return raw.trim()
        val hex = raw.substring(1)
        val b = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        if (b.size < 2) return null
        var off = 2; var len = b[1].toInt() and 0xff
        if (len and 0x80 != 0) { val n = len and 0x7f; len = 0; repeat(n) { len = (len shl 8) or (b[off++].toInt() and 0xff) } }
        return String(b, off, len.coerceAtMost(b.size - off), Charsets.UTF_8)
    }

    const val OID_CALL = "1.3.6.1.4.1.12348.1.1"
    const val OID_DXCC = "1.3.6.1.4.1.12348.1.4"
}
