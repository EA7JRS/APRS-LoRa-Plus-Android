/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

enum class MessageStatus { PENDING, ACKED, FAILED, REJECTED, RECEIVED }

data class ChatMessage(
    val id: Long,
    val peer: String,
    val text: String,
    val outgoing: Boolean,
    val msgNo: String?,
    val status: MessageStatus,
    val timeMs: Long,
    val attempts: Int = 0,
)

/**
 * Messaging engine: numbering, acknowledgements with retries and duplicate removal. Pure logic: time comes from [now]
 * and frames leave through [send] (addressee, information field), so it is tested without a radio.
 * Call [tick] about once per second.
 */
class MessageEngine(
    private val myCall: String,
    private val send: (info: String) -> Unit,
    private val now: () -> Long,
    private val retryWaitsMs: List<Long> = listOf(30_000L, 60_000L, 120_000L),
    /** False when the tracker already acknowledges our messages (same callsign and SSID): avoids a double ACK. */
    private val sendAcks: Boolean = true,
) {
    companion object {
        /**
         * True when the app and the tracker use the same callsign and SSID ("EA7JRS" and "EA7JRS-0" are the same).
         * The firmware acknowledges by itself the messages addressed to its own callsign, so the app must not.
         * An empty or invalid tracker callsign means "not known": the app sends the ACKs.
         */
        fun trackerSendsAcks(appCall: String, trackerCall: String): Boolean {
            val a = Ax25Address.parse(appCall) ?: return false
            val t = Ax25Address.parse(trackerCall) ?: return false
            return a.call == t.call && a.ssid == t.ssid
        }
    }

    private class Pending(var msg: ChatMessage, var nextAtMs: Long)

    private val messages = ArrayList<ChatMessage>()
    private val pending = ArrayList<Pending>()
    private val seen = HashMap<String, Long>()       // from + msgNo -> instant (duplicate removal)
    private var nextId = 1L
    private var nextNo = 1
    private val seenWindowMs = 10 * 60 * 1000L

    fun all(): List<ChatMessage> = messages.toList()
    fun conversation(peer: String): List<ChatMessage> = messages.filter { it.peer == peer.uppercase() }

    /** Queues a message and sends it right away. Returns the message or an error text. */
    fun sendMessage(to: String, text: String, wantAck: Boolean = true): Result<ChatMessage> {
        val peer = to.trim().uppercase()
        if (Ax25Address.parse(peer) == null) return Result.failure(IllegalArgumentException("invalid callsign"))
        Aprs.messageTextError(text)?.let { return Result.failure(IllegalArgumentException(it)) }
        val no = if (wantAck) (nextNo++).also { if (nextNo > 99999) nextNo = 1 }.toString() else null
        val msg = ChatMessage(nextId++, peer, text, true, no, if (wantAck) MessageStatus.PENDING else MessageStatus.ACKED, now(), 1)
        messages.add(msg)
        send(Aprs.encodeMessage(peer, text, no))
        if (wantAck) pending.add(Pending(msg, now() + retryWaitsMs[0]))
        return Result.success(msg)
    }

    /** Processes a received message. Returns the new message to show, or null (ack, rej, duplicate or not for me). */
    fun onMessage(from: String, m: AprsPacket.Message): ChatMessage? {
        if (!m.addressee.equals(myCall, ignoreCase = true)) return null
        val peer = from.uppercase()
        if (m.isAck || m.isRej) {
            val no = m.msgNo ?: return null
            val p = pending.firstOrNull { it.msg.peer == peer && it.msg.msgNo == no } ?: return null
            replace(p.msg, p.msg.copy(status = if (m.isAck) MessageStatus.ACKED else MessageStatus.REJECTED))
            pending.remove(p)
            return null
        }
        val no = m.msgNo
        if (no != null && sendAcks) send(Aprs.encodeAck(peer, no)) // always confirm, even a repeat: our ack may have been lost
        if (no != null) {
            val key = "$peer/$no"
            val t = now()
            seen.entries.removeIf { t - it.value > seenWindowMs }
            if (seen.containsKey(key)) return null
            seen[key] = t
        }
        val msg = ChatMessage(nextId++, peer, m.text, false, no, MessageStatus.RECEIVED, now())
        messages.add(msg)
        return msg
    }

    /** Resends what is due and gives up on what is spent. Returns the messages whose status changed. */
    fun tick(): List<ChatMessage> {
        val changed = ArrayList<ChatMessage>()
        val t = now()
        for (p in pending.toList()) {
            if (t < p.nextAtMs) continue
            val attempts = p.msg.attempts
            if (attempts >= retryWaitsMs.size) {
                val failed = p.msg.copy(status = MessageStatus.FAILED)
                replace(p.msg, failed); pending.remove(p); changed.add(failed)
            } else {
                send(Aprs.encodeMessage(p.msg.peer, p.msg.text, p.msg.msgNo))
                val updated = p.msg.copy(attempts = attempts + 1)
                replace(p.msg, updated)
                p.msg = updated
                p.nextAtMs = t + retryWaitsMs[attempts.coerceAtMost(retryWaitsMs.size - 1)]
                changed.add(updated)
            }
        }
        return changed
    }

    private fun replace(old: ChatMessage, new: ChatMessage) {
        val i = messages.indexOfFirst { it.id == old.id }
        if (i >= 0) messages[i] = new
    }
}
