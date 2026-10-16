package com.nutraherbal.calling

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * NH Calling — one screen app.
 *   Not connected → server address + 6-digit code from the CRM (Calls → My phone).
 *   Connected     → today's call list from the CRM, Call button, and after every call a short form (outcome, note, voice note, next follow-up).
 */
class MainActivity : Activity() {
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private val green = Color.parseColor("#0f8a5f")
    private val ink = Color.parseColor("#1f2430")
    private val grey = Color.parseColor("#6f7c88")
    private lateinit var root: LinearLayout

    private var outcomes: List<String> = listOf("Connected", "No answer", "Busy", "Switched off / not reachable", "Wrong number", "Callback requested")

    /** A call we started from the app and are waiting to see in the phone's call log. */
    private var pendingNumber: String? = null
    private var pendingName: String = ""
    private var pendingTaskId: String = ""
    private var pendingSince: Long = 0
    private var dialogOpen = false

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#f4f6f8"))
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        if (!store.isPaired()) { showPair(); return }
        if (pendingNumber != null && !dialogOpen) { checkPendingCall(); return }
        showHome()
    }

    // ------------------------------------------------------------------ helpers
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = ink): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(if (primary) Color.WHITE else ink)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(if (primary) green else Color.parseColor("#e3e6ea"))
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        bg.setColor(Color.WHITE)
        bg.setStroke(dp(1), Color.parseColor("#e3e6ea"))
        c.background = bg
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(10))
        c.layoutParams = lp
        return c
    }

    private fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = type
        e.setSingleLine(type != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        return e
    }

    private fun bg(run: () -> Unit) { Thread { try { run() } catch (e: Exception) { /* handled inside */ } }.start() }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun askPerms() {
        val need = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO).filter { !hasPerm(it) }
        if (need.isNotEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(need.toTypedArray(), 11)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (store.isPaired()) showHome()
    }

    // ------------------------------------------------------------------ pairing
    private fun showPair() {
        root.removeAllViews()
        root.addView(text("NH Calling", 26f, true, green))
        root.addView(text("Connect this phone to your Nutraherbal CRM", 14f, false, grey))
        val c = card()
        (c.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
        c.addView(text("1. In the CRM open Calls → My phone → Connect my phone.\n2. Type the server address and the 6-digit code below.", 14f))
        val server = input("Server address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        server.setText(store.base)
        val code = input("6-digit code", InputType.TYPE_CLASS_NUMBER)
        code.textSize = 24f
        c.addView(server)
        c.addView(code)
        val msg = text("", 13f, false, Color.parseColor("#c62828"))
        c.addView(msg)
        c.addView(button("Connect") {
            val base = server.text.toString().trim()
            val cd = code.text.toString().trim()
            if (!base.startsWith("https://")) { msg.text = "Server address must start with https://"; return@button }
            if (cd.length != 6) { msg.text = "Enter the 6-digit code."; return@button }
            msg.setTextColor(grey); msg.text = "Connecting…"
            bg {
                try {
                    val r = Api.post(base, "", JSONObject().put("op", "pair").put("code", cd).put("deviceName", Build.MANUFACTURER + " " + Build.MODEL).put("appVersion", "1.1"))
                    store.base = base
                    store.token = r.getString("token")
                    store.userName = r.getJSONObject("user").optString("name")
                    ui.post { showHome() }
                } catch (e: ApiException) {
                    ui.post { msg.setTextColor(Color.parseColor("#c62828")); msg.text = e.message ?: "" }
                }
            }
        })
        root.addView(c)
    }

    private fun disconnected(msg: String) {
        store.clear()
        toast(msg)
        showPair()
    }

    // ------------------------------------------------------------------ home (tabs: Today · Recents · Contacts · Keypad)
    private var tab = 0
    private var recFilter = 0
    private var contactQuery = ""
    private var contactLimit = 60
    private var contacts: List<ContactRow>? = null
    private val contactNames = HashMap<String, String>()
    private val crmNames = HashMap<String, String>()
    private var permsAsked = false

    private fun showHome() {
        root.removeAllViews()
        if (!permsAsked) { permsAsked = true; askPerms() }
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = LinearLayout(this)
        title.orientation = LinearLayout.VERTICAL
        title.addView(text("NH Calling", 22f, true, green))
        title.addView(text("Signed in as " + store.userName, 13f, false, grey))
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button("Refresh", false) { contacts = null; crmNames.clear(); showHome() })
        root.addView(head)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        barLp.setMargins(0, dp(10), 0, dp(10))
        val tabNames = listOf("Today", "Recents", "Contacts", "Keypad")
        for (i in tabNames.indices) {
            val b = button(tabNames[i], i == tab) { tab = i; showHome() }
            b.textSize = 13f
            b.setPadding(dp(4), 0, dp(4), 0)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), 0)
            bar.addView(b, lp)
        }
        root.addView(bar, barLp)

        when (tab) {
            1 -> tabRecents()
            2 -> tabContacts()
            3 -> tabKeypad()
            else -> tabToday()
        }
    }

    // ---- one row of a list: icon, name, small line, CRM badge, Call button ----
    private fun callRow(icon: String, iconColor: Int, title: String, sub: String, subColor: Int, number: String, name: String, badges: MutableList<Pair<String, TextView>>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(7), 0, dp(7))
        row.addView(text(icon, 18f, true, iconColor), LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 15f, true))
        col.addView(text(sub, 12f, false, subColor))
        val badge = text("", 11f, true, green)
        badge.visibility = View.GONE
        col.addView(badge)
        badges.add(Pair(number, badge))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Call") { callFlow(number, name) })
        row.setOnClickListener { callFlow(number, name) }
        return row
    }

    /** Marks numbers that are clients in the CRM (one request for all the numbers on screen). */
    private fun attachBadges(list: List<Pair<String, TextView>>) {
        val need = LinkedHashMap<String, String>()
        for (p in list) {
            val d = CallLogReader.digits(p.first)
            if (d.length >= 10 && !crmNames.containsKey(d) && need.size < 60) need[d] = p.first
        }
        val applyNow = {
            for (p in list) {
                val nm = crmNames[CallLogReader.digits(p.first)]
                if (!nm.isNullOrEmpty()) { p.second.text = "CRM client · " + nm; p.second.visibility = View.VISIBLE }
            }
        }
        if (need.isEmpty()) { applyNow(); return }
        bg {
            try {
                val arr = JSONArray()
                for (v in need.values) arr.put(v)
                val r = Api.post(store.base, store.token, JSONObject().put("op", "lookupMany").put("mobiles", arr))
                val found = r.optJSONArray("found") ?: JSONArray()
                val names = HashMap<String, String>()
                for (i in 0 until found.length()) {
                    val o = found.getJSONObject(i)
                    names[CallLogReader.digits(o.optString("mobile"))] = o.optString("name").ifEmpty { "client" }
                }
                ui.post {
                    for (d in need.keys) crmNames[d] = names[d] ?: ""
                    applyNow()
                }
            } catch (e: ApiException) {
                if (e.code == "unauthenticated") ui.post { disconnected("This phone was disconnected in the CRM. Pair it again.") }
            } catch (e: Exception) { /* badges are optional */ }
        }
    }

    private fun loadContacts(after: () -> Unit) {
        if (contacts != null || !hasPerm(Manifest.permission.READ_CONTACTS)) return
        bg {
            val l = Contacts.all(this)
            ui.post {
                contactNames.clear()
                for (c in l) { val d = CallLogReader.digits(c.number); if (!contactNames.containsKey(d)) contactNames[d] = c.name }
                contacts = l
                after()
            }
        }
    }

    private fun permCard(message: String, label: String, perm: String, code: Int) {
        val c = card()
        c.addView(text(message, 14f))
        c.addView(button(label) { if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(perm), code) })
        root.addView(c)
    }

    private fun fmtDur(sec: Int): String = if (sec <= 0) "0 s" else (sec / 60).toString() + ":" + (sec % 60).toString().padStart(2, '0')

    // ---- Recents: incoming, outgoing and missed calls of the phone ----
    private fun tabRecents() {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) {
            permCard("Allow “Call logs” so your recent incoming, outgoing and missed calls appear here — tap one to call back.", "Allow call logs", Manifest.permission.READ_CALL_LOG, 13)
            return
        }
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        val labels = listOf("All", "Incoming", "Outgoing", "Missed")
        for (i in labels.indices) {
            val b = button(labels[i], i == recFilter) { recFilter = i; showHome() }
            b.textSize = 12f
            b.setPadding(dp(2), 0, dp(2), 0)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), 0, dp(2), dp(8))
            chips.addView(b, lp)
        }
        root.addView(chips)
        val box = card()
        root.addView(box)
        fun fill() {
            box.removeAllViews()
            val rows = CallLogReader.rows(this, System.currentTimeMillis() - 30L * 86400000, null, 300).filter {
                it.number.isNotEmpty() && when (recFilter) {
                    1 -> it.type == CallLog.Calls.INCOMING_TYPE
                    2 -> it.type == CallLog.Calls.OUTGOING_TYPE
                    3 -> it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE
                    else -> true
                }
            }.take(80)
            if (rows.isEmpty()) { box.addView(text("No calls in the last 30 days.", 13f, false, grey)); return }
            val badges = ArrayList<Pair<String, TextView>>()
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            for (r in rows) {
                val nm = if (r.name.isNotEmpty()) r.name else (contactNames[CallLogReader.digits(r.number)] ?: "")
                val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
                val icon = when (r.type) { CallLog.Calls.INCOMING_TYPE -> "↙"; CallLog.Calls.OUTGOING_TYPE -> "↗"; else -> "✕" }
                val kind = when (r.type) { CallLog.Calls.INCOMING_TYPE -> "Incoming"; CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; else -> "Missed" }
                val sub = kind + " · " + fmt.format(Date(r.date)) + (if (!missed && r.durationSec > 0) " · " + fmtDur(r.durationSec) else "") + (if (nm.isNotEmpty()) " · " + r.number else "")
                box.addView(callRow(icon, if (missed) Color.parseColor("#c62828") else green, if (nm.isNotEmpty()) nm else r.number, sub, if (missed) Color.parseColor("#c62828") else grey, r.number, nm, badges))
            }
            attachBadges(badges)
        }
        fill()
        loadContacts { fill() }
    }

    // ---- Contacts: the phone's contact list, searchable ----
    private fun tabContacts() {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            permCard("Allow “Contacts” so your phone contacts appear here — no need to type any number.", "Allow contacts", Manifest.permission.READ_CONTACTS, 12)
            return
        }
        val search = input("Search name or number")
        search.setText(contactQuery)
        root.addView(search)
        val box = card()
        (box.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        root.addView(box)
        fun fill() {
            box.removeAllViews()
            val all = contacts
            if (all == null) { box.addView(text("Loading contacts…", 13f, false, grey)); return }
            val q = contactQuery.trim().lowercase(Locale.getDefault())
            val qd = q.filter { it.isDigit() }
            val hits = all.filter { q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q) || (qd.length >= 3 && CallLogReader.digits(it.number).contains(qd)) }
            if (hits.isEmpty()) { box.addView(text("No contact found.", 13f, false, grey)); return }
            val badges = ArrayList<Pair<String, TextView>>()
            for (c in hits.take(contactLimit)) box.addView(callRow("☎", green, c.name, c.number, grey, c.number, c.name, badges))
            if (hits.size > contactLimit) box.addView(button("Show more (" + (hits.size - contactLimit) + " more)", false) { contactLimit += 60; fill() })
            attachBadges(badges)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { contactQuery = s?.toString() ?: ""; contactLimit = 60; fill() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        fill()
        loadContacts { fill() }
    }

    // ---- Keypad: for a number that is in neither list ----
    private fun tabKeypad() {
        val dial = card()
        dial.addView(text("Call any number", 16f, true))
        val num = input("Mobile number", InputType.TYPE_CLASS_PHONE)
        dial.addView(num)
        dial.addView(button("Call") {
            val n = num.text.toString().trim()
            if (n.filter { it.isDigit() }.length < 10) { toast("Enter a 10-digit mobile number."); return@button }
            callFlow(n, "")
        })
        root.addView(dial)
        root.addView(text("Tip: use Recents or Contacts — you do not need to type numbers.", 12f, false, grey))
    }

    /** One tap = call. Clients of the CRM are saved with outcome / note / voice note afterwards; other numbers just call. */
    private fun callFlow(number: String, name: String) {
        val d = CallLogReader.digits(number)
        val cached = crmNames[d]
        if (cached != null) { afterLookup(number, name, cached); return }
        toast("Checking client…")
        bg {
            try {
                val r = Api.post(store.base, store.token, JSONObject().put("op", "lookup").put("mobile", number))
                val nm = if (r.optBoolean("found")) r.optString("name").ifEmpty { "client" } else ""
                ui.post { crmNames[d] = nm; afterLookup(number, name, nm) }
            } catch (e: ApiException) {
                ui.post { if (e.code == "unauthenticated") disconnected("This phone was disconnected in the CRM. Pair it again.") else toast(e.message ?: "Could not check the number.") }
            }
        }
    }

    private fun afterLookup(number: String, name: String, crmName: String) {
        if (crmName.isNotEmpty()) { startCall(number, crmName, ""); return }
        AlertDialog.Builder(this)
            .setTitle(if (name.isNotEmpty()) name else number)
            .setMessage("This number is not a client in your CRM, so this call will not be saved there.")
            .setPositiveButton("Call anyway") { _, _ -> plainCall(number) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun plainCall(number: String) {
        val uri = Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })
        try {
            if (hasPerm(Manifest.permission.CALL_PHONE)) startActivity(Intent(Intent.ACTION_CALL, uri)) else startActivity(Intent(Intent.ACTION_DIAL, uri))
        } catch (e: Exception) { toast("Could not start the call: " + (e.message ?: "")) }
    }

    // ---- Today: calls due from the CRM + calls not yet saved ----
    private fun tabToday() {
        val queueBox = card()
        queueBox.addView(text("Today's calls", 16f, true))
        val qInfo = text("Loading…", 13f, false, grey)
        queueBox.addView(qInfo)
        root.addView(queueBox)

        val unBox = card()
        unBox.addView(text("Calls not yet saved in the CRM", 16f, true))
        val uInfo = text("Checking your call history…", 13f, false, grey)
        unBox.addView(uInfo)
        root.addView(unBox)

        root.addView(button("Disconnect this phone", false) {
            AlertDialog.Builder(this).setMessage("Disconnect this phone from the CRM?").setPositiveButton("Disconnect") { _, _ -> store.clear(); showPair() }.setNegativeButton("Cancel", null).show()
        })

        // load queue + flush uploads
        bg {
            val left = try { Uploader.flush(store) } catch (e: Exception) { 0 }
            try {
                val r = Api.post(store.base, store.token, JSONObject().put("op", "queue"))
                val items = r.getJSONArray("items")
                ui.post {
                    queueBox.removeView(qInfo)
                    if (items.length() == 0) queueBox.addView(text("No calls due today. 🎉", 14f, false, grey))
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val row = LinearLayout(this)
                        row.orientation = LinearLayout.HORIZONTAL
                        row.gravity = Gravity.CENTER_VERTICAL
                        row.setPadding(0, dp(8), 0, dp(8))
                        val col = LinearLayout(this)
                        col.orientation = LinearLayout.VERTICAL
                        col.addView(text(item.optString("name").ifEmpty { item.optString("mobile") }, 15f, true))
                        val due = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(item.optLong("dueAt")))
                        col.addView(text(item.optString("title") + " · " + due + if (item.optBoolean("overdue")) " · OVERDUE" else "", 12f, false, if (item.optBoolean("overdue")) Color.parseColor("#c62828") else grey))
                        if (item.optString("notes").isNotEmpty()) col.addView(text(item.optString("notes"), 12f, false, grey))
                        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(button("Call") { startCall(item.optString("mobile"), item.optString("name"), item.optString("taskId")) })
                        queueBox.addView(row)
                    }
                    if (left > 0) root.addView(text("$left call(s) waiting to upload — will retry when you are online.", 12f, false, Color.parseColor("#b26a00")), 1)
                }
            } catch (e: ApiException) { ui.post { handleErr(e, qInfo) } }
            loadUnlogged(unBox, uInfo)
        }
    }

    private fun handleErr(e: ApiException, into: TextView) {
        if (e.code == "unauthenticated") disconnected("This phone was disconnected in the CRM. Pair it again.") else into.text = e.message ?: ""
    }

    // ------------------------------------------------------------------ unlogged calls
    private fun loadUnlogged(box: LinearLayout, info: TextView) {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) { ui.post { info.text = "Allow “Call logs” permission to see calls made outside the app." }; return }
        val rows = CallLogReader.rows(this, System.currentTimeMillis() - 48L * 3600000, null, 60).filter { !store.isLogged(it.id) && it.number.isNotEmpty() }
        val found = ArrayList<Pair<LogRow, String>>()
        val seen = HashMap<String, String?>()
        for (r in rows) {
            val d = CallLogReader.digits(r.number)
            if (!seen.containsKey(d)) {
                if (seen.size >= 15) continue
                seen[d] = try {
                    val j = Api.post(store.base, store.token, JSONObject().put("op", "lookup").put("mobile", r.number))
                    if (j.optBoolean("found")) j.optString("name").ifEmpty { r.number } else null
                } catch (e: ApiException) { null }
            }
            val nm = seen[d]
            if (nm != null) found.add(Pair(r, nm))
        }
        ui.post {
            if (found.isEmpty()) { info.text = "Nothing pending."; return@post }
            box.removeView(info)
            for ((r, nm) in found.take(12)) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(6), 0, dp(6))
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.addView(text(nm, 15f, true))
                val kind = when (r.type) { CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.INCOMING_TYPE -> "Incoming"; else -> "Missed" }
                col.addView(text(kind + " · " + SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(r.date)) + " · " + r.durationSec + " s", 12f, false, grey))
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(button("Save") { summaryDialog(r.number, nm, "", r) })
                box.addView(row)
            }
        }
    }

    // ------------------------------------------------------------------ calling
    private fun startCall(number: String, name: String, taskId: String) {
        val digits = number.filter { it.isDigit() || it == '+' }
        pendingNumber = number
        pendingName = name
        pendingTaskId = taskId
        pendingSince = System.currentTimeMillis() - 2000
        val uri = Uri.parse("tel:" + digits)
        try {
            if (hasPerm(Manifest.permission.CALL_PHONE)) startActivity(Intent(Intent.ACTION_CALL, uri)) else startActivity(Intent(Intent.ACTION_DIAL, uri))
        } catch (e: Exception) {
            pendingNumber = null
            toast("Could not start the call: " + (e.message ?: ""))
        }
    }

    /** Back in the app after a call: find it in the call log and ask for the outcome. */
    private fun checkPendingCall() {
        val number = pendingNumber ?: return
        dialogOpen = true
        bg {
            var row: LogRow? = null
            if (hasPerm(Manifest.permission.READ_CALL_LOG)) {
                for (attempt in 0 until 3) {
                    row = CallLogReader.rows(this, pendingSince, number, 1).firstOrNull()
                    if (row != null) break
                    Thread.sleep(1200)
                }
            }
            ui.post {
                val nm = pendingName
                val tid = pendingTaskId
                pendingNumber = null
                showHome()
                summaryDialog(number, nm, tid, row)
            }
        }
    }

    private fun nextAtFor(choice: Int): Long {
        val c = Calendar.getInstance()
        when (choice) {
            1 -> c.add(Calendar.HOUR_OF_DAY, 2)
            2 -> { c.add(Calendar.DAY_OF_YEAR, 1); c.set(Calendar.HOUR_OF_DAY, 10); c.set(Calendar.MINUTE, 0) }
            3 -> { c.add(Calendar.DAY_OF_YEAR, 3); c.set(Calendar.HOUR_OF_DAY, 10); c.set(Calendar.MINUTE, 0) }
            4 -> { c.add(Calendar.DAY_OF_YEAR, 7); c.set(Calendar.HOUR_OF_DAY, 10); c.set(Calendar.MINUTE, 0) }
            else -> return 0
        }
        return c.timeInMillis
    }

    /** Form shown after a call: outcome, note, voice note, next follow-up. */
    private fun summaryDialog(number: String, name: String, taskId: String, row: LogRow?) {
        dialogOpen = true
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(10), dp(18), dp(4))
        val dur = row?.durationSec ?: 0
        val kind = when (row?.type) { CallLog.Calls.INCOMING_TYPE -> "in"; CallLog.Calls.OUTGOING_TYPE -> "out"; null -> "out"; else -> "missed" }
        box.addView(text((if (name.isNotEmpty()) name + " · " else "") + number, 15f, true))
        box.addView(text(if (row != null) "Call time: " + dur + " seconds" else "Call time not found in the phone's history — please check the outcome.", 12f, false, grey))
        val outcome = Spinner(this)
        outcome.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, outcomes)
        outcome.setSelection(if (dur > 0 && kind != "missed") 0 else 1)
        box.addView(text("Outcome", 12f, false, grey))
        box.addView(outcome)
        val note = input("What was discussed? (short note)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        note.minLines = 2
        box.addView(note)
        val voiceInfo = text("Or record a voice note (up to 60 s, Hindi / English / Hinglish)", 12f, false, grey)
        box.addView(voiceInfo)
        val recBtn = button("● Record voice note", false) {}
        recBtn.setOnClickListener {
            if (recorder == null) {
                if (!hasPerm(Manifest.permission.RECORD_AUDIO)) { askPerms(); return@setOnClickListener }
                if (startRecording()) { recBtn.text = "■ Stop recording"; voiceInfo.text = "Recording… speak now" }
            } else {
                stopRecording()
                recBtn.text = "● Record again"
                voiceInfo.text = if (recFile != null) "Voice note ready ✔" else "Recording failed"
            }
        }
        box.addView(recBtn)
        val next = Spinner(this)
        next.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No follow-up", "Call again in 2 hours", "Tomorrow 10 am", "In 3 days (10 am)", "Next week (10 am)"))
        box.addView(text("Next follow-up", 12f, false, grey))
        box.addView(next)
        val sv = ScrollView(this)
        sv.addView(box)

        val d = AlertDialog.Builder(this).setTitle("Save this call").setView(sv).setCancelable(false)
            .setPositiveButton("Save", null).setNegativeButton("Skip", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (recorder != null) { stopRecording() }
                val body = JSONObject()
                    .put("op", "log")
                    .put("mobile", number)
                    .put("direction", kind)
                    .put("startedAt", row?.date ?: (System.currentTimeMillis() - dur * 1000L))
                    .put("durationSec", dur)
                    .put("outcome", outcomes[outcome.selectedItemPosition])
                    .put("note", note.text.toString().trim())
                    .put("nextAt", nextAtFor(next.selectedItemPosition))
                    .put("taskId", taskId)
                    .put("deviceCallId", "cl-" + (row?.id ?: ("m" + System.currentTimeMillis())))
                store.enqueue(body, recFile?.absolutePath ?: "")
                if (row != null) store.markLogged(row.id)
                recFile = null
                dialogOpen = false
                d.dismiss()
                toast("Saving to CRM…")
                bg { Uploader.flush(store); ui.post { showHome() } }
            }
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (recorder != null) stopRecording()
                recFile?.delete(); recFile = null
                if (row != null) store.markLogged(row.id)
                dialogOpen = false
                d.dismiss()
                showHome()
            }
        }
        d.show()
    }

    // ------------------------------------------------------------------ voice note
    private fun startRecording(): Boolean {
        return try {
            val f = File(cacheDir, "vn_" + System.currentTimeMillis() + ".m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(60000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recFile = f
            true
        } catch (e: Exception) {
            recorder = null
            toast("Could not start recording: " + (e.message ?: ""))
            false
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        try { r.stop() } catch (e: Exception) { recFile?.delete(); recFile = null }
        try { r.release() } catch (e: Exception) { /* ignore */ }
        recorder = null
    }
}
