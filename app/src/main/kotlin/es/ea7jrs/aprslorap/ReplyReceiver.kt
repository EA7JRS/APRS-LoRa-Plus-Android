/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Receives the reply typed or dictated from a notification (phone or Android Auto) and the mark-as-read action. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra(Notifier.EXTRA_PEER) ?: return
        when (intent.action) {
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val c = AppState.controller
                if (c == null) {
                    // The link was stopped: do not pretend the answer went out
                    AppState.error.value = "Reply to $peer not sent: the link with the tracker is stopped"
                    AppState.notifier?.replied(peer, "(not sent) $text", null)
                    return
                }
                c.sendMessage(peer, text)
                AppState.notifier?.replied(peer, text, null)
            }
            Notifier.ACTION_READ -> {
                AppState.notifier?.clear(peer)
                if (peer.startsWith(AlertCenter.CAR_PREFIX)) AlertCenter.acknowledgedInCar(peer.removePrefix(AlertCenter.CAR_PREFIX))
            }
        }
    }
}
