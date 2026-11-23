/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import es.ea7jrs.aprslorap.core.CarText
import es.ea7jrs.aprslorap.core.ChatMessage
import es.ea7jrs.aprslorap.core.Page
import es.ea7jrs.aprslorap.core.Nav
import es.ea7jrs.aprslorap.core.Station

/**
 * Notifications. Incoming messages use MessagingStyle with reply and mark-as-read actions, which is what
 * Android Auto needs to read them aloud and let you answer by voice.
 */
class Notifier(private val ctx: Context) {
    companion object {
        const val CH_SERVICE = "service"
        const val CH_MESSAGES = "messages"
        const val CH_DAPNET = "dapnet"
        const val CH_FAVOURITES = "favourites"
        const val FAV_PREFIX = "FAV:"
        /** Lines of a conversation kept in its notification (the car reads the newest, shows the rest). */
        const val LINES = 8
        const val DAPNET_PEER = "DAPNET"
        const val ID_SERVICE = 1
        const val KEY_REPLY = "reply_text"
        const val EXTRA_PEER = "peer"
        const val ACTION_REPLY = "es.ea7jrs.aprslorap.REPLY"
        const val ACTION_READ = "es.ea7jrs.aprslorap.READ"
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
    }

    // Lines of each open conversation notification, oldest first. Real lines carry the engine's id (their status follows the
    // engine in onMessages); a reply that could not be sent is a local line with a negative id.
    private val history = HashMap<String, MutableList<ChatMessage>>()
    private val shownSig = HashMap<String, String>()
    private var localId = -1L
    private val icons = HashMap<String, androidx.core.graphics.drawable.IconCompat>()

    /**
     * The station's APRS symbol (as aprs.fi draws it) on a light disc, for the sender's avatar on the phone and in Android Auto.
     * [table] + [code] given, or looked up in the station list and the tracker's heard list.
     */
    /** DAPNET's mark (three linked rings, the image Manuel gave), drawn on a light disc: avatar of the DAPNET pages. */
    val dapnetIcon: androidx.core.graphics.drawable.IconCompat? by lazy {
        try {
            val px = 128
            val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = 0xFFE8EEF3.toInt(); c.drawCircle(px / 2f, px / 2f, px / 2f, p)
            val k = px * 0.62f / 280f                                // the 280 x 280 drawing, centred
            val o = px * 0.19f
            fun x(v: Float) = o + v * k
            p.style = android.graphics.Paint.Style.STROKE
            p.color = 0xFF5BBEF5.toInt(); p.strokeWidth = 9f * k
            c.drawLine(x(118f), x(75f), x(178f), x(106f), p)
            c.drawLine(x(126f), x(168f), x(176f), x(146f), p)
            p.color = 0xFF1E78B4.toInt(); p.strokeWidth = 15f * k
            c.drawCircle(x(90f), x(60f), 31f * k, p)
            c.drawCircle(x(220f), x(126f), 48f * k, p)
            c.drawCircle(x(72f), x(196f), 60f * k, p)
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(out)
        } catch (_: Exception) { null }
    }

    fun symbolIcon(call: String, table: Char? = null, code: Char? = null): androidx.core.graphics.drawable.IconCompat? {
        val s = AppState.stations.value.firstOrNull { it.call.equals(call, true) }
        val h = AppState.heard.value.firstOrNull { it.call.equals(call, true) }
        val sym = es.ea7jrs.aprslorap.core.AprsSymbols.of(table ?: s?.symbolTable ?: h?.symbol?.getOrNull(0), code ?: s?.symbolCode ?: h?.symbol?.getOrNull(1)) ?: return null
        val key = "" + (sym.base) + (sym.overlay)
        synchronized(icons) { icons[key]?.let { return it } }
        return try {
            val px = 128
            val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawCircle(px / 2f, px / 2f, px / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EEF3.toInt() })
            val sprite = AprsSprites.bitmap(ctx, sym, px * 5 / 8)
            c.drawBitmap(sprite, px * 3 / 16f, px * 3 / 16f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(out).also { synchronized(icons) { icons[key] = it } }
        } catch (_: Exception) { null }
    }

    init {
        val nm = ctx.getSystemService(NotificationManager::class.java)       // nullable in the SDK annotations
        nm?.createNotificationChannel(NotificationChannel(CH_SERVICE, "Tracker link", NotificationManager.IMPORTANCE_LOW))
        nm?.createNotificationChannel(NotificationChannel(CH_MESSAGES, "APRS messages", NotificationManager.IMPORTANCE_HIGH))
        nm?.createNotificationChannel(NotificationChannel(CH_DAPNET, "DAPNET pages", NotificationManager.IMPORTANCE_HIGH))
        nm?.createNotificationChannel(NotificationChannel(CH_FAVOURITES, "Favourite stations heard", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun foreground(text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("APRS LoRa+")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    /** Shows the link state in the service notification. */
    @SuppressLint("MissingPermission")
    fun updateForeground(text: String) {
        val nmc = NotificationManagerCompat.from(ctx)
        if (nmc.areNotificationsEnabled()) nmc.notify(ID_SERVICE, foreground(text))
    }

    // Written from the controller thread (incoming, onMessages) and the main thread (reply, mark as read): every access is synchronized.
    fun incoming(msg: ChatMessage, station: Station?) {
        synchronized(history) {
            val l = history.getOrPut(msg.peer) { ArrayList() }
            if (l.none { it.id == msg.id && it.id > 0 }) l.add(msg)
        }
        show(msg.peer, station, alert = true)
    }

    /**
     * After answering from the notification. A sent answer is not added here: the engine's own copy arrives in [onMessages] with
     * its state (waiting, retry n/3, confirmed). One that could not be sent is shown at once, so the car does not keep waiting.
     */
    fun replied(peer: String, text: String, outgoing: ChatMessage?) {
        if (outgoing == null && !text.startsWith("(not sent")) return
        synchronized(history) {
            history.getOrPut(peer) { ArrayList() }.add(
                outgoing ?: ChatMessage(localId--, peer, text, true, null, es.ea7jrs.aprslorap.core.MessageStatus.FAILED, System.currentTimeMillis()),
            )
        }
        show(peer, AppState.stations.value.firstOrNull { it.call == peer }, alert = false)
    }

    /**
     * Every message of the engine, after each change (Controller.publish): the open conversations take the state of what we sent
     * (confirmed, retry 2/3, not confirmed, rejected) and the answers sent from the car or the phone. A conversation whose lines
     * did not change is not touched; a message that ends up not confirmed or rejected alerts again.
     */
    fun onMessages(all: List<ChatMessage>) {
        val changed = ArrayList<Pair<String, Boolean>>()
        synchronized(history) {
            for ((peer, lines) in history) {
                val since = lines.filter { it.id > 0 }.minOfOrNull { it.timeMs } ?: lines.minOfOrNull { it.timeMs } ?: continue
                val conv = all.filter { it.peer == peer && it.timeMs >= since }
                val local = lines.filter { it.id < 0 }
                val next = (conv + local).sortedBy { it.timeMs }.takeLast(LINES)
                if (CarText.signature(next) == CarText.signature(lines)) continue
                val failedNow = next.any { n -> n.outgoing && n.id > 0 &&
                    (n.status == es.ea7jrs.aprslorap.core.MessageStatus.FAILED || n.status == es.ea7jrs.aprslorap.core.MessageStatus.REJECTED) &&
                    lines.none { it.id == n.id && it.status == n.status } }
                lines.clear(); lines.addAll(next)
                changed.add(peer to failedNow)
            }
        }
        for ((peer, alert) in changed) show(peer, AppState.stations.value.firstOrNull { it.call == peer }, alert)
    }

    /** "12 km north-east" (spoken: [words]) or "12 km NE" from our position (the tracker's GNSS, or the phone), in the user's units. */
    fun where(lat: Double?, lon: Double?, words: Boolean = true): String? {
        val d = Own.to(lat, lon) ?: return null
        val (k, unit) = es.ea7jrs.aprslorap.core.WxCalc.distFactor(Fmt.units)
        return CarText.where(d.first * k, unit, d.second, words)
    }

    private val favTold = HashMap<String, Long>()

    /**
     * A favourite station (★ in its detail) heard: a conversation-style notification, so the car reads "EA7URM-13: heard, 5.1 km
     * north-east, via ED7ZAB-10". Once every 30 min per station. Reply writes to it; Go to if it sent a position.
     */
    @SuppressLint("MissingPermission")
    fun favouriteHeard(call: String, lat: Double?, lon: Double?, via: String?) {
        val now = System.currentTimeMillis()
        synchronized(favTold) {
            if (!CarText.tellFavourite(now, favTold[call] ?: 0L)) return
            favTold[call] = now
        }
        val nmc = NotificationManagerCompat.from(ctx)
        if (!nmc.areNotificationsEnabled()) return
        val peer = FAV_PREFIX + call
        val text = listOfNotNull("Heard", where(lat, lon), via?.takeIf { it.isNotBlank() }?.let { "via $it" }).joinToString(", ")
        val me = Person.Builder().setName("Me").build()
        val from = Person.Builder().setName(call).setKey(peer).also { b -> symbolIcon(call)?.let { b.setIcon(it) } }.build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("Favourite heard").setGroupConversation(false)
            .addMessage(text, now, from)
        val b = NotificationCompat.Builder(ctx, CH_FAVOURITES)
            .setSmallIcon(android.R.drawable.star_on)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, peer.hashCode() + 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(replyAction(call, peer.hashCode()))
            .addAction(readAction(peer))
        goToAction(peer, lat, lon)?.let { b.addAction(it) }
        try { nmc.notify(peer.hashCode(), b.build()) } catch (_: Exception) {}
    }

    private fun replyAction(to: String, code: Int): NotificationCompat.Action = NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_send, "Reply",
        PendingIntent.getBroadcast(ctx, code,
            Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PEER, to),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
    ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        .setShowsUserInterface(false).setAllowGeneratedReplies(false).build()

    private fun readAction(peer: String): NotificationCompat.Action = NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_view, "Mark as read",
        PendingIntent.getBroadcast(ctx, peer.hashCode() + 1,
            Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_PEER, peer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
    ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()

    private fun goToAction(peer: String, lat: Double?, lon: Double?): NotificationCompat.Action? {
        if (lat == null || lon == null) return null
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse(Nav.googleNavigationUri(lat, lon))).setPackage(GOOGLE_MAPS)
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_directions, "Go to",
            PendingIntent.getActivity(ctx, peer.hashCode() + 2, nav, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    }

    private val pageHistory = ArrayList<Page>()

    /**
     * A DAPNET page received by the tracker. POCSAG pages carry no sender, so there is no reply action, only mark as read
     * (Android Auto may not show a conversation without a reply action: to be checked in the car).
     */
    @SuppressLint("MissingPermission")
    fun page(p: Page) {
        val pages = synchronized(pageHistory) {
            pageHistory.add(p)
            while (pageHistory.size > 10) pageHistory.removeAt(0)
            pageHistory.toList()
        }
        val nmc = NotificationManagerCompat.from(ctx)
        if (!nmc.areNotificationsEnabled()) return
        val me = Person.Builder().setName("Me").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle("DAPNET")
        val now = System.currentTimeMillis()
        for (x in pages) {
            val from = Person.Builder().setName(if (x.own) "RIC ${x.ric}" else "RIC ${x.ric} (other)").setKey("ric${x.ric}")
                .also { b -> dapnetIcon?.let { b.setIcon(it) } }.build()
            style.addMessage(x.text, now - x.ageS * 1000, from)
        }
        val read = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark as read",
            PendingIntent.getBroadcast(
                ctx, DAPNET_PEER.hashCode() + 1,
                Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_PEER, DAPNET_PEER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val b = NotificationCompat.Builder(ctx, CH_DAPNET)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .addAction(read)
            .setContentIntent(PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        nmc.notify(DAPNET_PEER.hashCode(), b.build())
    }

    /**
     * An emergency alert also as a conversation (MessagingStyle), so that Android Auto shows it and reads it aloud: the alarm-type
     * notification of AlertCenter does not reach the car. Reply (APRS alerts) answers the station by APRS; "Mark as read" silences
     * the alarm and, for the man-down warning, means "I'm OK".
     */
    @SuppressLint("MissingPermission")
    fun alert(peer: String, title: String, who: String, text: String, replyTo: String?, lat: Double?, lon: Double?,
              icon: androidx.core.graphics.drawable.IconCompat? = null) {
        val nmc = NotificationManagerCompat.from(ctx)
        if (!nmc.areNotificationsEnabled()) return
        val me = Person.Builder().setName("Me").build()
        val from = Person.Builder().setName(who.ifBlank { title }).setKey(peer).also { b -> icon?.let { b.setIcon(it) } }.build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(title).setGroupConversation(false)
            .addMessage(text, System.currentTimeMillis(), from)
        val read = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark as read",
            PendingIntent.getBroadcast(ctx, peer.hashCode() + 1,
                Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_PEER, peer),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()
        val b = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, peer.hashCode() + 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (replyTo != null) {
            b.addAction(NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "Reply",
                PendingIntent.getBroadcast(ctx, peer.hashCode(),
                    Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PEER, replyTo),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
            ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false).setAllowGeneratedReplies(false).build())
        }
        b.addAction(read)
        if (lat != null && lon != null) {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse(Nav.googleNavigationUri(lat, lon))).setPackage(GOOGLE_MAPS)
            b.addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_directions, "Go to",
                PendingIntent.getActivity(ctx, peer.hashCode() + 2, nav, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        try { nmc.notify(peer.hashCode(), b.build()) } catch (_: Exception) {}
    }

    fun clear(peer: String) {
        if (peer == DAPNET_PEER) synchronized(pageHistory) { pageHistory.clear() }
        synchronized(history) { history.remove(peer); shownSig.remove(peer) }
        NotificationManagerCompat.from(ctx).cancel(peer.hashCode())
    }

    @SuppressLint("MissingPermission")
    private fun show(peer: String, station: Station?, alert: Boolean) {
        val nmc = NotificationManagerCompat.from(ctx)
        if (!nmc.areNotificationsEnabled()) return
        val msgs = synchronized(history) { history[peer].orEmpty().takeLast(LINES).toList() }
        if (msgs.isEmpty()) return
        val sig = CarText.signature(msgs)
        synchronized(history) { if (!alert && shownSig[peer] == sig) return; shownSig[peer] = sig }
        val h = AppState.heard.value.firstOrNull { it.call.equals(peer, true) }
        val lat = station?.lat ?: h?.lat
        val lon = station?.lon ?: h?.lon
        // who and where: the car reads the sender's name before the text ("EA7XYZ-9, 12 km north-east")
        val me = Person.Builder().setName("Me").build()
        val other = Person.Builder().setName(listOfNotNull(peer, where(lat, lon)).joinToString(", ")).setKey(peer)
            .also { b -> symbolIcon(peer)?.let { b.setIcon(it) } }.build()
        val style = NotificationCompat.MessagingStyle(me)
        val all = AppState.messages.value.filter { it.peer == peer }
        for (m in msgs) {
            val text = if (m.outgoing) listOfNotNull(m.text, CarText.status(m, es.ea7jrs.aprslorap.core.MessageEngine.LORA_WAITS.size)).joinToString(" · ")
                else CarText.spoken(m.text, CarText.answers((all + msgs).distinctBy { it.id }.sortedBy { it.timeMs }, m))
            style.addMessage(text, m.timeMs, if (m.outgoing) me else other)
        }
        val b = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, peer.hashCode() + 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(replyAction(peer, peer.hashCode()))
            .addAction(readAction(peer))
        goToAction(peer, lat, lon)?.let { b.addAction(it) }
        try { nmc.notify(peer.hashCode(), b.build()) } catch (_: Exception) {}
    }
}
