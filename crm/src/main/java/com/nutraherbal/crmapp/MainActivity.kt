package com.nutraherbal.crmapp

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Button
import android.app.AlertDialog

/**
 * NH CRM — the CRM as an installable Android app.
 * It opens your CRM address in a full-screen WebView, keeps you signed in, supports file / camera upload (selfie attendance, documents),
 * downloads (PDF invoices) and the back button. The first time it asks for the CRM address.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("nh_crm", Context.MODE_PRIVATE)
        val saved = sp.getString("url", "") ?: ""
        if (saved.isEmpty()) askAddress(sp.getString("url", "https://nutraherbalwellness.com/crm") ?: "") else start(saved)
    }

    private fun askAddress(def: String) {
        val box = EditText(this)
        box.setText(if (def.isEmpty()) "https://nutraherbalwellness.com/crm" else def)
        box.setSingleLine(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this)
        wrap.setPadding(pad, pad, pad, 0)
        wrap.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("CRM address")
            .setMessage("Type the web address of your Nutraherbal CRM (https://…)")
            .setView(wrap)
            .setCancelable(false)
            .setPositiveButton("Open") { _, _ ->
                var u = box.text.toString().trim().trimEnd('/')
                if (!u.startsWith("https://")) u = "https://" + u.removePrefix("http://")
                getSharedPreferences("nh_crm", Context.MODE_PRIVATE).edit().putString("url", u).apply()
                start(u)
            }
            .show()
    }

    private fun start(url: String) {
        val root = FrameLayout(this)
        web = WebView(this)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8))
        setContentView(root)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString + " NHCRMApp/1.0"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.WHITE)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val u = request.url
                val host = Uri.parse(url).host ?: ""
                val scheme = u.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    if (u.host == host) return false
                    // other websites (WhatsApp links, courier tracking …) open outside the app
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { /* ignore */ }
                    return true
                }
                // tel: → always the NH Calling app (not the phone's own dialer)
                if (scheme == "tel") {
                    try {
                        val i = Intent(Intent.ACTION_VIEW, u)
                        i.setPackage("com.nutraherbal.calling")
                        startActivity(i)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this@MainActivity, "Install the NH Calling app to call from here. Opening the phone dialer instead.", android.widget.Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(Intent.ACTION_DIAL, u)) } catch (e2: Exception) { /* no dialer */ }
                    }
                    return true
                }
                // mailto:, whatsapp:, upi: …
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { /* no app to handle */ }
                return true
            }
            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadData("<html><body style='font-family:sans-serif;padding:24px'><h2>No connection</h2><p>Could not open the CRM. Check your internet and pull the app down again.</p><p><a href='$url'>Try again</a></p><p style='color:#888;font-size:12px'>To change the CRM address, clear the app data in Android Settings.</p></body></html>", "text/html", "utf-8")
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                try {
                    startActivityForResult(params.createIntent(), 77)
                } catch (e: Exception) {
                    fileCb = null
                    return false
                }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                // camera for the attendance selfie
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 78)
                }
                runOnUiThread { request.grant(request.resources) }
            }
        }
        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mime, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(dlUrl))
                req.setMimeType(mime)
                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(dlUrl) ?: "")
                req.addRequestHeader("User-Agent", userAgent)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(dlUrl, contentDisposition, mime))
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                android.widget.Toast.makeText(this, "Downloading…", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl))) } catch (e2: Exception) { /* ignore */ }
            }
        }
        web.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) {
            val cb = fileCb
            fileCb = null
            if (cb != null) {
                val r: Array<Uri>? = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                cb.onReceiveValue(r)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
