package com.nutraherbal.calling

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import org.json.JSONObject

/**
 * v1.4 — WhatsApp call LOG (who, when, how long). It never records any sound.
 * It only looks at WhatsApp's own call notifications (ringing / ongoing / missed), after the agent gives "Notification access".
 * Only calls with people who are CRM clients are saved by the server; everything else is dropped there.
 */
class WaCallListener : NotificationListenerService() {
    private class St(var name: String, var dir: String, var video: Boolean, val startedAt: Long, var connectedAt: Long = 0L, var token: Int = 0)

    private val handler = Handler(Looper.getMainLooper())
    private val calls = HashMap<String, St>()          // by package
    private val seenMissed = HashSet<String>()

    private fun isWa(pkg: String?) = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try { handle(sbn ?: return) } catch (e: Exception) { /* never crash the listener */ }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (!isWa(sbn.packageName)) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()
        val low = (title + " " + text).lowercase()
        val isCallCat = n.category == Notification.CATEGORY_CALL
        // Missed call (an ordinary notification)
        if (!isCallCat && low.contains("missed") && low.contains("call")) {
            val key = sbn.key + "|" + sbn.postTime
            if (seenMissed.add(key)) {
                val who = if (text.lowercase().contains("missed")) title else title
                log(who, "missed", low.contains("video"), sbn.postTime, 0)
            }
            if (seenMissed.size > 200) seenMissed.clear()
            return
        }
        if (!isCallCat) return
        val pkg = sbn.packageName
        val name = title.ifEmpty { text }
        val video = low.contains("video")
        val st = calls[pkg]
        val now = System.currentTimeMillis()
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val connectedWords = low.contains("ongoing") || ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val incomingWords = low.contains("incoming")
        val outgoingWords = low.contains("calling") || low.contains("ringing") || low.contains("connecting")
        if (st == null) {
            val dir = if (incomingWords) "in" else if (outgoingWords) "out" else if (connectedWords) "out" else "in"
            val s = St(name, dir, video, now)
            if (connectedWords && !incomingWords && !outgoingWords) s.connectedAt = now
            calls[pkg ?: ""] = s
        } else {
            st.token++                                  // cancels a pending "call ended" check
            if (name.isNotEmpty()) st.name = name
            if (video) st.video = true
            if (connectedWords && st.connectedAt == 0L && !incomingWords && !outgoingWords) st.connectedAt = now
            else if (ongoing && st.connectedAt == 0L && !incomingWords && !outgoingWords && !connectedWords && now - st.startedAt > 4000) st.connectedAt = now
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            if (sbn == null || !isWa(sbn.packageName)) return
            if (sbn.notification?.category != Notification.CATEGORY_CALL) return
            val pkg = sbn.packageName ?: ""
            val st = calls[pkg] ?: return
            val tok = ++st.token
            // WhatsApp may swap the ringing notification for the ongoing one — wait a moment before calling it the end.
            handler.postDelayed({
                val cur = calls[pkg]
                if (cur === st && st.token == tok) { calls.remove(pkg); finish(st) }
            }, 2500)
        } catch (e: Exception) { }
    }

    private fun finish(st: St) {
        val end = System.currentTimeMillis()
        if (st.connectedAt > 0L) log(st.name, st.dir, st.video, st.connectedAt, ((end - st.connectedAt) / 1000).toInt().coerceAtLeast(1))
        else if (st.dir == "in") log(st.name, "missed", st.video, st.startedAt, 0)
        else log(st.name, "out", st.video, st.startedAt, 0)
    }

    private fun log(who: String, dir: String, video: Boolean, startedAt: Long, dur: Int) {
        val store = Store(applicationContext)
        if (!store.isPaired() || who.isEmpty()) return
        val digits = who.filter { it.isDigit() }
        val numberLike = who.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' } && digits.length >= 7
        val mobile = if (numberLike) digits else numberFor(who)
        val body = JSONObject()
            .put("op", "log")
            .put("channel", "whatsapp")
            .put("video", video)
            .put("mobile", mobile)
            .put("contactName", if (numberLike) "" else who)
            .put("direction", dir)
            .put("startedAt", startedAt)
            .put("durationSec", dur)
            .put("note", "WhatsApp " + (if (video) "video" else "voice") + " call")
            .put("deviceCallId", "wa-" + startedAt + "-" + who.hashCode())
        Thread {
            try { store.enqueue(body, "", ""); Uploader.flush(store) } catch (e: Exception) { }
        }.start()
    }

    /** Name → phone number from the phone's contacts (empty if not allowed / not found). */
    private fun numberFor(name: String): String {
        return try {
            val c = applicationContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?", arrayOf(name), null)
            c?.use { if (it.moveToFirst()) it.getString(0).filter { ch -> ch.isDigit() } else "" } ?: ""
        } catch (e: Exception) { "" }
    }

    companion object {
        fun enabled(ctx: Context): Boolean = try {
            (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName)
        } catch (e: Exception) { false }
        fun component(ctx: Context) = ComponentName(ctx, WaCallListener::class.java)
    }
}
