package com.nutraherbal.calling

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: String, message: String, val network: Boolean = false) : Exception(message)

/** Talks to <server>/api/callapp.php. Plain HttpURLConnection — no extra libraries. */
object Api {
    private fun endpoint(base: String) = base.trimEnd('/') + "/api/callapp.php"

    private fun read(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val j = try { JSONObject(text) } catch (e: Exception) { throw ApiException("bad-response", "Server answered with something unexpected (HTTP $code). Check the server address.") }
        if (j.has("error")) {
            val e = j.getJSONObject("error")
            throw ApiException(e.optString("code"), e.optString("message", "Error"))
        }
        return j
    }

    fun post(base: String, token: String, body: JSONObject): JSONObject {
        try {
            val c = URL(endpoint(base)).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            if (token.isNotEmpty()) { c.setRequestProperty("X-NH-Token", token); body.put("token", token) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return read(c)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("network", "No connection to the server (" + (e.message ?: "network error") + ")", true)
        }
    }

    /** Voice note (kept for older call sites). */
    fun uploadVoice(base: String, token: String, callId: String, file: File): JSONObject = uploadAudio(base, token, "voice", callId, file)

    /** Multipart upload of an audio file: op = "voice" (voice note) or "recording" (the phone's call recording). */
    fun uploadAudio(base: String, token: String, op: String, callId: String, file: File): JSONObject {
        try {
            val boundary = "----nh" + System.currentTimeMillis()
            val sb = StringBuilder()
            fun field(name: String, value: String) {
                sb.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n").append(value).append("\r\n")
            }
            field("op", op)
            field("token", token)
            field("callId", callId)
            val ext = file.name.substringAfterLast('.', "m4a")
            sb.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"").append(op).append(".").append(ext).append("\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            val head = sb.toString().toByteArray(Charsets.UTF_8)
            val tail = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)
            val c = URL(endpoint(base)).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 180000
            c.doOutput = true
            c.setFixedLengthStreamingMode(head.size.toLong() + file.length() + tail.size.toLong())
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-NH-Token", token)
            c.outputStream.use { out ->
                out.write(head)
                file.inputStream().use { ins -> ins.copyTo(out) }
                out.write(tail)
                out.flush()
            }
            return read(c)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("network", "No connection to the server (" + (e.message ?: "network error") + ")", true)
        }
    }
}
