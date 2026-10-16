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
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return read(c)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("network", "No connection to the server (" + (e.message ?: "network error") + ")", true)
        }
    }

    /** Multipart upload of the voice note. */
    fun uploadVoice(base: String, token: String, callId: String, file: File): JSONObject {
        try {
            val boundary = "----nh" + System.currentTimeMillis()
            val c = URL(endpoint(base)).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setRequestProperty("Authorization", "Bearer $token")
            val out = DataOutputStream(c.outputStream)
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("op", "voice")
            field("callId", callId)
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voice.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n")
            out.write(file.readBytes())
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
            out.close()
            return read(c)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("network", "No connection to the server (" + (e.message ?: "network error") + ")", true)
        }
    }
}
