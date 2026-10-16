package com.nutraherbal.calling

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AlertItem(val id: String, val kind: String, val title: String, val body: String, val at: Long, val dueAt: Long, val mobile: String, val taskId: String, val name: String)

/** Pop-up (heads-up) notifications: call reminders, meetings, pending payments, daily plan. */
object Notifier {
    const val CH_CALL = "nh_calls"
    const val CH_PAY = "nh_pay"
    const val CH_INFO = "nh_info"
    const val CH_PLAN = "nh_plan"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun mk(id: String, name: String, desc: String) {
            if (nm.getNotificationChannel(id) != null) return
            val c = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            c.description = desc
            c.enableVibration(true)
            c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(c)
        }
        mk(CH_CALL, "Call & meeting reminders", "Follow-up calls, meetings and tasks that are due")
        mk(CH_PAY, "Pending payments", "Customers whose payment is pending")
        mk(CH_INFO, "CRM alerts", "Other alerts from the CRM")
        mk(CH_PLAN, "Daily plan & report", "Morning plan and evening report")
    }

    fun allowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun channelFor(kind: String): String = when (kind) {
        "payment" -> CH_PAY
        "plan", "report" -> CH_PLAN
        "info" -> CH_INFO
        else -> CH_CALL
    }

    fun show(ctx: Context, key: String, kind: String, title: String, body: String, mobile: String, taskId: String, name: String) {
        if (!allowed(ctx)) return
        channels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channelFor(kind)) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_call)
        b.setContentTitle(title)
        b.setContentText(body)
        b.setStyle(Notification.BigTextStyle().bigText(body))
        b.setAutoCancel(true)
        b.setColor(T.accent)
        b.setWhen(System.currentTimeMillis())
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION") b.setPriority(Notification.PRIORITY_HIGH)
            @Suppress("DEPRECATION") b.setDefaults(Notification.DEFAULT_ALL)
        }
        b.setCategory(if (kind == "payment" || kind == "info") Notification.CATEGORY_MESSAGE else Notification.CATEGORY_REMINDER)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val req = key.hashCode()
        val open = Intent(ctx, MainActivity::class.java)
        open.putExtra("openTab", if (kind == "report") 4 else 0)
        open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        b.setContentIntent(PendingIntent.getActivity(ctx, req, open, flags))
        if (mobile.isNotEmpty() && Build.VERSION.SDK_INT >= 23) {
            val dial = Intent(ctx, MainActivity::class.java)
            dial.action = Intent.ACTION_DIAL
            dial.data = Uri.parse("tel:" + T.dialable(mobile))
            dial.putExtra("taskId", taskId)
            dial.putExtra("name", name)
            dial.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val callPi = PendingIntent.getActivity(ctx, req + 1, dial, flags)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_call), "Call now", callPi).build())
        }
        if (kind == "call" || kind == "meeting" || kind == "task") {
            val sn = Intent(ctx, AlarmReceiver::class.java)
            sn.action = "com.nutraherbal.calling.SNOOZE"
            sn.data = Uri.parse("nh://snooze/" + key)
            sn.putExtra("key", key).putExtra("kind", kind).putExtra("title", title).putExtra("body", body).putExtra("mobile", mobile).putExtra("taskId", taskId).putExtra("name", name)
            val snPi = PendingIntent.getBroadcast(ctx, req + 2, sn, flags)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_clock), "Snooze 10 min", snPi).build())
        }
        try { nm.notify(req, b.build()) } catch (e: Exception) { /* notification is best effort */ }
    }
}

/** Alarms (exact when the phone allows it) that pop a notification at the reminder time. */
object Sched {
    private fun pi(ctx: Context, key: String, extras: Intent?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        i.action = "com.nutraherbal.calling.ALERT"
        i.data = Uri.parse("nh://alert/" + key)
        if (extras != null) i.putExtras(extras)
        return PendingIntent.getBroadcast(ctx, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun setAt(ctx: Context, key: String, at: Long, extras: Intent?) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = pi(ctx, key, extras)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun cancel(ctx: Context, key: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(ctx, key, null))
    }

    fun schedule(ctx: Context, a: AlertItem, key: String, at: Long) {
        val ex = Intent()
        ex.putExtra("key", key).putExtra("kind", a.kind).putExtra("title", a.title).putExtra("body", a.body).putExtra("mobile", a.mobile).putExtra("taskId", a.taskId).putExtra("name", a.name)
        setAt(ctx, a.id, at, ex)
    }

    /** Next 9:00 (morning plan) and 18:30 (evening report) alarms. */
    fun planAlarms(ctx: Context) {
        planOne(ctx, "plan_morning", "plan", 9, 0)
        planOne(ctx, "plan_evening", "report", 18, 30)
    }

    private fun planOne(ctx: Context, key: String, kind: String, hour: Int, minute: Int) {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis() + 60000) c.add(Calendar.DAY_OF_YEAR, 1)
        val ex = Intent()
        ex.putExtra("key", key).putExtra("kind", kind).putExtra("title", "").putExtra("body", "").putExtra("mobile", "").putExtra("taskId", "").putExtra("name", "")
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi(ctx, key, ex)) } catch (e: Exception) { /* ignore */ }
    }

    fun ensureJob(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(1001) != null) return
        val info = JobInfo.Builder(1001, ComponentName(ctx, SyncService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        js.schedule(info)
    }

    fun cancelAll(ctx: Context, st: Store) {
        for (id in st.scheduledIds()) cancel(ctx, id)
        st.saveScheduledIds(emptySet())
        cancel(ctx, "plan_morning")
        cancel(ctx, "plan_evening")
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        js.cancel(1001)
    }
}

/** Download the alert list from the CRM and (re)arm the alarms. */
object AlertSync {
    fun parse(text: String): List<AlertItem> {
        val out = ArrayList<AlertItem>()
        try {
            val a = JSONArray(text)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(AlertItem(o.optString("id"), o.optString("kind"), o.optString("title"), o.optString("body"), o.optLong("at"), o.optLong("dueAt"), o.optString("mobile"), o.optString("taskId"),
                    o.optString("title").substringAfter("· ", "")))
            }
        } catch (e: Exception) { /* empty */ }
        return out
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Network + apply. Call from a background thread. */
    fun run(ctx: Context): Boolean {
        val st = Store(ctx)
        if (!st.isPaired()) return false
        val r = try { Api.post(st.base, st.token, JSONObject().put("op", "alerts")) } catch (e: ApiException) {
            if (e.code == "unauthenticated") Sched.cancelAll(ctx, st)
            return false
        }
        st.alertsJson = (r.optJSONArray("items") ?: JSONArray()).toString()
        st.planJson = (r.optJSONObject("plan") ?: JSONObject()).toString()
        st.lastSync = System.currentTimeMillis()
        apply(ctx, st)
        return true
    }

    private fun keyFor(a: AlertItem): String = if (a.kind == "payment") a.id + "@" + today() else a.id

    /** Arm alarms from the saved list (also used after a reboot, with no network). */
    fun apply(ctx: Context, st: Store) {
        if (!st.isPaired() || !st.alertsOn) return
        Notifier.channels(ctx)
        Sched.planAlarms(ctx)
        val now = System.currentTimeMillis()
        val keep = HashSet<String>()
        for (a in parse(st.alertsJson)) {
            var at = a.at
            if (a.kind == "payment") {
                val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 11); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0)
                at = if (c.timeInMillis > now) c.timeInMillis else now
            }
            if (a.kind == "info") at = now
            val key = keyFor(a)
            if (st.isFired(key)) { if (st.snoozedUntil(key) > now) keep.add(key); continue }
            if (at <= now + 20000) {
                st.markFired(key)
                Notifier.show(ctx, key, a.kind, a.title, a.body, a.mobile, a.taskId, a.name)
            } else {
                keep.add(a.id)
                Sched.schedule(ctx, a, key, at)
            }
        }
        for (old in st.scheduledIds()) if (!keep.contains(old)) Sched.cancel(ctx, old)
        st.saveScheduledIds(keep)
    }

    fun planText(st: Store, evening: Boolean): Pair<String, String> {
        val p = try { JSONObject(st.planJson) } catch (e: Exception) { JSONObject() }
        val name = p.optString("name").substringBefore(" ")
        if (evening) {
            val calls = p.optInt("callsToday")
            val talk = p.optInt("talkSecToday")
            val sc = p.optDouble("avgScoreToday", 0.0)
            return Pair("Aaj ka report" + if (name.isNotEmpty()) ", $name" else "", "$calls calls · ${p.optInt("connectedToday")} connected · talk ${T.dur(talk)}" + (if (sc > 0) " · AI rating " + String.format("%.1f", sc) + "/10" else "") +
                (if (p.optInt("tasksToday") > 0) "\nAbhi bhi ${p.optInt("tasksToday")} follow-up baaki hain." else ""))
        }
        val parts = ArrayList<String>()
        parts.add(p.optInt("tasksToday").toString() + " calls/follow-ups aaj")
        if (p.optInt("overdue") > 0) parts.add(p.optInt("overdue").toString() + " overdue")
        if (p.optInt("pendingCount") > 0) parts.add(T.rupees(p.optDouble("pendingAmount")) + " payment baaki (" + p.optInt("pendingCount") + " orders)")
        return Pair("Good morning" + if (name.isNotEmpty()) ", $name" else "", parts.joinToString(" · "))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val st = Store(ctx)
        if (!st.isPaired() || !st.alertsOn) return
        val key = intent.getStringExtra("key") ?: return
        val kind = intent.getStringExtra("kind") ?: "call"
        if (intent.action == "com.nutraherbal.calling.SNOOZE") {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(key.hashCode())
            val a = AlertItem(key, kind, intent.getStringExtra("title") ?: "", intent.getStringExtra("body") ?: "", 0, 0, intent.getStringExtra("mobile") ?: "", intent.getStringExtra("taskId") ?: "", intent.getStringExtra("name") ?: "")
            val until = System.currentTimeMillis() + 10 * 60000L
            st.setSnooze(key, until)
            Sched.schedule(ctx, a, key, until)
            return
        }
        if (kind == "plan" || kind == "report") {
            val pr = goAsync()
            Thread {
                try {
                    try { AlertSync.run(ctx) } catch (e: Exception) { /* use the saved numbers */ }
                    val tx = AlertSync.planText(st, kind == "report")
                    Notifier.show(ctx, key + "@" + AlertSync.today(), kind, tx.first, tx.second, "", "", "")
                    Sched.planAlarms(ctx)
                } finally { pr.finish() }
            }.start()
            return
        }
        st.markFired(key)
        Notifier.show(ctx, key, kind, intent.getStringExtra("title") ?: "", intent.getStringExtra("body") ?: "", intent.getStringExtra("mobile") ?: "", intent.getStringExtra("taskId") ?: "", intent.getStringExtra("name") ?: "")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val st = Store(ctx)
        if (!st.isPaired()) return
        try {
            Sched.ensureJob(ctx)
            AlertSync.apply(ctx, st)
        } catch (e: Exception) { /* ignore */ }
    }
}

/** Runs every ~15 minutes (even when the app is closed) to fetch new alerts. */
class SyncService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { AlertSync.run(applicationContext) } catch (e: Exception) { /* try again next time */ }
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
