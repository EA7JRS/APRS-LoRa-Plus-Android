/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * Texts for Android Auto (panel 17 of the design canvas, approved 2026-11-15): what the car shows and reads aloud. Directions in
 * words ("north-east", not "NE") because the car's voice reads letters one by one; message states in plain words.
 */
object CarText {
    private val WORDS = arrayOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    private val SHORT = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** 8-point direction of a bearing in degrees. */
    fun direction(deg: Double, words: Boolean = true): String {
        val i = Math.floorMod(Math.round(deg / 45.0).toInt(), 8)
        return if (words) WORDS[i] else SHORT[i]
    }

    /** "12 km north-east" (spoken) or "12 km NE" (shown); [dist] already in the user's units with its [unit] label. */
    fun where(dist: Double, unit: String, deg: Double, words: Boolean = true): String {
        val d = if (dist < 10) String.format(Locale.US, "%.1f", dist) else String.format(Locale.US, "%,d", Math.round(dist))
        return "$d $unit ${direction(deg, words)}"
    }

    /** State of a message we sent, as the conversation shows it; null for received messages. [tries] = retries in total. */
    fun status(m: ChatMessage, tries: Int = 3): String? {
        if (!m.outgoing) return null
        return when (m.status) {
            MessageStatus.ACKED -> if (m.msgNo == null) "sent" else "confirmed"
            MessageStatus.PENDING -> if (m.attempts <= 1) "sent, waiting for the ack" else "retry ${m.attempts.coerceAtMost(tries)}/$tries"
            MessageStatus.FAILED -> "not confirmed"
            MessageStatus.REJECTED -> "rejected"
            MessageStatus.RECEIVED -> null
        }
    }

    /** Short quote of a text: the first [max] characters, cut at a space, with "…". */
    fun quote(text: String, max: Int = 32): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return t.substring(0, cut).trimEnd() + "…"
    }

    /**
     * What a received message answers: our last message to that station before it, when nothing else came from them in between
     * and it went out less than [windowMs] earlier. [conversation] in time order, with [incoming] in it.
     */
    fun answers(conversation: List<ChatMessage>, incoming: ChatMessage, windowMs: Long = 30 * 60_000L): ChatMessage? {
        val i = conversation.indexOfFirst { it.id == incoming.id && !it.outgoing }
        if (i <= 0) return null
        val prev = conversation[i - 1]
        if (!prev.outgoing || incoming.timeMs - prev.timeMs > windowMs) return null
        return prev
    }

    /** Line read aloud for a received message: "Re “Where are you?”: On my way". */
    fun spoken(text: String, answered: ChatMessage?): String =
        if (answered == null) text else "Re “${quote(answered.text)}”: $text"

    /** Changes the notification of a conversation must show again: the id, status and attempts of each line. */
    fun signature(lines: List<ChatMessage>): String = lines.joinToString(";") { "${it.id}:${it.status}:${it.attempts}:${it.text.hashCode()}" }

    /** A favourite station heard: how long it waits before telling again about the same one. */
    const val FAVOURITE_QUIET_MS = 30 * 60_000L

    /**
     * Whether to tell about a favourite station heard now ([lastToldMs] = when we last did, 0 = never). A station already heard
     * less than [FAVOURITE_QUIET_MS] ago is not news.
     */
    fun tellFavourite(nowMs: Long, lastToldMs: Long): Boolean = lastToldMs == 0L || nowMs - lastToldMs >= FAVOURITE_QUIET_MS

    /** Pressure trend arrow for a 3-hour change in hPa, with the limits of [WxCalc.trendName] (steady under 1 hPa). */
    fun trendArrow(d3h: Double?): String = when {
        d3h == null -> ""
        d3h >= 1.0 -> "↑"
        d3h <= -1.0 -> "↓"
        else -> "→"
    }
}
