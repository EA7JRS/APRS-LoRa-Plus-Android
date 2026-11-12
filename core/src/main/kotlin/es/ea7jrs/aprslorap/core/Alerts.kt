/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/** Emergency alerts shown as a window over any screen: APRS beacon or message, AIS distress or collision, own EAI, man down. */
enum class AlertKind(val title: String, val distress: Boolean) {
    APRS_BEACON("EMERGENCY BEACON", true),
    APRS_MESSAGE("EMERGENCY MESSAGE", true),
    MOB("MAN OVERBOARD", true),
    AIS_DISTRESS("AIS DISTRESS", true),
    AIS_COLLISION("COLLISION WARNING", false),
    EAI("EAI ACTIVE", true),
    MAN_DOWN("MAN DOWN", false),
    /** Weather, tsunami, earthquake, space weather or NOAA Weather Radio: the [EmergencyAlert.official] alert. */
    OFFICIAL("OFFICIAL ALERT", false),
}

/**
 * One alert. [key] joins the same alarm heard by the tracker and by the app ("aprs:EA7ZZZ-9", "ais:970123456", "eai", "mandown").
 * [fromMsg] = it came in an APRS message; [text] = the message or the tracker's detail line.
 */
data class EmergencyAlert(
    val key: String, val kind: AlertKind, val who: String, val text: String, val fromMsg: Boolean, val mmsi: Long?, val atMs: Long,
    val fromTracker: Boolean,
    val official: OfficialAlert? = null,
)

object Alerts {
    /** Same words as the tracker (pages_core.cpp, alarmFromText): whole words, any case. */
    fun textKind(text: String?, fromMsg: Boolean): AlertKind? {
        if (text.isNullOrBlank()) return null
        if (word(text, "MOB") || word(text, "MANOVERBOARD") || word(text, "MAN OVERBOARD")) return AlertKind.MOB
        if (word(text, "SOS") || word(text, "EMERGENCY") || word(text, "MAYDAY")) return if (fromMsg) AlertKind.APRS_MESSAGE else AlertKind.APRS_BEACON
        return null
    }

    private fun word(text: String, w: String): Boolean {
        var i = text.indexOf(w, ignoreCase = true)
        while (i >= 0) {
            val before = i == 0 || !text[i - 1].isLetterOrDigit()
            val after = i + w.length >= text.length || !text[i + w.length].isLetterOrDigit()
            if (before && after) return true
            i = text.indexOf(w, i + 1, ignoreCase = true)
        }
        return false
    }

    /** Alert from the tracker's "led" event (an alarm sounding or its screen open), or null. */
    fun fromLed(l: ControlEvent.Led, nowMs: Long): EmergencyAlert? {
        if (l.seq <= 0 || !(l.emg || l.open) || l.emgCall.isBlank()) return null
        val kind = when (l.kind) {
            "ais_sos" -> AlertKind.AIS_DISTRESS
            "ais_cpa" -> AlertKind.AIS_COLLISION
            "mob" -> AlertKind.MOB
            else -> if (l.fromMsg) AlertKind.APRS_MESSAGE else AlertKind.APRS_BEACON
        }
        val ais = kind == AlertKind.AIS_DISTRESS || kind == AlertKind.AIS_COLLISION
        val mmsi = if (ais) l.emgCall.trim().toLongOrNull() else null
        return EmergencyAlert(if (ais) "ais:" + (mmsi ?: l.emgCall) else "aprs:" + l.emgCall.uppercase(), kind, l.emgCall, l.emgText, l.fromMsg, mmsi, nowMs, true)
    }

    /** AIS distress (AIS-SART, MOB, EPIRB by MMSI, status 14 or a safety text) or a collision risk, as the tracker decides. */
    fun fromAis(v: AisVessel, nowMs: Long): EmergencyAlert? {
        val test = v.text.contains("TEST", ignoreCase = true)
        val distress = !test && (Ais.distressName(v.mmsi).isNotEmpty() || v.status == 14 ||
            listOf("SART", "MOB", "EPIRB").any { v.text.contains(it, ignoreCase = true) })
        val kind = when { distress -> AlertKind.AIS_DISTRESS; v.danger -> AlertKind.AIS_COLLISION; else -> return null }
        return EmergencyAlert("ais:${v.mmsi}", kind, v.title, v.text, false, v.mmsi, nowMs, false)
    }

    /** An APRS message or beacon comment heard by the app itself (also with firmware that does not send alerts). */
    fun fromAprs(call: String, text: String, fromMsg: Boolean, nowMs: Long): EmergencyAlert? {
        val kind = textKind(text, fromMsg) ?: return null
        return EmergencyAlert("aprs:" + call.uppercase(), kind, call, text, fromMsg, null, nowMs, false)
    }

    /** Own emergency: EAI (remote, voice of the T-TWR, or the SOS beacon) and the man-down warning; null when nothing is on. */
    fun fromEai(e: ControlEvent.Eai, myCall: String, nowMs: Long): List<EmergencyAlert> {
        val out = ArrayList<EmergencyAlert>()
        if (e.sos == 2 || e.fm) out.add(EmergencyAlert("eai", AlertKind.EAI, myCall, "", false, null, nowMs, true))
        if (e.manDown) out.add(EmergencyAlert("mandown", AlertKind.MAN_DOWN, myCall, "", false, null, nowMs, true))
        return out
    }

    /**
     * The open alerts. A new key opens (and sounds); the same key while open only updates it; a key closed by the user does not
     * open again for [QUIET_MS] (the tracker does the same: 10 minutes); the own EAI / man down, closed, stays closed until it ends
     * and opens again the next time it starts.
     */
    class Queue {
        private val open = LinkedHashMap<String, EmergencyAlert>()
        private val closedAt = HashMap<String, Long>()

        val items: List<EmergencyAlert> get() = open.values.toList()

        /** True when the alert is new (sound the alarm). */
        fun offer(a: EmergencyAlert, nowMs: Long): Boolean {
            val old = open[a.key]
            if (old != null) {
                // a distress overrides a collision warning of the same vessel; the text is kept when the update has none
                val kind = if (old.kind.distress && !a.kind.distress) old.kind else a.kind
                open[a.key] = a.copy(kind = kind, text = a.text.ifBlank { old.text }, atMs = old.atMs, fromTracker = old.fromTracker || a.fromTracker)
                return !old.kind.distress && a.kind.distress
            }
            val own = a.kind == AlertKind.EAI || a.kind == AlertKind.MAN_DOWN
            val c = closedAt[a.key]
            if (c != null && (own || nowMs - c < QUIET_MS)) return false
            open[a.key] = a
            return true
        }

        fun close(key: String, nowMs: Long) {
            if (open.remove(key) != null) closedAt[key] = nowMs
        }

        /** Own alerts that ended on the tracker go away by themselves. */
        fun end(key: String) { open.remove(key); closedAt.remove(key) }

        fun has(key: String) = open.containsKey(key)
    }

    const val QUIET_MS = 10 * 60_000L

    fun formatLeft(s: Long): String = if (s < 0) "0:00" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
