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
         * Waits for a LoRa channel: the usual 30 s / 60 s / 120 s and two late tries at 5 and 15 min for a station that was out of
         * reach for a while (5 sends in all, the last one 8.5 min after the first; given up at 23.5 min). The schedule VHF clients
         * use (every minute for 5 min, then every 5 min for half an hour) would keep a slow LoRa channel busy.
         */
        val LORA_WAITS: List<Long> = listOf(30_000L, 60_000L, 120_000L, 300_000L, 900_000L)

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
    private val groupWindowMs = 30 * 60 * 1000L
    /** Sends in all before a message is given up (first one included). */
    val tries: Int get() = retryWaitsMs.size

    fun all(): List<ChatMessage> = messages.toList()
    fun conversation(peer: String): List<ChatMessage> = messages.filter { it.peer == peer.uppercase() }

    /** The conversations as text, to keep them between sessions (the last [max] messages). */
    fun export(max: Int = 500): String = Json.encode(mapOf("v" to 1, "no" to nextNo, "messages" to messages.takeLast(max).map { m ->
        mapOf("p" to m.peer, "x" to m.text, "o" to m.outgoing, "n" to m.msgNo, "s" to m.status.name, "t" to m.timeMs, "a" to m.attempts).filterValues { it != null }
    }))

    /**
     * Reads what [export] wrote, in front of what there is. A message that was still waiting for its acknowledgement comes
     * back as not confirmed: its retries ended with the session. The numbering goes on where it was. Returns how many came back.
     */
    fun restore(text: String): Int {
        val root = try { Json.parseObject(text) } catch (_: Exception) { null } ?: return 0
        val back = root.objList("messages").mapNotNull { m ->
            val peer = m.str("p") ?: return@mapNotNull null
            val st = try { MessageStatus.valueOf(m.str("s") ?: "") } catch (_: Exception) { return@mapNotNull null }
            ChatMessage(nextId++, peer, m.str("x") ?: "", m.bool("o") ?: false, m.str("n"), if (st == MessageStatus.PENDING) MessageStatus.FAILED else st,
                m.long("t") ?: 0L, m.int("a") ?: 0)
        }
        messages.addAll(0, back)
        root.int("no")?.takeIf { it in 1..99999 }?.let { nextNo = maxOf(nextNo, it) }
        return back.size
    }

    /** Queues a message and sends it right away. Returns the message or an error text. */
    fun sendMessage(to: String, text: String, wantAck: Boolean = true): Result<ChatMessage> {
        val peer = to.trim().uppercase()
        if (Ax25Address.parse(peer) == null) return Result.failure(IllegalArgumentException("invalid callsign"))
        Aprs.messageTextError(text)?.let { return Result.failure(IllegalArgumentException(it)) }
        val wantAck = wantAck && !AprsConventions.isGroupAddressee(peer)          // a bulletin or a group is never acknowledged
        val no = if (wantAck) (nextNo++).also { if (nextNo > 99999) nextNo = 1 }.toString() else null
        val msg = ChatMessage(nextId++, peer, text, true, no, if (wantAck) MessageStatus.PENDING else MessageStatus.ACKED, now(), 1)
        messages.add(msg)
        send(Aprs.encodeMessage(peer, text, no))
        if (wantAck) pending.add(Pending(msg, now() + retryWaitsMs[0]))
        return Result.success(msg)
    }

    /** Processes a received message. Returns the new message to show, or null (ack, rej, duplicate or not for me). */
    fun onMessage(from: String, m: AprsPacket.Message): ChatMessage? {
        if (m.isGroup && !m.isAck && !m.isRej) {
            // bulletins, announcements and group calls are for everybody: one conversation per addressee, "FROM> text", never an
            // ack; they are repeated on the air for hours, so the same line counts once every 30 minutes
            val sender = from.uppercase()
            if (sender.equals(myCall, ignoreCase = true)) return null
            val group = m.addressee.trim().uppercase()
            val key = "G/$group/$sender/${m.text}"
            val t = now()
            seen.entries.removeIf { t - it.value > (if (it.key.startsWith("G/")) groupWindowMs else seenWindowMs) }
            if (seen.containsKey(key)) return null
            seen[key] = t
            val msg = ChatMessage(nextId++, group, "$sender> ${m.text}", false, null, MessageStatus.RECEIVED, t)
            messages.add(msg)
            return msg
        }
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
            seen.entries.removeIf { t - it.value > (if (it.key.startsWith("G/")) groupWindowMs else seenWindowMs) }
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

    /** Stops the retries of the messages still waiting for an ack (all, or those to [peer]); they end as FAILED. */
    fun cancelPending(peer: String? = null): List<ChatMessage> {
        val changed = ArrayList<ChatMessage>()
        for (p in pending.toList()) {
            if (peer != null && p.msg.peer != peer.uppercase()) continue
            val failed = p.msg.copy(status = MessageStatus.FAILED)
            replace(p.msg, failed); pending.remove(p); changed.add(failed)
        }
        return changed
    }

    private fun replace(old: ChatMessage, new: ChatMessage) {
        val i = messages.indexOfFirst { it.id == old.id }
        if (i >= 0) messages[i] = new
    }
}
