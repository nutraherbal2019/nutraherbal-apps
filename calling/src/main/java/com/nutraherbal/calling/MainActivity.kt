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
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
 * NH Calling v1.3 — CRM-themed dialer.
 *   Tabs: Home (progress, alerts, today's calls, pending payments) · Recents · Contacts · Keypad · Reports, "More" from the avatar.
 *   Reminders / meetings / pending payments / daily plan arrive as pop-up notifications (see Alerts.kt).
 */
class MainActivity : Activity() {
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private val green: Int get() = T.green
    private val ink: Int get() = T.ink
    private val grey: Int get() = T.muted
    private lateinit var root: LinearLayout
    private lateinit var headerBox: LinearLayout
    private lateinit var navBox: LinearLayout
    private lateinit var scroll: ScrollView

    private var outcomes: List<String> = listOf("Connected", "No answer", "Busy", "Switched off / not reachable", "Wrong number", "Callback requested")

    /** A call we started from the app and are waiting to see in the phone's call log. */
    private var pendingNumber: String? = null
    private var pendingName: String = ""
    private var pendingTaskId: String = ""
    private var pendingSince: Long = 0
    private var dialogOpen = false
    private var pendingLead = false
    private var dialName = ""
    private var dialTask = ""

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null

    private val MM = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        T.apply(T.isDark(this, store.themeMode))   // v1.5 dark mode — must happen before any screen is built
        setTheme(if (T.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        try { window.statusBarColor = T.accentDark } catch (e: Exception) { /* cosmetic */ }
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(T.bg)
        headerBox = LinearLayout(this)
        headerBox.orientation = LinearLayout.VERTICAL
        outer.addView(headerBox, LinearLayout.LayoutParams(MM, WC))
        scroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(14), dp(14), dp(18))
        scroll.addView(root, ViewGroup.LayoutParams(MM, WC))
        outer.addView(scroll, LinearLayout.LayoutParams(MM, 0, 1f))
        navBox = LinearLayout(this)
        navBox.orientation = LinearLayout.HORIZONTAL
        navBox.setBackgroundColor(T.surface)
        navBox.elevation = dp(8).toFloat()
        outer.addView(navBox, LinearLayout.LayoutParams(MM, WC))
        setContentView(outer)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** A tel: link opened with this app (NH CRM app, CRM in a browser, a notification's “Call now”, any other app). */
    private fun takeDialIntent(): String? {
        val ci = intent ?: return null
        val a = ci.action
        val d = ci.data
        if ((a != Intent.ACTION_DIAL && a != Intent.ACTION_VIEW) || d == null || d.scheme != "tel") return null
        dialName = ci.getStringExtra("name") ?: ""
        dialTask = ci.getStringExtra("taskId") ?: ""
        setIntent(Intent())
        val n = d.schemeSpecificPart ?: ""
        return if (n.filter { c -> c.isDigit() }.length >= 7) n else null
    }

    override fun onResume() {
        super.onResume()
        if (!store.isPaired()) { setIntent(Intent()); showPair(); return }
        try { Sched.ensureJob(this) } catch (e: Exception) { /* background sync is optional */ }
        val onceTab = store.openTabOnce
        if (onceTab >= 0) { tab = onceTab; store.openTabOnce = -1 }
        bg { refreshTags() }
        val openTab = intent?.getIntExtra("openTab", -1) ?: -1
        if (openTab >= 0) { tab = openTab; setIntent(Intent()) }
        val dialNumber = takeDialIntent()
        if (dialNumber != null) {
            tab = 0
            showHome()
            callFlow(dialNumber, dialName, dialTask)
            return
        }
        if (pendingNumber != null && !dialogOpen) { checkPendingCall(); return }
        showHome()
    }

    // ------------------------------------------------------------------ helpers
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun hp(weight: Float = 1f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, WC, weight)
    private fun mp(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(MM, WC)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = ink): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    /** Primary = purple gradient, otherwise soft grey-purple. */
    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.stateListAnimator = null
        b.setTypeface(null, Typeface.BOLD)
        b.setTextColor(if (primary) Color.WHITE else T.accent)
        b.background = if (primary) T.gradient(this, T.grads[0], 12) else T.round(this, T.accentSoft, 12)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(MM, WC)
        lp.setMargins(0, dp(6), 0, 0)
        b.layoutParams = lp
        return b
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = T.round(this, T.surface, 16, T.line)
        c.layoutParams = mp(0, 0, 0, 12)
        return c
    }

    private fun input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = type
        e.setSingleLine(type != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        return e
    }

    private fun icon(res: Int, sizeDp: Int, color: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(color)
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    private fun avatar(name: String, sizeDp: Int): TextView {
        val t = TextView(this)
        t.text = T.initials(name)
        t.setTextColor(Color.WHITE)
        t.textSize = if (sizeDp >= 44) 16f else 13f
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, T.avatarGrad(name))
        g.shape = GradientDrawable.OVAL
        t.background = g
        t.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return t
    }

    /** Round icon button (green call button, etc.). */
    private fun roundBtn(res: Int, bgColor: Int, sizeDp: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(Color.WHITE)
        v.background = T.oval(bgColor)
        val pad = dp(sizeDp / 4 + 1)
        v.setPadding(pad, pad, pad, pad)
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        v.setOnClickListener { onClick() }
        return v
    }

    private fun pill(label: String, fg: Int, bgc: Int): TextView {
        val t = text(label, 11f, true, fg)
        t.setPadding(dp(8), dp(2), dp(8), dp(2))
        t.background = T.round(this, bgc, 20)
        return t
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = text(label, 13f, true, if (selected) Color.WHITE else T.accent)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(7), dp(14), dp(7))
        t.background = if (selected) T.gradient(this, T.grads[0], 20) else T.round(this, T.accentSoft, 20)
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(WC, WC)
        lp.setMargins(0, 0, dp(8), 0)
        t.layoutParams = lp
        return t
    }

    private fun section(title: String): TextView {
        val t = text(title, 15f, true)
        t.layoutParams = mp(2, 6, 0, 8)
        return t
    }

    /** Coloured stat tile (CRM dashboard look). */
    private fun tile(label: String, value: String, res: Int, g: IntArray): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = T.gradient(this, g, 16)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(icon(res, 18, Color.WHITE))
        val l = text(label, 12f, true, Color.parseColor("#E6FFFFFF"))
        l.setPadding(dp(6), 0, 0, 0)
        top.addView(l)
        c.addView(top)
        val v = text(value, 24f, true, Color.WHITE)
        v.setPadding(0, dp(4), 0, 0)
        c.addView(v)
        return c
    }

    private fun tileRow(a: LinearLayout, b: LinearLayout): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val l1 = LinearLayout.LayoutParams(0, WC, 1f)
        l1.setMargins(0, 0, dp(5), 0)
        val l2 = LinearLayout.LayoutParams(0, WC, 1f)
        l2.setMargins(dp(5), 0, 0, 0)
        r.addView(a, l1)
        r.addView(b, l2)
        r.layoutParams = mp(0, 0, 0, 10)
        return r
    }

    private fun bar(frac: Float, color: Int): LinearLayout {
        val track = LinearLayout(this)
        track.orientation = LinearLayout.HORIZONTAL
        track.weightSum = 1000f
        track.background = T.round(this, T.line, 5)
        val fill = View(this)
        fill.background = T.round(this, color, 5)
        val w = (frac.coerceIn(0f, 1f) * 1000f).coerceAtLeast(8f)
        track.addView(fill, LinearLayout.LayoutParams(0, dp(9), w))
        track.layoutParams = mp(0, 4, 0, 8)
        track.minimumHeight = dp(9)
        return track
    }

    private fun bg(run: () -> Unit) { Thread { try { run() } catch (e: Exception) { /* handled inside */ } }.start() }

    /** v1.5 — keeps the call-tag list (set by the manager in the CRM) up to date on the phone. */
    private fun refreshTags() {
        try {
            val r = Api.post(store.base, store.token, JSONObject().put("op", "me"))
            val t = r.optJSONArray("tags")
            if (t != null && t.length() > 0) store.tagsJson = t.toString()
        } catch (e: Exception) { /* keep the cached list */ }
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun askPerms() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.CALL_PHONE); list.add(Manifest.permission.READ_CALL_LOG); list.add(Manifest.permission.READ_CONTACTS); list.add(Manifest.permission.RECORD_AUDIO); list.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = list.filter { !hasPerm(it) }
        if (need.isNotEmpty() && Build.VERSION.SDK_INT >= 23) requestPermissions(need.toTypedArray(), 11)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (store.isPaired()) {
            showHome()
            bg { AlertSync.run(applicationContext) }
        }
    }

    // ------------------------------------------------------------------ pairing
    private fun showPair() {
        navBox.visibility = View.GONE
        headerBox.removeAllViews()
        headerBox.background = T.headerBg(this)
        headerBox.setPadding(dp(22), dp(34), dp(22), dp(30))
        val logo = FrameLayout(this)
        logo.background = T.round(this, Color.parseColor("#33FFFFFF"), 16)
        logo.addView(icon(R.drawable.ic_call, 28, Color.WHITE), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        headerBox.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        val t1 = text("NH Calling", 28f, true, Color.WHITE)
        t1.setPadding(0, dp(14), 0, 0)
        headerBox.addView(t1)
        headerBox.addView(text("Nutraherbal CRM ka apna dialer", 14f, false, Color.parseColor("#D9FFFFFF")))
        root.removeAllViews()
        val c = card()
        c.addView(text("Phone ko CRM se jodein", 17f, true))
        c.addView(text("1. CRM me Calls → My phone → Connect my phone dabayein.\n2. Neeche server address aur 6-digit code daalein.", 13f, false, grey))
        val server = input("Server address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        server.setText(store.base)
        val code = input("6-digit code", InputType.TYPE_CLASS_NUMBER)
        code.textSize = 24f
        c.addView(server)
        c.addView(code)
        val msg = text("", 13f, false, T.coral)
        c.addView(msg)
        c.addView(button("Connect") {
            val base = server.text.toString().trim()
            val cd = code.text.toString().trim()
            if (!base.startsWith("https://")) { msg.text = "Server address must start with https://"; return@button }
            if (cd.length != 6) { msg.text = "Enter the 6-digit code."; return@button }
            msg.setTextColor(grey); msg.text = "Connecting…"
            bg {
                try {
                    val r = Api.post(base, "", JSONObject().put("op", "pair").put("code", cd).put("deviceName", Build.MANUFACTURER + " " + Build.MODEL).put("appVersion", "1.5"))
                    store.base = base
                    store.token = r.getString("token")
                    store.userName = r.getJSONObject("user").optString("name")
                    ui.post { tab = 0; showHome() }
                } catch (e: ApiException) {
                    ui.post { msg.setTextColor(T.coral); msg.text = e.message ?: "" }
                }
            }
        })
        root.addView(c)
    }

    private fun disconnected(msg: String) {
        try { Sched.cancelAll(this, store) } catch (e: Exception) { /* ignore */ }
        store.clear()
        toast(msg)
        showPair()
    }

    // ------------------------------------------------------------------ chrome: header + bottom navigation
    private var tab = 0
    private var recFilter = 0
    private var repRange = "week"
    private var contactQuery = ""
    private var contactLimit = 60
    private var contacts: List<ContactRow>? = null
    private val contactNames = HashMap<String, String>()
    private val crmNames = HashMap<String, String>()
    private var permsAsked = false

    private val tabTitles = listOf("Home", "Recents", "Contacts", "Keypad", "Reports", "More")
    private val tabIcons = listOf(R.drawable.ic_home, R.drawable.ic_history, R.drawable.ic_people, R.drawable.ic_dialpad, R.drawable.ic_chart)
    private val tabLabels = listOf("Home", "Recents", "Contacts", "Keypad", "Reports")

    private fun alertBadgeCount(): Int {
        val now = System.currentTimeMillis()
        var n = 0
        var pay = false
        for (a in AlertSync.parse(store.alertsJson)) {
            if (a.kind == "payment") pay = true
            else if (a.kind != "info" && a.at <= now + 2 * 3600000L) n++
        }
        return n + (if (pay) 1 else 0)
    }

    private fun buildHeader() {
        headerBox.removeAllViews()
        headerBox.background = T.headerBg(this)
        headerBox.setPadding(dp(18), dp(16), dp(18), dp(if (tab == 0) 22 else 16))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (tab == 0) {
            val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            col.addView(text(if (hr < 12) "Good morning" else if (hr < 17) "Good afternoon" else "Good evening", 13f, false, Color.parseColor("#D9FFFFFF")))
            col.addView(text(store.userName.ifEmpty { "NH Calling" }, 22f, true, Color.WHITE))
            col.addView(text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), 12f, false, Color.parseColor("#BFFFFFFF")))
        } else {
            col.addView(text(tabTitles[tab], 22f, true, Color.WHITE))
            col.addView(text(store.userName, 12f, false, Color.parseColor("#BFFFFFFF")))
        }
        row.addView(col, hp(1f))
        val bellWrap = FrameLayout(this)
        bellWrap.background = T.oval(Color.parseColor("#33FFFFFF"))
        bellWrap.addView(icon(R.drawable.ic_bell, 20, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        val cnt = alertBadgeCount()
        if (cnt > 0) {
            val b = text(if (cnt > 9) "9+" else cnt.toString(), 9f, true, Color.WHITE)
            b.gravity = Gravity.CENTER
            b.background = T.oval(T.coral)
            bellWrap.addView(b, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END))
        }
        bellWrap.setOnClickListener { alertsDialog() }
        val bl = LinearLayout.LayoutParams(dp(42), dp(42))
        bl.setMargins(0, 0, dp(10), 0)
        row.addView(bellWrap, bl)
        val av = avatar(store.userName.ifEmpty { "NH" }, 42)
        av.setOnClickListener { tab = 5; showHome() }
        row.addView(av)
        headerBox.addView(row)
    }

    private fun buildNav() {
        navBox.visibility = View.VISIBLE
        navBox.removeAllViews()
        for (i in tabLabels.indices) {
            val sel = i == tab
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(0, dp(8), 0, dp(8))
            val pillBox = FrameLayout(this)
            if (sel) pillBox.background = T.round(this, T.accentSoft, 14)
            pillBox.addView(icon(tabIcons[i], 22, if (sel) T.accent else T.muted), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            item.addView(pillBox, LinearLayout.LayoutParams(dp(54), dp(30)))
            val l = text(tabLabels[i], 11f, sel, if (sel) T.accent else T.muted)
            l.gravity = Gravity.CENTER
            item.addView(l)
            item.setOnClickListener { tab = i; showHome() }
            navBox.addView(item, LinearLayout.LayoutParams(0, WC, 1f))
        }
    }

    private fun showHome() {
        root.removeAllViews()
        if (!permsAsked) { permsAsked = true; askPerms() }
        buildHeader()
        buildNav()
        scroll.scrollTo(0, 0)
        when (tab) {
            1 -> tabRecents()
            2 -> tabContacts()
            3 -> tabKeypad()
            4 -> tabReports()
            5 -> tabMore()
            else -> tabHome()
        }
    }

    private fun fail(e: ApiException, into: TextView?) {
        if (e.code == "unauthenticated") disconnected("This phone was disconnected in the CRM. Pair it again.") else if (into != null) into.text = e.message ?: ""
    }

    private fun fmtTime(ms: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))
    private fun fmtDur(sec: Int): String = if (sec <= 0) "0 s" else (sec / 60).toString() + ":" + (sec % 60).toString().padStart(2, '0')

    // ---- one row of a list: leading icon/avatar, name, small line, CRM badge, round Call button ----
    // kind: 0 outgoing · 1 incoming · 2 missed · 3 contact (avatar)
    private fun callRow(kind: Int, title: String, sub: String, subColor: Int, number: String, name: String, badges: MutableList<Pair<String, TextView>>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        if (kind == 3) {
            val av = avatar(title, 40)
            row.addView(av)
        } else {
            val fl = FrameLayout(this)
            fl.background = T.oval(if (kind == 2) T.coralSoft else T.greenSoft)
            val res = if (kind == 0) R.drawable.ic_made else if (kind == 1) R.drawable.ic_received else R.drawable.ic_missed
            fl.addView(icon(res, 18, if (kind == 2) T.coral else T.green), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            row.addView(fl, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(8), 0)
        col.addView(text(title, 15f, true))
        col.addView(text(sub, 12f, false, subColor))
        val badge = text("", 11f, true, green)
        badge.visibility = View.GONE
        col.addView(badge)
        badges.add(Pair(number, badge))
        row.addView(col, hp(1f))
        row.addView(roundBtn(R.drawable.ic_call, T.green, 40) { callFlow(number, name) })
        row.setOnClickListener { clientCard(number, name) }
        return row
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(T.line)
        v.layoutParams = LinearLayout.LayoutParams(MM, dp(1))
        return v
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
                val d = CallLogReader.digits(p.first)
                val nm = crmNames[d]
                if (nm != null) {
                    if (nm.isNotEmpty()) {
                        p.second.text = "CRM client · " + nm
                        p.second.setTextColor(green)
                        p.second.visibility = View.VISIBLE
                    } else if (!store.isPersonal(d)) {
                        p.second.text = "Not in CRM — tap to add as lead"
                        p.second.setTextColor(T.amber)
                        p.second.visibility = View.VISIBLE
                        p.second.setOnClickListener { leadDialog(p.first, contactNames[d] ?: "", null, false) }
                    }
                }
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

    // ---- Recents: incoming, outgoing and missed calls of the phone ----
    private fun tabRecents() {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) {
            permCard("Allow “Call logs” so your recent incoming, outgoing and missed calls appear here — tap one to call back.", "Allow call logs", Manifest.permission.READ_CALL_LOG, 13)
            return
        }
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.layoutParams = mp(0, 0, 0, 10)
        val labels = listOf("All", "Incoming", "Outgoing", "Missed")
        for (i in labels.indices) chips.addView(chip(labels[i], i == recFilter) { recFilter = i; showHome() })
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
            var first = true
            for (r in rows) {
                val nm = if (r.name.isNotEmpty()) r.name else (contactNames[CallLogReader.digits(r.number)] ?: "")
                val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
                val kind = if (missed) 2 else if (r.type == CallLog.Calls.INCOMING_TYPE) 1 else 0
                val label = when (r.type) { CallLog.Calls.INCOMING_TYPE -> "Incoming"; CallLog.Calls.OUTGOING_TYPE -> "Outgoing"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; else -> "Missed" }
                val sub = label + " · " + fmt.format(Date(r.date)) + (if (!missed && r.durationSec > 0) " · " + fmtDur(r.durationSec) else "") + (if (nm.isNotEmpty()) " · " + r.number else "")
                if (!first) box.addView(divider())
                first = false
                box.addView(callRow(kind, if (nm.isNotEmpty()) nm else r.number, sub, if (missed) T.coral else grey, r.number, nm, badges))
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
        search.background = T.round(this, T.surface, 14, T.line)
        search.setPadding(dp(14), dp(10), dp(14), dp(10))
        search.layoutParams = mp(0, 0, 0, 10)
        root.addView(search)
        val box = card()
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
            var first = true
            for (c in hits.take(contactLimit)) {
                if (!first) box.addView(divider())
                first = false
                box.addView(callRow(3, c.name, c.number, grey, c.number, c.name, badges))
            }
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

    // ---- Keypad: a real dial pad ----
    private var dialed = ""

    private fun tabKeypad() {
        val c = card()
        val display = text(if (dialed.isEmpty()) "Type a number" else dialed, 30f, true, if (dialed.isEmpty()) T.line else ink)
        display.gravity = Gravity.CENTER
        display.setPadding(0, dp(8), 0, dp(4))
        c.addView(display)
        val hint = text("", 12f, false, grey)
        hint.gravity = Gravity.CENTER
        c.addView(hint)
        fun refresh() {
            display.text = if (dialed.isEmpty()) "Type a number" else dialed
            display.setTextColor(if (dialed.isEmpty()) T.line else ink)
            val d = CallLogReader.digits(dialed)
            val nm = contactNames[d]
            hint.text = if (nm != null && d.length >= 10) nm else ""
        }
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
        for (kr in keys) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.layoutParams = mp(0, 4, 0, 4)
            for (k in kr) {
                val b = text(k, 24f, true, ink)
                b.gravity = Gravity.CENTER
                b.background = T.round(this, T.bg, 16)
                b.setOnClickListener { if (dialed.length < 18) { dialed += k; refresh() } }
                val lp = LinearLayout.LayoutParams(0, dp(58), 1f)
                lp.setMargins(dp(5), 0, dp(5), 0)
                r.addView(b, lp)
            }
            c.addView(r)
        }
        val last = LinearLayout(this)
        last.orientation = LinearLayout.HORIZONTAL
        last.gravity = Gravity.CENTER_VERTICAL
        last.layoutParams = mp(0, 8, 0, 2)
        val plus = text("+", 24f, true, T.accent)
        plus.gravity = Gravity.CENTER
        plus.setOnClickListener { if (dialed.isEmpty()) { dialed = "+"; refresh() } }
        last.addView(plus, LinearLayout.LayoutParams(0, dp(58), 1f))
        val callB = FrameLayout(this)
        callB.background = T.gradient(this, T.grads[3], 30)
        callB.addView(icon(R.drawable.ic_call, 28, Color.WHITE), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        callB.setOnClickListener {
            if (dialed.filter { it.isDigit() }.length < 10) { toast("Enter a 10-digit mobile number."); return@setOnClickListener }
            callFlow(dialed, contactNames[CallLogReader.digits(dialed)] ?: "")
        }
        val cl = LinearLayout.LayoutParams(0, dp(58), 2f)
        cl.setMargins(dp(5), 0, dp(5), 0)
        last.addView(callB, cl)
        val back = FrameLayout(this)
        back.addView(icon(R.drawable.ic_backspace, 24, T.muted), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        back.setOnClickListener { if (dialed.isNotEmpty()) { dialed = dialed.dropLast(1); refresh() } }
        back.setOnLongClickListener { dialed = ""; refresh(); true }
        last.addView(back, LinearLayout.LayoutParams(0, dp(58), 1f))
        c.addView(last)
        root.addView(c)
        root.addView(text("Tip: Recents ya Contacts se seedha call karein — number type karne ki zaroorat nahi.", 12f, false, grey))
        if (contacts == null) loadContacts { refresh() }
        refresh()
    }

    /** One tap = call. Clients of the CRM are saved with outcome / note / voice note afterwards; other numbers just call. */
    private fun callFlow(number: String, name: String, taskId: String = "") {
        val d = CallLogReader.digits(number)
        val cached = crmNames[d]
        if (cached != null) { afterLookup(number, name, cached, taskId); return }
        toast("Checking client…")
        bg {
            try {
                val r = Api.post(store.base, store.token, JSONObject().put("op", "lookup").put("mobile", number))
                val nm = if (r.optBoolean("found")) r.optString("name").ifEmpty { "client" } else ""
                ui.post { crmNames[d] = nm; afterLookup(number, name, nm, taskId) }
            } catch (e: ApiException) {
                ui.post { if (e.code == "unauthenticated") disconnected("This phone was disconnected in the CRM. Pair it again.") else toast(e.message ?: "Could not check the number.") }
            }
        }
    }

    private fun afterLookup(number: String, name: String, crmName: String, taskId: String) {
        if (crmName.isNotEmpty()) { startCall(number, crmName, taskId); return }
        AlertDialog.Builder(this)
            .setTitle(if (name.isNotEmpty()) name else number)
            .setMessage("This number is not a client in your CRM, so this call will not be saved there.")
            .setPositiveButton("Call anyway") { _, _ -> plainCall(number, name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A call to a number that is not in the CRM. When it ends the app offers to add the number as a lead. */
    private fun plainCall(number: String, name: String) {
        val uri = Uri.parse("tel:" + T.dialable(number))
        val ask = !store.isPersonal(CallLogReader.digits(number))
        if (ask) {
            pendingNumber = number
            pendingName = name
            pendingTaskId = ""
            pendingSince = System.currentTimeMillis() - 2000
            pendingLead = true
        }
        try {
            if (hasPerm(Manifest.permission.CALL_PHONE)) startActivity(Intent(Intent.ACTION_CALL, uri)) else { pendingNumber = null; pendingLead = false; askPerms(); toast("Allow the Phone permission, then tap Call again.") }
        } catch (e: Exception) {
            pendingNumber = null
            pendingLead = false
            toast("Could not start the call: " + (e.message ?: ""))
        }
    }

    private val leadCategories = listOf("Brand owner", "Doctor / clinic", "Distributor", "Super Stockist", "Retailer", "New brand / startup", "Export importer", "Other")

    /** Basic questions for a new lead: name, category, brand, what they want. */
    private fun leadDialog(number: String, prefName: String, row: LogRow?, logCall: Boolean) {
        dialogOpen = true
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(10), dp(18), dp(4))
        box.addView(text(number, 15f, true))
        box.addView(text("This number is not in your CRM yet. Add it as a lead?", 13f, false, grey))
        val nameIn = input("Name")
        nameIn.setText(prefName)
        box.addView(nameIn)
        box.addView(text("Who is this?", 12f, false, grey))
        val cat = Spinner(this)
        cat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, leadCategories)
        box.addView(cat)
        val brand = input("Brand / company (optional)")
        box.addView(brand)
        val city = input("City (optional)")
        box.addView(city)
        val note = input("What are they looking for? (optional)")
        box.addView(note)
        val sv = ScrollView(this)
        sv.addView(box)
        val d = AlertDialog.Builder(this).setTitle("Add to CRM?").setView(sv).setCancelable(false)
            .setPositiveButton("Add to CRM", null).setNegativeButton("Not now", null).setNeutralButton("Personal number", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nm = nameIn.text.toString().trim()
                if (nm.isEmpty()) { toast("Enter the name."); return@setOnClickListener }
                toast("Adding…")
                bg {
                    try {
                        val r = Api.post(store.base, store.token, JSONObject().put("op", "leadAdd").put("mobile", number).put("name", nm)
                            .put("category", leadCategories[cat.selectedItemPosition]).put("brandName", brand.text.toString().trim())
                            .put("city", city.text.toString().trim()).put("note", note.text.toString().trim()))
                        ui.post {
                            crmNames[CallLogReader.digits(number)] = nm
                            dialogOpen = false
                            d.dismiss()
                            if (r.optString("result") == "repeat" && !r.optBoolean("mine")) {
                                toast("Already a client of " + r.optString("ownerName") + ". This call will not be saved.")
                                showHome()
                            } else {
                                toast("Added to CRM ✔")
                                showHome()
                                if (logCall) summaryDialog(number, nm, "", row)
                            }
                        }
                    } catch (e: ApiException) {
                        ui.post { if (e.code == "unauthenticated") { dialogOpen = false; d.dismiss(); disconnected("This phone was disconnected in the CRM. Pair it again.") } else toast(e.message ?: "Could not add.") }
                    }
                }
            }
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialogOpen = false; d.dismiss(); showHome() }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                store.addPersonal(CallLogReader.digits(number))
                dialogOpen = false
                d.dismiss()
                showHome()
            }
        }
        d.show()
    }

    // ---- Home: progress, next up, today's calls, pending payments, unsaved calls ----
    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.layoutParams = mp()
        return l
    }

    private fun whenText(ms: Long): String {
        val diff = ms - System.currentTimeMillis()
        if (diff < 0) return "Overdue"
        if (diff < 3600000L) return "in " + (diff / 60000L + 1).toString() + " min"
        val c1 = Calendar.getInstance(); val c2 = Calendar.getInstance(); c2.timeInMillis = ms
        if (c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR) && c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)) return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
        return fmtTime(ms)
    }

    private fun notifBanner() {
        if (Notifier.allowed(this)) return
        val c = card()
        c.background = T.round(this, T.amberSoft, 16)
        c.addView(text("Popup notifications band hain", 15f, true, ink))
        c.addView(text("Call reminders, meeting aur payment alerts screen par dikhne ke liye notifications allow karein.", 12f, false, ink))
        c.addView(button("Allow notifications") { enableNotifications() })
        root.addView(c)
    }

    private fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 14); return }
        try {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(i)
        } catch (e: Exception) { toast("Open Settings → Apps → NH Calling → Notifications.") }
    }

    private fun tabHome() {
        notifBanner()
        val heroBox = vbox()
        val loading = card()
        loading.addView(text("Loading your day…", 13f, false, grey))
        heroBox.addView(loading)
        root.addView(heroBox)
        root.addView(section("Aaj ke calls & follow-ups"))
        val queueBox = card()
        val qInfo = text("Loading…", 13f, false, grey)
        queueBox.addView(qInfo)
        root.addView(queueBox)
        val payHead = section("Pending payments")
        payHead.visibility = View.GONE
        root.addView(payHead)
        val payBox = card()
        payBox.visibility = View.GONE
        root.addView(payBox)
        val unHead = section("Calls not yet saved in the CRM")
        root.addView(unHead)
        val unBox = card()
        val uInfo = text("Checking your call history…", 13f, false, grey)
        unBox.addView(uInfo)
        root.addView(unBox)

        bg {
            val left = try { Uploader.flush(store) } catch (e: Exception) { 0 }
            try {
                val q = Api.post(store.base, store.token, JSONObject().put("op", "queue"))
                ui.post { fillQueue(queueBox, q.optJSONArray("items") ?: JSONArray(), left) }
            } catch (e: ApiException) { ui.post { fail(e, qInfo) }; return@bg }
            try {
                val h = Api.post(store.base, store.token, JSONObject().put("op", "home"))
                ui.post { fillHero(heroBox, h) }
            } catch (e: ApiException) { ui.post { fail(e, null) } }
            try { AlertSync.run(applicationContext) } catch (e: Exception) { /* alerts are optional */ }
            ui.post {
                fillPayments(payHead, payBox)
                if (tab == 0) buildHeader()
                if (tab == 0 && !dialogOpen && store.lastPlanDay != AlertSync.today()) { store.lastPlanDay = AlertSync.today(); planDialog() }
            }
            loadUnlogged(unBox, uInfo, unHead)
        }
    }

    private fun fillHero(box: LinearLayout, h: JSONObject) {
        box.removeAllViews()
        val t = h.optJSONObject("today") ?: JSONObject()
        val tg = h.optJSONObject("target") ?: JSONObject()
        val c = card()
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(text("Aaj ka progress", 16f, true), hp(1f))
        val over = h.optInt("overdue")
        if (over > 0) top.addView(pill("$over overdue", T.coral, T.coralSoft))
        c.addView(top)
        val calls = t.optInt("total")
        val cg = tg.optInt("callsPerDay", 40).coerceAtLeast(1)
        val l1 = text("Calls   $calls / $cg", 13f, true, grey)
        l1.setPadding(0, dp(8), 0, 0)
        c.addView(l1)
        c.addView(bar(calls.toFloat() / cg, T.accent))
        val talkMin = t.optInt("talkSec") / 60
        val tgt = tg.optInt("talkMinPerDay", 60).coerceAtLeast(1)
        c.addView(text("Talk time   $talkMin / $tgt min", 13f, true, grey))
        c.addView(bar(talkMin.toFloat() / tgt, T.green))
        box.addView(c)

        box.addView(tileRow(tile("Calls", calls.toString(), R.drawable.ic_call, T.grads[0]), tile("Connected", t.optInt("connected").toString(), R.drawable.ic_check, T.grads[3])))
        box.addView(tileRow(tile("Talk time", T.dur(t.optInt("talkSec")), R.drawable.ic_clock, T.grads[4]), tile("Missed", t.optInt("missed").toString(), R.drawable.ic_missed, T.grads[1])))
        val pp = h.optJSONObject("pendingPayments")
        val score = t.optDouble("avgScore", 0.0)
        box.addView(tileRow(tile("AI rating", if (score > 0) String.format("%.1f", score) + "/10" else "—", R.drawable.ic_chart, T.grads[2]),
            tile("Payment baaki", if (pp != null && pp.optInt("count") > 0) T.rupees(pp.optDouble("amount")) else "—", R.drawable.ic_info, T.grads[1])))

        val nx = h.optJSONObject("next")
        if (nx != null) {
            val nc = card()
            nc.addView(text("Next up", 12f, true, T.accent))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, 0)
            val nm = nx.optString("name").ifEmpty { nx.optString("mobile") }
            row.addView(avatar(nm, 44))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(12), 0, dp(8), 0)
            col.addView(text(nm, 16f, true))
            col.addView(text(nx.optString("title") + " · " + whenText(nx.optLong("dueAt")), 12f, false, grey))
            row.addView(col, hp(1f))
            row.addView(roundBtn(R.drawable.ic_call, T.green, 44) { callFlow(nx.optString("mobile"), nm, nx.optString("taskId")) })
            nc.addView(row)
            nc.setOnClickListener { clientCard(nx.optString("mobile"), nm) }
            box.addView(nc)
        }
    }

    private fun fillQueue(box: LinearLayout, items: JSONArray, left: Int) {
        box.removeAllViews()
        if (items.length() == 0) box.addView(text("Aaj koi call due nahi 🎉", 14f, false, grey))
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val nm = item.optString("name").ifEmpty { item.optString("mobile") }
            val overdue = item.optBoolean("overdue")
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            row.addView(avatar(nm, 40))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(12), 0, dp(8), 0)
            col.addView(text(nm, 15f, true))
            col.addView(text(item.optString("title") + " · " + fmtTime(item.optLong("dueAt")), 12f, false, if (overdue) T.coral else grey))
            if (item.optString("notes").isNotEmpty()) col.addView(text(item.optString("notes"), 12f, false, grey))
            if (overdue) {
                val p = pill("OVERDUE", T.coral, T.coralSoft)
                p.layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.topMargin = dp(3) }
                col.addView(p)
            } else if (item.optString("type") == "meeting") {
                val p = pill("MEETING", T.cyan, T.cyanSoft)
                p.layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.topMargin = dp(3) }
                col.addView(p)
            }
            row.addView(col, hp(1f))
            row.addView(roundBtn(R.drawable.ic_call, T.green, 40) { startCall(item.optString("mobile"), item.optString("name"), item.optString("taskId")) })
            row.setOnClickListener { clientCard(item.optString("mobile"), item.optString("name")) }
            if (i > 0) box.addView(divider())
            box.addView(row)
        }
        if (left > 0) box.addView(text("$left call(s) waiting to upload — will retry when you are online.", 12f, false, T.amber))
    }

    private fun fillPayments(head: TextView, box: LinearLayout) {
        val pays = AlertSync.parse(store.alertsJson).filter { it.kind == "payment" }
        if (pays.isEmpty()) return
        head.visibility = View.VISIBLE
        box.visibility = View.VISIBLE
        box.removeAllViews()
        var first = true
        for (p in pays.take(5)) {
            val nm = p.title.substringAfter("· ", p.title)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            val fl = FrameLayout(this)
            fl.background = T.oval(T.amberSoft)
            val rs = text("₹", 18f, true, T.amber)
            rs.gravity = Gravity.CENTER
            fl.addView(rs, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            row.addView(fl, LinearLayout.LayoutParams(dp(40), dp(40)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(12), 0, dp(8), 0)
            col.addView(text(nm, 15f, true))
            col.addView(text(p.body, 12f, false, T.amber))
            row.addView(col, hp(1f))
            row.addView(roundBtn(R.drawable.ic_call, T.green, 40) { callFlow(p.mobile, nm) })
            row.setOnClickListener { clientCard(p.mobile, nm) }
            if (!first) box.addView(divider())
            first = false
            box.addView(row)
        }
    }

    /** Calls made outside the app (last 48 h) to CRM clients that are not saved yet. */
    private fun loadUnlogged(box: LinearLayout, info: TextView, head: TextView) {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) { ui.post { info.text = "Allow “Call logs” permission to see calls made outside the app." }; return }
        val rows = CallLogReader.rows(this, System.currentTimeMillis() - 48L * 3600000, null, 60).filter { !store.isLogged(it.id) && it.number.isNotEmpty() }
        val need = LinkedHashMap<String, String>()
        for (r in rows) { val d = CallLogReader.digits(r.number); if (d.length >= 10 && need.size < 30) need[d] = r.number }
        val names = HashMap<String, String>()
        if (need.isNotEmpty()) {
            try {
                val arr = JSONArray()
                for (v in need.values) arr.put(v)
                val j = Api.post(store.base, store.token, JSONObject().put("op", "lookupMany").put("mobiles", arr))
                val f = j.optJSONArray("found") ?: JSONArray()
                for (i in 0 until f.length()) { val o = f.getJSONObject(i); names[CallLogReader.digits(o.optString("mobile"))] = o.optString("name").ifEmpty { o.optString("mobile") } }
            } catch (e: ApiException) {
                if (e.code == "unauthenticated") { ui.post { disconnected("This phone was disconnected in the CRM. Pair it again.") }; return }
            }
        }
        val found = rows.filter { names.containsKey(CallLogReader.digits(it.number)) }
        ui.post {
            if (found.isEmpty()) { info.text = "Sab saved hai ✔"; return@post }
            box.removeView(info)
            var first = true
            for (r in found.take(12)) {
                val nm = names[CallLogReader.digits(r.number)] ?: r.number
                val kind = if (r.type == CallLog.Calls.OUTGOING_TYPE) 0 else if (r.type == CallLog.Calls.INCOMING_TYPE) 1 else 2
                val label = when (kind) { 0 -> "Outgoing"; 1 -> "Incoming"; else -> "Missed" }
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(7), 0, dp(7))
                val fl = FrameLayout(this)
                fl.background = T.oval(if (kind == 2) T.coralSoft else T.greenSoft)
                fl.addView(icon(if (kind == 0) R.drawable.ic_made else if (kind == 1) R.drawable.ic_received else R.drawable.ic_missed, 18, if (kind == 2) T.coral else T.green), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                row.addView(fl, LinearLayout.LayoutParams(dp(40), dp(40)))
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.setPadding(dp(12), 0, dp(8), 0)
                col.addView(text(nm, 15f, true))
                col.addView(text(label + " · " + fmtTime(r.date) + " · " + r.durationSec + " s", 12f, false, grey))
                row.addView(col, hp(1f))
                val sv = text("Save", 13f, true, Color.WHITE)
                sv.gravity = Gravity.CENTER
                sv.setPadding(dp(16), dp(8), dp(16), dp(8))
                sv.background = T.gradient(this, T.grads[0], 20)
                sv.setOnClickListener { summaryDialog(r.number, nm, "", r) }
                row.addView(sv)
                if (!first) box.addView(divider())
                first = false
                box.addView(row)
            }
        }
    }

    // ---- Reports: day / week / month ----
    private fun tabReports() {
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.layoutParams = mp(0, 0, 0, 10)
        val keys = listOf("today", "yesterday", "week", "month")
        val labels = listOf("Today", "Yesterday", "This week", "This month")
        for (i in keys.indices) chips.addView(chip(labels[i], repRange == keys[i]) { repRange = keys[i]; showHome() })
        val hs = android.widget.HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        root.addView(hs)
        val body = vbox()
        val info = text("Loading report…", 13f, false, grey)
        body.addView(info)
        root.addView(body)
        val asked = repRange
        bg {
            try {
                val r = Api.post(store.base, store.token, JSONObject().put("op", "reports").put("range", asked))
                ui.post { if (tab == 4 && repRange == asked) fillReport(body, r) }
            } catch (e: ApiException) { ui.post { fail(e, info) } }
        }
    }

    private fun fillReport(body: LinearLayout, r: JSONObject) {
        body.removeAllViews()
        val s = r.optJSONObject("stats") ?: JSONObject()
        val total = s.optInt("total")
        if (total == 0) { val c = card(); c.addView(text("Is period me koi call nahi hui.", 14f, false, grey)); body.addView(c); return }
        val score = s.optDouble("avgScore", 0.0)
        body.addView(tileRow(tile("Calls", total.toString(), R.drawable.ic_call, T.grads[0]), tile("Connected", s.optInt("connected").toString() + " (" + (s.optInt("connected") * 100 / total) + "%)", R.drawable.ic_check, T.grads[3])))
        body.addView(tileRow(tile("Talk time", T.dur(s.optInt("talkSec")), R.drawable.ic_clock, T.grads[4]), tile("AI rating", if (score > 0) String.format("%.1f", score) + "/10" else "—", R.drawable.ic_chart, T.grads[2])))

        val split = card()
        split.addView(text("Calls ka split", 15f, true))
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.setPadding(0, dp(8), 0, 0)
        fun cell(label: String, v: Int, color: Int) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(text(v.toString(), 24f, true, color))
            col.addView(text(label, 12f, false, grey))
            sr.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
        }
        cell("Outgoing", s.optInt("out"), T.accent)
        cell("Incoming", s.optInt("in"), T.green)
        cell("Missed", s.optInt("missed"), T.coral)
        cell("Clients", s.optInt("unique"), T.cyan)
        split.addView(sr)
        body.addView(split)

        val oc = s.optJSONObject("outcomes")
        if (oc != null && oc.length() > 0) {
            val c = card()
            c.addView(text("Call ka result", 15f, true))
            val keys = ArrayList<String>()
            val ks = oc.keys()
            while (ks.hasNext()) keys.add(ks.next())
            keys.sortByDescending { k2 -> oc.optInt(k2) }
            for (k in keys) {
                val n = oc.optInt(k)
                val l = text("$k   $n", 13f, false, ink)
                l.setPadding(0, dp(8), 0, 0)
                c.addView(l)
                c.addView(bar(n.toFloat() / total, if (k == "Connected") T.green else if (k == "No answer" || k == "Busy") T.amber else T.accent))
            }
            body.addView(c)
        }

        val days = r.optJSONArray("days")
        if (days != null && days.length() > 1) {
            val c = card()
            c.addView(text("Din-ba-din calls", 15f, true))
            var mx = 1
            for (i in 0 until days.length()) mx = Math.max(mx, days.getJSONObject(i).optInt("calls"))
            val chart = LinearLayout(this)
            chart.orientation = LinearLayout.HORIZONTAL
            chart.gravity = Gravity.BOTTOM
            chart.setPadding(0, dp(10), 0, 0)
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                val n = d.optInt("calls")
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                val cn = text(n.toString(), 9f, true, grey)
                cn.gravity = Gravity.CENTER
                col.addView(cn)
                val b = View(this)
                b.background = T.gradient(this, T.grads[0], 4)
                col.addView(b, LinearLayout.LayoutParams(dp(if (days.length() > 16) 8 else 16), Math.max(dp(4), n * dp(90) / mx)))
                val dl = text(d.optString("d").takeLast(2), 9f, false, grey)
                dl.gravity = Gravity.CENTER
                col.addView(dl)
                chart.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
            }
            c.addView(chart)
            body.addView(c)
        }

        val tagStats = r.optJSONArray("tags")
        if (tagStats != null && tagStats.length() > 0) {
            val tc = card()
            tc.addView(text("Call tags", 15f, true))
            for (i in 0 until tagStats.length()) {
                val o = tagStats.getJSONObject(i)
                val tr = text("# " + o.optString("tag") + "  —  " + o.optInt("n"), 13f, false, ink)
                tr.setPadding(0, dp(6), 0, 0)
                tc.addView(tr)
            }
            body.addView(tc)
        }

        val top = r.optJSONArray("topClients")
        if (top != null && top.length() > 0) {
            val c = card()
            c.addView(text("Sabse zyada baat (talk time)", 15f, true))
            for (i in 0 until top.length()) {
                val o = top.getJSONObject(i)
                val nm = o.optString("name").ifEmpty { o.optString("mobile") }
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(8), 0, 0)
                row.addView(avatar(nm, 34))
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.setPadding(dp(10), 0, 0, 0)
                col.addView(text(nm, 14f, true))
                col.addView(text(o.optInt("calls").toString() + " calls · " + T.dur(o.optInt("sec")), 12f, false, grey))
                row.addView(col, hp(1f))
                row.setOnClickListener { clientCard(o.optString("mobile"), nm) }
                c.addView(row)
            }
            body.addView(c)
        }

        val lb = r.optJSONArray("leaderboard")
        if (r.optBoolean("isManager") && lb != null && lb.length() > 0) {
            val c = card()
            c.addView(text("Team leaderboard", 15f, true))
            for (i in 0 until lb.length()) {
                val o = lb.getJSONObject(i)
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(8), 0, 0)
                row.addView(text("#" + (i + 1), 14f, true, if (i == 0) T.amber else grey), LinearLayout.LayoutParams(dp(30), WC))
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.addView(text(o.optString("name") + if (o.optBoolean("me")) " (you)" else "", 14f, true))
                col.addView(text(o.optInt("calls").toString() + " calls · " + o.optInt("connected") + " connected · " + T.dur(o.optInt("talkSec")) + (if (o.optDouble("avgScore") > 0) " · AI " + o.optDouble("avgScore") else ""), 12f, false, grey))
                row.addView(col, hp(1f))
                c.addView(row)
            }
            body.addView(c)
        }
    }

    // ---- More: notifications, call recording, account ----
    private fun tabMore() {
        val prof = card()
        val pr = LinearLayout(this)
        pr.orientation = LinearLayout.HORIZONTAL
        pr.gravity = Gravity.CENTER_VERTICAL
        pr.addView(avatar(store.userName.ifEmpty { "NH" }, 52))
        val pc = LinearLayout(this)
        pc.orientation = LinearLayout.VERTICAL
        pc.setPadding(dp(12), 0, 0, 0)
        pc.addView(text(store.userName, 17f, true))
        pc.addView(text(store.base, 12f, false, grey))
        pc.addView(text("NH Calling 1.5.1", 12f, false, grey))
        pr.addView(pc, hp(1f))
        prof.addView(pr)
        root.addView(prof)

        // v1.4.1: WhatsApp call-log (notification access) is switched off — Google Play Protect blocks sideloaded apps that request it.
        val wc = card()
        wc.addView(text("WhatsApp calls", 16f, true))
        wc.addView(text("Is version me WhatsApp call ka automatic log band hai (Google Play Protect aise apps ko install hone se rokta hai). Phone-SIM calls ka poora log pehle jaise chalta hai.", 12f, false, grey))
        root.addView(wc)

        val ap = card()
        ap.addView(text("Appearance", 16f, true))
        val modeLabel = when (store.themeMode) { "dark" -> "Dark"; "light" -> "Light"; else -> "Automatic (follows the phone)" }
        ap.addView(text("Theme: " + modeLabel, 13f, true, T.accent))
        ap.addView(button("Change theme (Auto → Light → Dark)", false) {
            store.themeMode = when (store.themeMode) { "auto" -> "light"; "light" -> "dark"; else -> "auto" }
            store.openTabOnce = tab
            recreate()
        })
        root.addView(ap)

        val cid = card()
        cid.addView(text("Incoming call pop-up (caller ID)", 16f, true))
        cid.addView(text("Jab CRM ke kisi client ka call aata hai, popup me naam, stage, baaki rakam, pichhli call ka note/tags aur follow-up dikhta hai.", 12f, false, grey))
        val phoneOk = Build.VERSION.SDK_INT < 23 || (hasPerm(Manifest.permission.READ_PHONE_STATE) && hasPerm(Manifest.permission.READ_CALL_LOG))
        cid.addView(text(if (!phoneOk) "Permission needed" else if (store.callerIdOn) "ON ✔" else "OFF", 13f, true, if (phoneOk && store.callerIdOn) T.green else T.amber))
        if (!phoneOk) cid.addView(button("Allow phone permission") { if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG), 15) })
        cid.addView(button(if (store.callerIdOn) "Turn off caller-ID pop-up" else "Turn on caller-ID pop-up", false) { store.callerIdOn = !store.callerIdOn; showHome() })
        root.addView(cid)

        val nc = card()
        nc.addView(text("Popup notifications & reminders", 16f, true))
        val ok = Notifier.allowed(this)
        nc.addView(text(if (ok) "Notifications: ON ✔" else "Notifications: OFF — allow them to get popups", 13f, true, if (ok) T.green else T.coral))
        nc.addView(text("Follow-up calls, meetings, pending payments, subah 9 baje plan aur shaam 6:30 baje report — sab popup ke roop me aayenge, app band ho tab bhi.", 12f, false, grey))
        if (!ok) nc.addView(button("Allow notifications") { enableNotifications() })
        nc.addView(button(if (store.alertsOn) "Reminders: ON (tap to turn off)" else "Reminders: OFF (tap to turn on)", false) {
            store.alertsOn = !store.alertsOn
            if (store.alertsOn) { bg { AlertSync.run(applicationContext) } } else { Sched.cancelAll(this, store) }
            showHome()
        })
        nc.addView(button("Send a test popup", false) {
            Notifier.show(this, "test" + System.currentTimeMillis(), "call", "Call · Test client", "Aise popup aayenge — follow-up, meeting aur payment alerts.", "", "", "")
            if (!Notifier.allowed(this)) toast("Notifications are off — allow them first.")
        })
        if (!Sched.canExact(this)) {
            nc.addView(text("Exact timing is off: reminders can come a few minutes late.", 12f, false, T.amber))
            nc.addView(button("Allow exact alarms", false) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + packageName))) } catch (e: Exception) { toast("Open Settings → Apps → NH Calling → Alarms & reminders.") }
            })
        }
        nc.addView(button("Battery: set NH Calling to “Unrestricted”", false) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { toast("Open Settings → Battery → NH Calling → Unrestricted.") }
        })
        val sync = text(if (store.lastSync > 0) "Last sync: " + fmtTime(store.lastSync) else "Not synced yet", 12f, false, grey)
        sync.setPadding(0, dp(8), 0, 0)
        nc.addView(sync)
        nc.addView(button("Sync now", false) {
            toast("Syncing…")
            bg { val okk = AlertSync.run(applicationContext); ui.post { toast(if (okk) "Alerts updated ✔" else "Could not sync."); if (tab == 5) showHome() } }
        })
        root.addView(nc)

        val recBox = card()
        recBox.addView(text("Call recording → transcript, notes & rating", 16f, true))
        if (store.recTree.isEmpty()) {
            recBox.addView(text("1. In the phone's Phone app → Settings → Call recording: switch ON “Auto record” (all calls).\n2. Choose the folder where the phone saves those recordings (Samsung: Recordings/Call · Redmi: MIUI/sound_recorder/call_rec · Vivo: Record/Call · Oppo/Realme: Recordings/Call Recordings). After every CRM call the app finds the recording and sends it for AI notes and rating.", 12f, false, grey))
            recBox.addView(button("Choose recorder folder") { pickRecFolder() })
        } else {
            recBox.addView(text("Recorder folder is set ✔ — recordings of CRM-client calls are sent after you save the call.", 12f, false, grey))
            recBox.addView(button("Change folder", false) { pickRecFolder() })
            recBox.addView(button("Stop using recordings", false) { store.recTree = ""; showHome() })
        }
        root.addView(recBox)

        val dc = button("Disconnect this phone", false) {
            AlertDialog.Builder(this).setMessage("Disconnect this phone from the CRM?").setPositiveButton("Disconnect") { _, _ -> disconnected("Disconnected.") }.setNegativeButton("Cancel", null).show()
        }
        dc.setTextColor(T.coral)
        root.addView(dc)
    }

    // ---- bell: all alerts ----
    private fun alertsDialog() {
        val items = AlertSync.parse(store.alertsJson).sortedBy { it.at }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(10), dp(18), dp(4))
        if (items.isEmpty()) box.addView(text("Koi alert nahi hai. 🎉", 14f, false, grey))
        val d = AlertDialog.Builder(this).setTitle("Alerts & reminders").setView(ScrollView(this).also { it.addView(box) }).setPositiveButton("Close", null).create()
        for (a in items.take(40)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            val color = when (a.kind) { "payment" -> T.amber; "meeting" -> T.cyan; "info" -> T.accent; else -> T.green }
            val soft = when (a.kind) { "payment" -> T.amberSoft; "meeting" -> T.cyanSoft; "info" -> T.accentSoft; else -> T.greenSoft }
            val fl = FrameLayout(this)
            fl.background = T.oval(soft)
            if (a.kind == "payment") {
                val rs = text("₹", 16f, true, color); rs.gravity = Gravity.CENTER
                fl.addView(rs, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
            } else {
                val res = when (a.kind) { "meeting" -> R.drawable.ic_event; "info" -> R.drawable.ic_info; "task" -> R.drawable.ic_clock; else -> R.drawable.ic_call }
                fl.addView(icon(res, 18, color), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }
            row.addView(fl, LinearLayout.LayoutParams(dp(38), dp(38)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(10), 0, 0, 0)
            col.addView(text(a.title, 14f, true))
            col.addView(text(a.body, 12f, false, grey))
            row.addView(col, hp(1f))
            if (a.kind != "payment" && a.kind != "info") row.addView(text(whenText(a.at), 11f, true, if (a.at < System.currentTimeMillis()) T.coral else T.muted))
            if (a.mobile.isNotEmpty()) row.setOnClickListener { d.dismiss(); callFlow(a.mobile, a.name, a.taskId) }
            box.addView(row)
        }
        d.show()
    }

    // ---- daily plan popup (first open of the day) ----
    private fun planDialog() {
        val tx = AlertSync.planText(store, false)
        val p = try { JSONObject(store.planJson) } catch (e: Exception) { JSONObject() }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(4))
        fun line(res: Int, color: Int, s: String) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(7), 0, dp(7))
            r.addView(icon(res, 20, color))
            val t = text(s, 14f, false, ink)
            t.setPadding(dp(12), 0, 0, 0)
            r.addView(t)
            box.addView(r)
        }
        line(R.drawable.ic_call, T.accent, p.optInt("tasksToday").toString() + " calls / follow-ups aaj")
        if (p.optInt("overdue") > 0) line(R.drawable.ic_clock, T.coral, p.optInt("overdue").toString() + " overdue — pehle inhe karein")
        if (p.optInt("pendingCount") > 0) line(R.drawable.ic_info, T.amber, T.rupees(p.optDouble("pendingAmount")) + " payment baaki (" + p.optInt("pendingCount") + " orders)")
        if (p.optInt("tasksToday") == 0 && p.optInt("overdue") == 0 && p.optInt("pendingCount") == 0) box.addView(text("Aaj ka plan khaali hai. 🎉", 14f, false, grey))
        AlertDialog.Builder(this).setTitle(tx.first).setView(box).setPositiveButton("Chalo shuru karein", null).show()
    }

    // ---- client card: who is this, last calls with AI summary, pending money ----
    private fun clientCard(number: String, name: String) {
        if (CallLogReader.digits(number).length < 10) { callFlow(number, name); return }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(10), dp(18), dp(4))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(avatar(name.ifEmpty { number }, 48))
        val hc = LinearLayout(this)
        hc.orientation = LinearLayout.VERTICAL
        hc.setPadding(dp(12), 0, 0, 0)
        val nameTv = text(name.ifEmpty { number }, 17f, true)
        hc.addView(nameTv)
        hc.addView(text(number, 12f, false, grey))
        head.addView(hc)
        box.addView(head)
        val load = text("Loading CRM details…", 13f, false, grey)
        load.setPadding(0, dp(10), 0, 0)
        box.addView(load)
        val d = AlertDialog.Builder(this).setView(ScrollView(this).also { it.addView(box) })
            .setPositiveButton("Call now") { _, _ -> callFlow(number, name) }
            .setNeutralButton("Add as lead", null)
            .setNegativeButton("Close", null).create()
        d.show()
        val addBtn = d.getButton(AlertDialog.BUTTON_NEUTRAL)
        addBtn.visibility = View.GONE
        bg {
            try {
                val r = Api.post(store.base, store.token, JSONObject().put("op", "clientCard").put("mobile", number))
                ui.post {
                    box.removeView(load)
                    if (!r.optBoolean("found")) {
                        box.addView(text("Ye number aapke CRM me nahi hai.", 13f, false, T.amber))
                        addBtn.visibility = View.VISIBLE
                        addBtn.setOnClickListener { d.dismiss(); leadDialog(number, name, null, false) }
                        return@post
                    }
                    val b = r.optJSONObject("brief") ?: JSONObject()
                    if (b.optString("name").isNotEmpty()) nameTv.text = b.optString("name")
                    val meta = ArrayList<String>()
                    if (b.optString("company").isNotEmpty()) meta.add(b.optString("company"))
                    if (b.optString("city").isNotEmpty()) meta.add(b.optString("city"))
                    if (b.optString("ownerName").isNotEmpty()) meta.add("Owner: " + b.optString("ownerName"))
                    val pl = LinearLayout(this)
                    pl.orientation = LinearLayout.HORIZONTAL
                    pl.setPadding(0, dp(10), 0, 0)
                    if (b.optString("stage").isNotEmpty()) { val p = pill(b.optString("stage"), T.accent, T.accentSoft); pl.addView(p); p.layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.rightMargin = dp(6) } }
                    if (r.optDouble("pending") > 0) { val p = pill("₹" + r.optDouble("pending").toLong() + " baaki", T.amber, T.amberSoft); pl.addView(p) }
                    box.addView(pl)
                    if (meta.isNotEmpty()) { val m = text(meta.joinToString(" · "), 12f, false, grey); m.setPadding(0, dp(6), 0, 0); box.addView(m) }
                    val tasks = r.optJSONArray("tasks") ?: JSONArray()
                    if (tasks.length() > 0) {
                        val h = text("Open follow-ups", 13f, true, T.accent); h.setPadding(0, dp(12), 0, dp(2)); box.addView(h)
                        for (i in 0 until tasks.length()) { val t = tasks.getJSONObject(i); box.addView(text("• " + t.optString("title") + " — " + fmtTime(t.optLong("dueAt")), 13f, false, ink)) }
                    }
                    val hist = r.optJSONArray("history") ?: JSONArray()
                    val h2 = text("Last calls (" + r.optInt("totalCalls") + " total)", 13f, true, T.accent)
                    h2.setPadding(0, dp(12), 0, dp(2))
                    box.addView(h2)
                    if (hist.length() == 0) box.addView(text("Abhi tak koi call nahi.", 13f, false, grey))
                    for (i in 0 until hist.length()) {
                        val c = hist.getJSONObject(i)
                        val dir = c.optString("direction")
                        val line = (if (dir == "in") "Incoming" else if (dir == "missed") "Missed" else "Outgoing") + " · " + fmtTime(c.optLong("at")) + " · " + T.dur(c.optInt("sec")) + " · " + c.optString("outcome")
                        val tv = text(line, 13f, true, ink)
                        tv.setPadding(0, dp(8), 0, 0)
                        box.addView(tv)
                        val tagArr = c.optJSONArray("tags")
                        if (tagArr != null && tagArr.length() > 0) {
                            val tagParts = ArrayList<String>()
                            for (k in 0 until tagArr.length()) tagParts.add(tagArr.optString(k))
                            box.addView(text("# " + tagParts.joinToString("  # "), 12f, true, T.accent))
                        }
                        if (c.optDouble("score") > 0) box.addView(text("AI rating " + c.optDouble("score") + "/10", 12f, true, if (c.optDouble("score") >= 7.0) T.green else if (c.optDouble("score") >= 4.5) T.amber else T.coral))
                        val sm = c.optString("summary").ifEmpty { c.optString("note") }
                        if (sm.isNotEmpty()) box.addView(text(sm, 12f, false, grey))
                    }
                }
            } catch (e: ApiException) {
                ui.post { if (e.code == "unauthenticated") { d.dismiss(); disconnected("This phone was disconnected in the CRM. Pair it again.") } else load.text = e.message ?: "" }
            }
        }
    }

    // ------------------------------------------------------------------ calling
    private fun startCall(number: String, name: String, taskId: String) {
        pendingNumber = number
        pendingName = name
        pendingTaskId = taskId
        pendingSince = System.currentTimeMillis() - 2000
        val uri = Uri.parse("tel:" + T.dialable(number))
        try {
            if (hasPerm(Manifest.permission.CALL_PHONE)) startActivity(Intent(Intent.ACTION_CALL, uri)) else { pendingNumber = null; askPerms(); toast("Allow the Phone permission, then tap Call again.") }
        } catch (e: Exception) {
            pendingNumber = null
            toast("Could not start the call: " + (e.message ?: ""))
        }
    }

    private fun pickRecFolder() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(i, 21)
        } catch (e: Exception) { toast("Could not open the folder picker: " + (e.message ?: "")) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 21 && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { /* some pickers do not offer it */ }
                store.recTree = uri.toString()
                toast("Recorder folder saved ✔")
            }
        }
    }

    private fun handleErr(e: ApiException, into: TextView) {
        if (e.code == "unauthenticated") disconnected("This phone was disconnected in the CRM. Pair it again.") else into.text = e.message ?: ""
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
                val lead = pendingLead
                pendingNumber = null
                pendingLead = false
                showHome()
                if (lead) leadDialog(number, nm, row, true) else summaryDialog(number, nm, tid, row)
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
        val chosen = LinkedHashSet<String>()
        val tagList = store.tags()
        if (tagList.isNotEmpty()) {
            val th = text("Tags (tap to select, up to 5)", 12f, false, grey)
            th.setPadding(0, dp(8), 0, dp(4))
            box.addView(th)
            val flow = FlowLayout(this)
            flow.gap = dp(6)
            for (tg in tagList) {
                val chip = TextView(this)
                chip.text = tg
                chip.textSize = 13f
                chip.setPadding(dp(12), dp(6), dp(12), dp(6))
                val paint = {
                    val on = chosen.contains(tg)
                    chip.setTextColor(if (on) Color.WHITE else T.accent)
                    chip.background = T.round(this, if (on) T.accent else T.accentSoft, 16)
                }
                paint()
                chip.setOnClickListener {
                    if (chosen.contains(tg)) chosen.remove(tg) else if (chosen.size < 5) chosen.add(tg) else toast("Maximum 5 tags")
                    paint()
                }
                flow.addView(chip)
            }
            box.addView(flow)
        }
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
        var recFound: RecFile? = null
        if (row != null && dur > 0 && store.recTree.isNotEmpty()) {
            val recInfo = text("Looking for the call recording…", 12f, false, grey)
            box.addView(recInfo)
            val startAt = row.date
            bg {
                var f: RecFile? = null
                for (attempt in 0 until 5) {
                    f = RecFinder.find(this, store.recTree, startAt, dur, number)
                    if (f != null) break
                    Thread.sleep(2000)
                }
                ui.post {
                    recFound = f
                    recInfo.text = if (f != null) "Call recording found ✔ — it will be sent for transcript, notes and rating." else "No call recording found for this call. Check that auto call recording is ON in the phone's Phone app."
                }
            }
        } else if (row != null && dur > 0) {
            box.addView(text("Tip: on the Today tab choose your phone's call-recorder folder to get transcript, notes and rating of every call.", 12f, false, grey))
        }
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
                    .put("tags", JSONArray(chosen.toList()))
                    .put("nextAt", nextAtFor(next.selectedItemPosition))
                    .put("taskId", taskId)
                    .put("deviceCallId", "cl-" + (row?.id ?: ("m" + System.currentTimeMillis())))
                val voicePath = recFile?.absolutePath ?: ""
                val rf = recFound
                if (row != null) store.markLogged(row.id)
                recFile = null
                dialogOpen = false
                d.dismiss()
                toast("Saving to CRM…")
                bg {
                    val recPath = if (rf != null) (RecFinder.copyToCache(this, rf)?.absolutePath ?: "") else ""
                    store.enqueue(body, voicePath, recPath)
                    Uploader.flush(store)
                    ui.post { showHome() }
                }
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
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) ui.post { stopRecording() } }
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
        try { r.stop() } catch (e: Exception) { /* already stopped (60 s limit) — keep the file */ }
        try { r.release() } catch (e: Exception) { /* ignore */ }
        recorder = null
    }
}
