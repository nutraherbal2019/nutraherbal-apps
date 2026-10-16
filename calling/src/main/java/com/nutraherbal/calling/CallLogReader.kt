package com.nutraherbal.calling

import android.content.Context
import android.provider.CallLog

data class LogRow(val id: String, val number: String, val type: Int, val date: Long, val durationSec: Int)

/** Reads the phone's call history (needs READ_CALL_LOG). */
object CallLogReader {
    fun digits(n: String): String {
        val d = n.filter { it.isDigit() }
        return if (d.length > 10) d.takeLast(10) else d
    }

    /** Newest rows since [sinceMs], optionally only for one number. */
    fun rows(ctx: Context, sinceMs: Long, onlyNumber: String?, max: Int): List<LogRow> {
        val out = ArrayList<LogRow>()
        val proj = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val cur = try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, CallLog.Calls.DATE + " >= ?", arrayOf(sinceMs.toString()), CallLog.Calls.DATE + " DESC")
        } catch (e: SecurityException) { null } catch (e: Exception) { null }
        cur?.use {
            val want = onlyNumber?.let { digits(it) }
            while (it.moveToNext() && out.size < max) {
                val number = it.getString(1) ?: ""
                if (want != null && digits(number) != want) continue
                out.add(LogRow(it.getLong(0).toString(), number, it.getInt(2), it.getLong(3), it.getInt(4)))
            }
        }
        return out
    }
}
