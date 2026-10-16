package com.nutraherbal.calling

import android.content.Context
import android.provider.ContactsContract

data class ContactRow(val name: String, val number: String)

/** Reads the phone's contact list (needs READ_CONTACTS). */
object Contacts {
    fun all(ctx: Context): List<ContactRow> {
        val out = ArrayList<ContactRow>()
        val seen = HashSet<String>()
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val cur = try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )
        } catch (e: SecurityException) { null } catch (e: Exception) { null }
        cur?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: ""
                val num = it.getString(1) ?: ""
                val d = CallLogReader.digits(num)
                if (d.length < 7) continue
                if (!seen.add(name + "|" + d)) continue
                out.add(ContactRow(if (name.isEmpty()) num else name, num))
            }
        }
        return out
    }
}
