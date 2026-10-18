package com.pwrlive.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PWR Live for Android.
 *
 * - "PWR" shows pwr.dominos.com in a normal browser view. You sign in there yourself; the app
 *   keeps the session cookie like a browser does and never sees or stores your password.
 * - Every 2 minutes while the app is open, a hidden copy of PWR opens the page you chose, taps
 *   each tab you picked and taps that tab's XLS export button. The file is caught as it
 *   downloads and saved in the app's private "PWR Live" folder, replacing the previous copy.
 *   Nothing else is tapped, and your own PWR view is never touched.
 * - "Dashboard" reads those files and shows live SPLH, sales pace and what-ifs.
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var pwrView: WebView
    private lateinit var grabView: WebView
    private lateinit var dashView: WebView
    private lateinit var statusText: TextView
    private lateinit var tabDash: Button
    private lateinit var tabPwr: Button
    private val ui = Handler(Looper.getMainLooper())

    private val folder by lazy { File(filesDir, "PWR Live").apply { mkdirs() } }

    // ---- Grab cycle state ----
    private var busy = false
    private var tabs: List<String> = emptyList()
    private var tabIdx = 0
    private var pendingTab: String? = null
    private var pageReady: (() -> Unit)? = null
    private var cycleStarted = 0L
    private val intervalMs = 2 * 60 * 1000L

    private val loop = object : Runnable {
        override fun run() {
            startCycle()
            ui.postDelayed(this, intervalMs)
        }
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pwrlive", MODE_PRIVATE)
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1B5C9E"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        tabDash = barButton("Dashboard") { show(dash = true) }
        tabPwr = barButton("PWR") { show(dash = false) }
        val grabBtn = barButton("Grab now") { startCycle(force = true) }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) }
        }
        bar.addView(tabDash); bar.addView(tabPwr); bar.addView(grabBtn); bar.addView(statusText)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val stack = FrameLayout(this)
        root.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Hidden grabber first so it sits underneath; INVISIBLE still lays pages out.
        grabView = WebView(this).also { setupWeb(it) }
        grabView.visibility = View.INVISIBLE
        stack.addView(grabView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        pwrView = WebView(this).also { setupWeb(it) }
        stack.addView(pwrView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        dashView = WebView(this)
        stack.addView(dashView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        setupGrabber()
        setupPwr()
        setupDashboard()

        if (prefs.getBoolean("keepScreenOn", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(dash = prefs.contains("grabUrl"))
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(loop)
        ui.postDelayed(loop, 3000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(loop)
        CookieManager.getInstance().flush()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pwrView.visibility == View.VISIBLE && pwrView.canGoBack()) pwrView.goBack()
        else if (pwrView.visibility == View.VISIBLE) show(dash = true)
        else super.onBackPressed()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun barButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        setOnClickListener { onClick() }
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun show(dash: Boolean) {
        dashView.visibility = if (dash) View.VISIBLE else View.GONE
        pwrView.visibility = if (dash) View.GONE else View.VISIBLE
        tabDash.typeface = if (dash) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tabPwr.typeface = if (dash) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        if (!dash && pwrView.url == null) pwrView.loadUrl(prefs.getString("grabUrl", null) ?: "https://pwr.dominos.com/PWR/")
        if (dash) dashJs("window.refresh && window.refresh()")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(w: WebView) {
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.setSupportMultipleWindows(false) // "open in new window" exports load in place
        w.settings.loadWithOverviewMode = true
        w.settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webChromeClient = WebChromeClient()
    }

    // --------------------------------------------------------------- PWR view

    private fun setupPwr() {
        pwrView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (url.contains("pwr.dominos.com", true) && !url.contains("login", true)) prefs.edit().putString("lastPwrUrl", url).apply()
            }
        }
        // Exports you tap yourself are kept too.
        pwrView.setDownloadListener { url, ua, cd, mime, _ ->
            fetchDownload(url, ua, mime, cd, "Manual - " + (pwrView.title ?: "export"), pwrView)
        }
    }

    // ---------------------------------------------------------------- grabber

    private val blobHook = """
        (function(){ if (window.__pwrHook) return; window.__pwrHook = 1; window.__pwrBlobs = {};
          var oc = URL.createObjectURL;
          URL.createObjectURL = function(b){ var u = oc.apply(URL, arguments); try { window.__pwrBlobs[u] = b; } catch(e){} return u; };
          var orv = URL.revokeObjectURL;
          URL.revokeObjectURL = function(u){ setTimeout(function(){ try { orv.call(URL, u); } catch(e){} }, 60000); };
        })();
    """.trimIndent()

    private fun setupGrabber() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(grabView, blobHook, setOf("https://pwr.dominos.com"))
        }
        grabView.addJavascriptInterface(GrabBridge(), "PWRGrab")
        grabView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(blobHook, null)
                val cb = pageReady; pageReady = null
                cb?.let { ui.postDelayed(it, 4000) }
            }
        }
        grabView.setDownloadListener { url, ua, cd, mime, _ ->
            val tab = pendingTab ?: return@setDownloadListener
            fetchDownload(url, ua, mime, cd, tab, grabView)
        }
    }

    private fun tabList(): List<String> =
        (prefs.getString("tabs", null) ?: "Sales, Labor, Hourly Sales, TM Schedule")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun startCycle(force: Boolean = false) {
        if (busy) { if (force) toastStatus("Already grabbing…"); return }
        val url = prefs.getString("grabUrl", null)
        if (url == null) {
            toastStatus("Open PWR, sign in, go to Real Time, then tap “Use this PWR page” on the Dashboard.")
            return
        }
        busy = true; cycleStarted = System.currentTimeMillis()
        tabs = tabList(); tabIdx = 0
        updateStatus("Grabbing…")
        pageReady = { firstTab() }
        grabView.loadUrl(url)
        // Safety net if the page never finishes loading.
        ui.postDelayed({ if (busy && pageReady != null) { pageReady = null; log("error", "", "PWR didn't load (check the connection or sign in again)"); finishCycle() } }, 60000)
    }

    private fun firstTab() {
        grabView.evaluateJavascript("(function(){return !!document.querySelector('input[type=password]')})()") { r ->
            if (r == "true") { log("error", "", "PWR asked to sign in. Open the PWR view and sign in again."); finishCycle() }
            else doTab()
        }
    }

    private fun doTab() {
        if (tabIdx >= tabs.size) { finishCycle(); return }
        val tab = tabs[tabIdx]
        updateStatus("Grabbing $tab…")
        grabView.evaluateJavascript(clickTabJs(tab)) { r ->
            if (r?.contains("ok") == true) {
                pageReady = null
                // A tab can redraw in place or reload the page; wait for either.
                ui.postDelayed({ doExport(tab) }, 8000)
            } else {
                log("error", tab, "Tab not found on the page")
                nextTab()
            }
        }
    }

    private fun doExport(tab: String) {
        pendingTab = tab
        grabView.evaluateJavascript(clickExportJs) { r ->
            if (r == null || r.contains("notfound")) {
                pendingTab = null
                log("error", tab, "XLS button not found")
                nextTab()
            } else {
                val token = System.nanoTime()
                exportToken = token
                ui.postDelayed({
                    if (pendingTab == tab && exportToken == token) {
                        pendingTab = null
                        log("error", tab, "Tapped XLS (${r.trim('"').take(60)}) but no file arrived")
                        nextTab()
                    }
                }, 30000)
            }
        }
    }
    private var exportToken = 0L

    private fun nextTab() {
        tabIdx++
        ui.postDelayed({ doTab() }, 1500)
    }

    private fun finishCycle() {
        busy = false; pendingTab = null
        prefs.edit().putLong("lastCycle", System.currentTimeMillis()).apply()
        updateStatus()
        dashJs("window.refresh && window.refresh()")
    }

    private fun clickTabJs(name: String) = """
        (function(name){
          var norm = function(s){ return (s||'').replace(/\s+/g,' ').trim().toLowerCase(); };
          var want = norm(name).replace(/^real time /,'');
          var docs = [document];
          [].forEach.call(document.querySelectorAll('iframe'), function(f){ try { if (f.contentDocument) docs.push(f.contentDocument); } catch(e){} });
          for (var d = 0; d < docs.length; d++) {
            var els = docs[d].querySelectorAll('a,[role=tab],li,span,div,button');
            var best = null;
            for (var i = 0; i < els.length; i++) {
              var e = els[i]; if (!e.getClientRects().length) continue;
              var t = norm(e.innerText).replace(/^real time /,'');
              if (t === want && (!best || best.contains(e))) best = e;
            }
            if (best) { var t = best.closest('a,[role=tab],li,button') || best; t.click(); return 'ok'; }
          }
          return 'notfound';
        })(${JSONObject.quote(name)})
    """.trimIndent()

    // Finds the export button by its label/icon (xls, excel, export) and taps it.
    private val clickExportJs = """
        (function(){
          var docs = [document];
          [].forEach.call(document.querySelectorAll('iframe'), function(f){ try { if (f.contentDocument) docs.push(f.contentDocument); } catch(e){} });
          var label = function(e){
            var cls = typeof e.className === 'string' ? e.className : (e.className && e.className.baseVal) || '';
            return [e.title, e.alt, e.getAttribute && e.getAttribute('aria-label'), cls, e.id, (e.innerText||'').trim().slice(0,12), e.getAttribute && e.getAttribute('src'), e.getAttribute && e.getAttribute('href')].join(' ');
          };
          for (var d = 0; d < docs.length; d++) {
            var els = [].slice.call(docs[d].querySelectorAll('a,button,span,div,img,i,input,svg'));
            var cands = els.filter(function(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.width < 200 && /\bxlsx?\b|excel|export/i.test(label(e)); });
            cands.sort(function(a,b){ var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return ra.width*ra.height - rb.width*rb.height; });
            if (cands.length) {
              var t = cands[0].closest('a,button,[onclick],[role=button]') || cands[0];
              t.click();
              return 'clicked ' + t.tagName + ' ' + label(t).replace(/\s+/g,' ').trim();
            }
          }
          return 'notfound';
        })()
    """.trimIndent()

    inner class GrabBridge {
        @JavascriptInterface
        fun onBlob(b64: String, mime: String, name: String) {
            val tab = pendingTab ?: return
            try { saveFile(tab, Base64.decode(b64, Base64.DEFAULT), mime, "blob") }
            catch (e: Exception) { ui.post { log("error", tab, "Couldn't save the file: ${e.message}"); pendingTab = null; nextTab() } }
        }
        @JavascriptInterface
        fun onBlobError(msg: String) {
            val tab = pendingTab ?: return
            ui.post { log("error", tab, "Couldn't read the export: $msg"); pendingTab = null; nextTab() }
        }
    }

    // Catch the export however PWR delivers it: a link, a file built in the page, or inline data.
    private fun fetchDownload(url: String, ua: String?, mime: String?, cd: String?, tab: String, view: WebView) {
        when {
            url.startsWith("blob:") -> view.evaluateJavascript("""
                (function(u){ try {
                  var p = (window.__pwrBlobs && window.__pwrBlobs[u]) ? Promise.resolve(window.__pwrBlobs[u]) : fetch(u).then(function(r){ return r.blob(); });
                  p.then(function(b){ var r = new FileReader(); r.onload = function(){ PWRGrab.onBlob(String(r.result).split(',')[1], b.type || '', ''); }; r.onerror = function(){ PWRGrab.onBlobError('read failed'); }; r.readAsDataURL(b); })
                   .catch(function(e){ PWRGrab.onBlobError(String(e)); });
                } catch(e) { PWRGrab.onBlobError(String(e)); } })(${JSONObject.quote(url)})
            """.trimIndent(), null)
            url.startsWith("data:") -> {
                val comma = url.indexOf(',')
                val meta = url.substring(5, comma)
                val data = url.substring(comma + 1)
                val bytes = if (meta.contains("base64")) Base64.decode(data, Base64.DEFAULT) else java.net.URLDecoder.decode(data, "UTF-8").toByteArray()
                saveFile(tab, bytes, meta.substringBefore(';'), "data")
            }
            else -> Thread {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                    ua?.let { conn.setRequestProperty("User-Agent", it) }
                    conn.connectTimeout = 20000; conn.readTimeout = 30000
                    val bytes = conn.inputStream.use { it.readBytes() }
                    saveFile(tab, bytes, conn.contentType ?: mime ?: "", "link")
                } catch (e: Exception) {
                    ui.post { log("error", tab, "Download failed: ${e.message}"); if (pendingTab == tab) { pendingTab = null; nextTab() } }
                }
            }.start()
        }
    }

    private fun saveFile(tab: String, bytes: ByteArray, mime: String, how: String) {
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 64)), Charsets.ISO_8859_1).trimStart()
        val ext = when {
            head.startsWith("PK") -> "xlsx"
            head.startsWith("ÐÏ") -> "xls"
            head.startsWith("<") -> if (head.contains("html", true) && head.contains("login", true)) "login" else "xls"
            mime.contains("csv") -> "csv"
            else -> "bin"
        }
        ui.post {
            if (ext == "login" || bytes.size < 50) {
                log("error", tab, "PWR sent a sign-in page instead of the file ($how)")
            } else {
                val safe = tab.replace(Regex("[^A-Za-z0-9 &()-]"), "_")
                folder.listFiles()?.filter { it.nameWithoutExtension == safe }?.forEach { it.delete() }
                val tmp = File(folder, ".$safe.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(File(folder, "$safe.$ext"))
                log("ok", tab, "Saved ${bytes.size / 1024} KB ($how)")
            }
            if (pendingTab == tab) { pendingTab = null; nextTab() }
            dashJs("window.refresh && window.refresh()")
        }
    }

    // ------------------------------------------------------------------ log

    private fun log(kind: String, tab: String, msg: String) {
        val arr = JSONArray(prefs.getString("log", "[]"))
        val entry = JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("tab", tab).put("msg", msg)
        val out = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), 59)) out.put(arr.get(i))
        prefs.edit().putString("log", out.toString()).apply()
    }

    private fun updateStatus(text: String? = null) {
        val last = prefs.getLong("lastCycle", 0)
        statusText.text = text ?: if (last == 0L) "Not grabbed yet" else "Last grab " + SimpleDateFormat("h:mm a", Locale.US).format(Date(last))
    }

    private fun toastStatus(msg: String) {
        statusText.text = msg
        ui.postDelayed({ updateStatus() }, 6000)
    }

    // ------------------------------------------------------------- dashboard

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupDashboard() {
        dashView.settings.javaScriptEnabled = true
        dashView.settings.allowFileAccess = true
        dashView.addJavascriptInterface(AppBridge(), "PWRApp")
        dashView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        dashView.loadUrl("file:///android_asset/dashboard.html")
    }

    private fun dashJs(js: String) { ui.post { dashView.evaluateJavascript(js, null) } }

    inner class AppBridge {
        @JavascriptInterface
        fun files(): String {
            val arr = JSONArray()
            folder.listFiles()?.filter { !it.name.startsWith(".") }?.sortedBy { it.name }?.forEach {
                arr.put(JSONObject().put("name", it.name).put("modified", it.lastModified()).put("size", it.length()))
            }
            return arr.toString()
        }
        @JavascriptInterface
        fun read(name: String): String {
            val f = File(folder, name)
            if (!f.canonicalPath.startsWith(folder.canonicalPath) || !f.exists()) return ""
            return Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        }
        @JavascriptInterface fun log(): String = prefs.getString("log", "[]") ?: "[]"
        @JavascriptInterface fun getPref(k: String): String = prefs.getString("p_$k", "") ?: ""
        @JavascriptInterface fun setPref(k: String, v: String) { prefs.edit().putString("p_$k", v).apply() }
        @JavascriptInterface fun tabs(): String = tabList().joinToString(", ")
        @JavascriptInterface fun setTabs(v: String) { prefs.edit().putString("tabs", v).apply() }
        @JavascriptInterface fun grabUrl(): String = prefs.getString("grabUrl", "") ?: ""
        @JavascriptInterface fun lastCycle(): Long = prefs.getLong("lastCycle", 0)
        @JavascriptInterface fun busy(): Boolean = busy
        @JavascriptInterface fun useCurrentPwrPage(): String {
            val u = prefs.getString("lastPwrUrl", null) ?: return ""
            prefs.edit().putString("grabUrl", u).apply()
            ui.post { startCycle(force = true) }
            return u
        }
        @JavascriptInterface fun grabNow() { ui.post { startCycle(force = true) } }
        @JavascriptInterface fun openPwr() { ui.post { show(dash = false) } }
        @JavascriptInterface fun keepScreenOn(on: Boolean) {
            prefs.edit().putBoolean("keepScreenOn", on).apply()
            ui.post { if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        @JavascriptInterface fun isKeepScreenOn(): Boolean = prefs.getBoolean("keepScreenOn", false)
    }
}
