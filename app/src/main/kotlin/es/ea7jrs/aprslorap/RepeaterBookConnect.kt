/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.database.Cursor
import android.net.Uri
import es.ea7jrs.aprslorap.core.RepeaterBook

/**
 * RepeaterBook Connect Data API: repeaters near a position read from the RepeaterBook app installed on this phone (its content
 * provider). No token: it needs the RepeaterBook app and the user's own RepeaterBook Connect subscription. Results are only shown.
 */
object RepeaterBookConnect {
    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(RepeaterBook.CONNECT_PACKAGE, 0); true
    } catch (e: Exception) { false }

    /** Repeaters around [lat] / [lon] (RepeaterBook's own filters and order), or around the last place searched in RepeaterBook when null. */
    fun search(ctx: Context, lat: Double?, lon: Double?) {
        if (AppState.rbBusy.value) return
        if (!installed(ctx)) { AppState.rbMessage.value = "Install the RepeaterBook app and its RepeaterBook Connect subscription"; return }
        AppState.rbBusy.value = true
        AppState.rbMessage.value = "Asking the RepeaterBook app..."
        val app = ctx.applicationContext
        Thread({
            var cur: Cursor? = null
            try {
                val args = if (lat != null && lon != null) arrayOf(lat.toString(), lon.toString()) else null
                cur = app.contentResolver.query(Uri.parse(RepeaterBook.CONNECT_URI), null, null, args, null)
                if (cur == null) {
                    AppState.rbMessage.value = "The RepeaterBook app gave no data: check that RepeaterBook Connect is active in it"
                    return@Thread
                }
                val out = ArrayList<RepeaterBook.Repeater>()
                val names = cur.columnNames
                while (cur.moveToNext() && out.size < 100) {
                    val row = HashMap<String, Any?>()
                    for (i in names.indices) row[names[i]] = when (cur.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cur.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> cur.getString(i)
                        else -> null
                    }
                    out.add(RepeaterBook.fromConnect(row))
                }
                AppState.rbResults.value = out
                AppState.rbMessage.value = if (out.isEmpty()) "No repeaters from the RepeaterBook app" +
                    (if (lat == null) " (no position: it uses the last place searched in RepeaterBook)" else "")
                    else "${out.size} repeater(s) from the RepeaterBook app. ${RepeaterBook.ATTRIBUTION}"
            } catch (e: SecurityException) {
                AppState.rbMessage.value = "The RepeaterBook app refused: RepeaterBook Connect must be active in it"
            } catch (e: Exception) {
                AppState.rbMessage.value = "RepeaterBook app: ${e.message}"
            } finally {
                try { cur?.close() } catch (_: Exception) {}
                AppState.rbBusy.value = false
            }
        }, "rbconnect").start()
    }
}
