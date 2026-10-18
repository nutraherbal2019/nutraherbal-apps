package com.nutraherbal.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * v1.5 — caller-ID pop-up. When the phone rings with a number that belongs to one of the agent's CRM clients,
 * a heads-up notification shows the client's name, stage, pending amount, last call (with tags) and next follow-up.
 * Needs the "Phone" permission (READ_PHONE_STATE) + call-log permission; it is a normal notification, so no "draw over other apps" permission is used.
 */
class CallerIdReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return
        val store = Store(ctx)
        if (!store.isPaired() || !store.callerIdOn) return
        @Suppress("DEPRECATION")
        val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return
        val digits = num.filter { it.isDigit() }
        if (digits.length < 7) return
        val now = System.currentTimeMillis()
        if (store.lastCallerId == digits && now - store.lastCallerIdAt < 20000L) return   // the system sends this broadcast twice
        store.lastCallerId = digits
        store.lastCallerIdAt = now
        val pending = goAsync()
        Thread {
            try {
                val r = Api.post(store.base, store.token, JSONObject().put("op", "callerId").put("mobile", num))
                if (r.optBoolean("found")) show(ctx, r, digits)
            } catch (e: Exception) {
                /* no network / not a client: no pop-up */
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun ago(at: Long): String {
        val days = ((System.currentTimeMillis() - at) / 86400000L).toInt()
        return if (days <= 0) "today" else if (days == 1) "yesterday" else days.toString() + " days ago"
    }

    private fun show(ctx: Context, r: JSONObject, digits: String) {
        val name = r.optString("name").ifEmpty { "+" + digits }
        val stage = r.optString("stage")
        val title = if (stage.isNotEmpty()) name + " · " + stage else name
        val pend = r.optDouble("pending", 0.0)
        val short = ArrayList<String>()
        if (pend > 0) short.add("Baaki " + T.rupees(pend))
        val last = r.optJSONObject("last")
        if (last != null) short.add("Last: " + last.optString("outcome") + " " + ago(last.optLong("at")))
        if (short.isEmpty()) short.add("CRM client")
        val full = ArrayList<String>()
        val where = listOf(r.optString("company"), r.optString("city")).filter { it.isNotEmpty() }.joinToString(", ")
        if (where.isNotEmpty()) full.add(where)
        if (last != null) {
            val note = last.optString("note")
            if (note.isNotEmpty()) full.add("Note: " + note)
            val tg = last.optJSONArray("tags")
            if (tg != null && tg.length() > 0) {
                val parts = ArrayList<String>()
                for (i in 0 until tg.length()) parts.add(tg.optString(i))
                full.add("Tags: " + parts.joinToString(", "))
            }
        }
        val task = r.optJSONObject("task")
        if (task != null && task.optString("title").isNotEmpty()) full.add("Follow-up: " + task.optString("title"))
        val owner = r.optString("owner")
        if (owner.isNotEmpty()) full.add("Owner: " + owner)
        val body = if (full.isEmpty()) short.joinToString(" · ") else short.joinToString(" · ") + "\n" + full.joinToString("\n")
        Notifier.show(ctx, "cid" + digits, "info", "📞 " + title, body, "", "", name)
    }
}
