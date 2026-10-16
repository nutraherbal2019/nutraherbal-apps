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

    fun isPaired(): Boolean = token.isNotEmpty()

    fun clear() { sp.edit().remove("token").remove("user").remove("queue").apply() }

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
    @Synchronized fun enqueue(body: JSONObject, voicePath: String) {
        val q = queue()
        q.put(JSONObject().put("body", body).put("voice", voicePath))
        saveQueue(q)
    }
}
