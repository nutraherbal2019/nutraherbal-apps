package com.nutraherbal.calling

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small wrapper around SharedPreferences: server address, device token, offline queue, already-logged call ids. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nh_calling", Context.MODE_PRIVATE)

    var base: String
        get() = sp.getString("base", "https://nutraherbalwellness.com/crm") ?: ""
        set(v) { sp.edit().putString("base", v.trim().trimEnd('/')).apply() }
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }
    var userName: String
        get() = sp.getString("user", "") ?: ""
        set(v) { sp.edit().putString("user", v).apply() }

    /** Folder (Storage Access Framework tree URI) where the phone's own call recorder saves its files. */
    var recTree: String
        get() = sp.getString("recTree", "") ?: ""
        set(v) { sp.edit().putString("recTree", v).apply() }

    /** Numbers the agent marked "personal": never asked to add them as leads. */
    fun isPersonal(digits: String): Boolean = (sp.getStringSet("personal", emptySet()) ?: emptySet()).contains(digits)
    fun addPersonal(digits: String) {
        val s = HashSet(sp.getStringSet("personal", emptySet()) ?: emptySet())
        s.add(digits)
        sp.edit().putStringSet("personal", s).apply()
    }

    fun isPaired(): Boolean = token.isNotEmpty()

    fun clear() { sp.edit().remove("token").remove("user").remove("queue").remove("alertsJson").remove("planJson").remove("sched").apply() }

    // ---- ids of call-log rows already sent (so they are not offered again) ----
    fun isLogged(id: String): Boolean = (sp.getStringSet("logged", emptySet()) ?: emptySet()).contains(id)
    fun markLogged(id: String) {
        val s = HashSet(sp.getStringSet("logged", emptySet()) ?: emptySet())
        s.add(id)
        // keep the set small
        val trimmed = if (s.size > 400) s.toList().takeLast(300).toHashSet() else s
        sp.edit().putStringSet("logged", trimmed).apply()
    }

    // ---- offline queue: [{"body":{...log...},"voice":"/path/or empty"}] ----
    @Synchronized fun queue(): JSONArray = try { JSONArray(sp.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
    @Synchronized fun saveQueue(a: JSONArray) { sp.edit().putString("queue", a.toString()).apply() }
    @Synchronized fun enqueue(body: JSONObject, voicePath: String, recPath: String = "") {
        val q = queue()
        q.put(JSONObject().put("body", body).put("voice", voicePath).put("rec", recPath))
        saveQueue(q)
    }

    // ---- v1.3: alerts / reminders ----
    /** Last alert list from the CRM (JSON array text) — used to re-arm alarms after a restart. */
    var alertsJson: String
        get() = sp.getString("alertsJson", "[]") ?: "[]"
        set(v) { sp.edit().putString("alertsJson", v).apply() }
    var planJson: String
        get() = sp.getString("planJson", "{}") ?: "{}"
        set(v) { sp.edit().putString("planJson", v).apply() }
    var lastPlanDay: String
        get() = sp.getString("lastPlanDay", "") ?: ""
        set(v) { sp.edit().putString("lastPlanDay", v).apply() }
    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) { sp.edit().putLong("lastSync", v).apply() }
    var alertsOn: Boolean
        get() = sp.getBoolean("alertsOn", true)
        set(v) { sp.edit().putBoolean("alertsOn", v).apply() }

    fun isFired(key: String): Boolean = (sp.getStringSet("fired", emptySet()) ?: emptySet()).contains(key)
    fun markFired(key: String) {
        val s = HashSet(sp.getStringSet("fired", emptySet()) ?: emptySet())
        s.add(key)
        val trimmed = if (s.size > 500) s.toList().takeLast(350).toHashSet() else s
        sp.edit().putStringSet("fired", trimmed).apply()
    }
    fun scheduledIds(): Set<String> = HashSet(sp.getStringSet("sched", emptySet()) ?: emptySet())
    fun saveScheduledIds(s: Set<String>) { sp.edit().putStringSet("sched", HashSet(s)).apply() }

    fun setSnooze(key: String, until: Long) { sp.edit().putLong("snz_" + key, until).apply() }
    fun snoozedUntil(key: String): Long = sp.getLong("snz_" + key, 0L)
}
