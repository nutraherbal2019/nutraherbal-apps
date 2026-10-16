package com.nutraherbal.calling

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sends queued calls (and their voice notes) to the CRM; keeps whatever fails for the next try. */
object Uploader {
    /** Returns how many items are still waiting. Call from a background thread. */
    @Synchronized fun flush(store: Store): Int {
        val q = store.queue()
        val keep = JSONArray()
        var disconnected = false
        for (i in 0 until q.length()) {
            val item = q.getJSONObject(i)
            if (disconnected) { keep.put(item); continue }
            val body: JSONObject = item.getJSONObject("body")
            val voice = item.optString("voice", "")
            try {
                var callId = item.optString("callId", "")
                if (callId.isEmpty()) {
                    val r = Api.post(store.base, store.token, body)
                    callId = r.optString("id", "")
                    item.put("callId", callId)
                }
                if (voice.isNotEmpty() && File(voice).exists() && callId.isNotEmpty()) {
                    Api.uploadVoice(store.base, store.token, callId, File(voice))
                    File(voice).delete()
                }
                // done
            } catch (e: ApiException) {
                if (e.network) { keep.put(item) }
                else if (e.code == "unauthenticated") { keep.put(item); disconnected = true }
                else if (e.code == "not-found" || e.code == "permission-denied" || e.code == "invalid-argument") { /* will never succeed: drop */ }
                else keep.put(item)
            }
        }
        store.saveQueue(keep)
        return keep.length()
    }
}
