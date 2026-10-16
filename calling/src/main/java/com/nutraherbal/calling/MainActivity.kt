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
import android.text.InputType
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
        val need = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO).filter { !hasPerm(it) }
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
                    val r = Api.post(base, "", JSONObject().put("op", "pair").put("code", cd).put("deviceName", Build.MANUFACTURER + " " + Build.MODEL).put("appVersion", "1.0"))
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

    // ------------------------------------------------------------------ home
    private fun showHome() {
        root.removeAllViews()
        askPerms()
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = LinearLayout(this)
        title.orientation = LinearLayout.VERTICAL
        title.addView(text("NH Calling", 22f, true, green))
        title.addView(text("Signed in as " + store.userName, 13f, false, grey))
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button("Refresh", false) { showHome() })
        root.addView(head)

        // dial any number
        val dial = card()
        (dial.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        dial.addView(text("Call a client", 16f, true))
        val num = input("Mobile number", InputType.TYPE_CLASS_PHONE)
        dial.addView(num)
        val info = text("", 13f, false, grey)
        dial.addView(info)
        dial.addView(button("Check & call") {
            val n = num.text.toString().trim()
            if (n.filter { it.isDigit() }.length < 10) { info.text = "Enter a 10-digit mobile number."; return@button }
            info.text = "Looking up…"
            bg {
                try {
                    val r = Api.post(store.base, store.token, JSONObject().put("op", "lookup").put("mobile", n))
                    ui.post {
                        if (r.optBoolean("found")) startCall(n, r.optString("name"), "")
                        else info.text = "This number is not in your clients in the CRM. Add the enquiry in the CRM first."
                    }
                } catch (e: ApiException) { ui.post { handleErr(e, info) } }
            }
        })
        root.addView(dial)

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
