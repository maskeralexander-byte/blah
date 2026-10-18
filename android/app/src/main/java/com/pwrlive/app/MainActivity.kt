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

    companion object {
        // "A > B" taps A then B (for example a top menu, then a tab); plain names are tabs in the
        // section opened last.
        const val DEFAULT_TABS = "REAL TIME > Sales, Labor, Hourly Sales, TM Schedule, KEYS > Costs"
    }

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
                rememberPwrPage(url)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { rememberPwrPage(url) }
        }
        // Exports you tap yourself are kept too.
        pwrView.setDownloadListener { url, ua, cd, mime, _ ->
            fetchDownload(url, ua, mime, cd, "Manual - " + (pwrView.title ?: "export"), pwrView)
        }
    }

    private fun rememberPwrPage(url: String?) {
        if (url != null && url.contains("pwr.dominos.com", true) && !url.contains("login", true)) prefs.edit().putString("lastPwrUrl", url).apply()
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

    // "REAL TIME > Sales, Labor" means Labor is also under REAL TIME: a menu carries forward
    // until the next entry names another one.
    private fun tabList(): List<String> {
        var menu = ""
        return (prefs.getString("tabs", null) ?: DEFAULT_TABS)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .map { e ->
                if (e.contains(">")) { menu = e.substringBeforeLast(">").trim(); e }
                else if (menu.isNotEmpty()) "$menu > $e" else e
            }
    }

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
        grabView.evaluateJavascript("(function(){return (document.querySelector('input[type=password]') ? 'LOGIN|' : 'OK|') + document.title})()") { r ->
            val v = r?.trim('"') ?: ""
            prefs.edit().putString("grabPageTitle", v.substringAfter('|')).apply()
            if (v.startsWith("LOGIN")) { log("error", "", "PWR asked to sign in. Open the PWR view and sign in again."); finishCycle() }
            else doTab()
        }
    }

    private fun doTab() {
        if (tabIdx >= tabs.size) { finishCycle(); return }
        val entry = tabs[tabIdx]
        updateStatus("Grabbing ${entry.substringAfterLast('>').trim()}…")
        runSteps(entry, entry.split(">").map { it.trim() }.filter { it.isNotEmpty() }, 0)
    }

    private fun fileName(entry: String) = entry.split(">").joinToString(" - ") { it.trim() }

    private fun runSteps(entry: String, steps: List<String>, i: Int) {
        if (i >= steps.size) { selectStore { doExport(fileName(entry)) }; return }
        val step = steps[i]
        grabView.evaluateJavascript(clickTabJs(step, snap = i < steps.size - 1, afterMenu = i > 0)) { r ->
            val v = r?.trim('"') ?: ""
            if (v.startsWith("ok")) {
                pageReady = null
                // A menu needs a moment to open; a tab can redraw or reload the page.
                ui.postDelayed({ runSteps(entry, steps, i + 1) }, if (i < steps.size - 1) 3000 else 8000)
            } else {
                grabView.evaluateJavascript("document.title") { title ->
                    log("error", fileName(entry), "“$step” not found on “${title?.trim('"')}”. ${v.substringAfter('|', "none").take(400)}")
                    nextTab()
                }
            }
        }
    }

    // PWR opens on the team view (all stores added together). Pick the store the way a person
    // would: team selector -> Select from List -> check the store -> Apply. Skipped when the
    // selector already shows the store.
    private fun selectStore(tries: Int = 0, then: () -> Unit) {
        val store = prefs.getString("p_store", null)?.trim()?.takeIf { it.isNotEmpty() } ?: "2058"
        grabView.evaluateJavascript(storeJs(store, "open")) { r ->
            val v = r?.trim('"') ?: ""
            when {
                v.startsWith("already") -> then()
                v.startsWith("opened") -> ui.postDelayed({ storeList(store, 0, then) }, 2500)
                // The page may still be loading; give it a few more seconds.
                tries < 3 -> ui.postDelayed({ selectStore(tries + 1, then) }, 4000)
                else -> { log("error", "Store", "Couldn't find the store picker. ${v.substringAfter('|', "").take(300)}"); then() }
            }
        }
    }

    private fun storeList(store: String, tries: Int, then: () -> Unit) {
        // Only the first try may tap "Select Store"; tapping it again would fold the list back up.
        grabView.evaluateJavascript(storeJs(store, if (tries == 0) "list" else "listonly")) { r ->
            val v = r?.trim('"') ?: ""
            when {
                v.startsWith("ok") -> ui.postDelayed({ storePick(store, 0, then) }, 3500)
                v.startsWith("expand") && tries < 3 -> ui.postDelayed({ storeList(store, tries + 1, then) }, 1500)
                else -> { log("error", "Store", "“Select from List” not found. ${v.substringAfter('|', "").take(300)}"); then() }
            }
        }
    }

    private fun storePick(store: String, tries: Int, then: () -> Unit) {
        grabView.evaluateJavascript(storeJs(store, "pick")) { r ->
            val v = r?.trim('"') ?: ""
            if (v.startsWith("ok")) { log("ok", "Store", "Picked store $store"); ui.postDelayed(then, 8000) }
            // The list can take a few seconds to show up.
            else if (v.contains("no store list opened") && tries < 3) ui.postDelayed({ storePick(store, tries + 1, then) }, 2500)
            else { log("error", "Store", "Store $store not in the list. ${v.substringAfter('|', "").take(300)}"); then() }
        }
    }

    private fun storeJs(store: String, phase: String) = tapJs + visJs + """
        (function(store, phase){
          var norm = __pwrNorm, vis = function(e){ return !!e.getClientRects().length; };
          var inPopup = function(e){ return !!e.closest('.dx-overlay-content,.dx-submenu,.dx-popup-wrapper,.dx-context-menu,.dx-overlay-wrapper,[role=dialog]'); };
          if (phase === 'open') {
            var skip = /^(today|yesterday|latest date|stores to show|local currency)|currency|\d+ \d+ \d{4}/;
            var items = [].filter.call(document.querySelectorAll('.dx-menu-item'), function(e){ return vis(e) && !inPopup(e) && !skip.test(norm(e.innerText)); });
            var sel = items.filter(function(e){ return /team|group|store|^\d{3,5}\b/.test(norm(e.innerText)); })[0];
            if (!sel) return 'noselector|' + items.map(function(e){ return norm(e.innerText); }).join(', ');
            if (new RegExp('(^|\\D)' + store + '(\\D|$)').test(norm(sel.innerText))) return 'already';
            __pwrTap(sel); return 'opened';
          }
          var fresh = function(re){
            var best = null;
            [].forEach.call(document.querySelectorAll('.dx-treeview-item,.dx-menu-item,[role=menuitem],[role=treeitem],a,li,span,div,button'), function(e){
              if (!vis(e) || !inPopup(e) || !re.test(norm(e.innerText))) return;
              if (!best || best.contains(e)) best = e;
            });
            return best;
          };
          if (phase === 'list' || phase === 'listonly') {
            // Some pages open the store list straight away.
            if ([].some.call(document.querySelectorAll('.dx-datagrid'), function(g){ return vis(g) && inPopup(g); })) return 'ok';
            var item = fresh(/^select from list$/);
            if (item) { __pwrTap(item.closest('.dx-treeview-item,.dx-menu-item,[role=menuitem],[role=treeitem],li,a') || item); return 'ok'; }
            var grp = fresh(/^select store$/);
            if (grp) { if (phase === 'list') __pwrTap(grp.closest('.dx-treeview-item,.dx-menu-item,[role=menuitem],[role=treeitem],li,a') || grp); return 'expand|menu shows: ' + __pwrVisible().slice(0, 30).join(', '); }
            return 'notfound|menu shows: ' + __pwrVisible().slice(0, 30).join(', ');
          }
          // phase 'pick': the "Select from List" popup with a store grid.
          var grids = [].filter.call(document.querySelectorAll('.dx-datagrid'), function(g){ return vis(g) && inPopup(g); });
          var apply = function(){
            setTimeout(function(){ var b = fresh(/^apply$/); if (b) __pwrTap(b.closest('.dx-button,button,a') || b); }, 1200);
          };
          for (var gi = 0; gi < grids.length; gi++) {
            // Single-pick list (TM Schedule): no checkboxes, tapping the store's row selects it.
            var drows = grids[gi].querySelectorAll('tr.dx-data-row');
            if (drows.length && ![].some.call(drows, function(tr){ return tr.querySelector('.dx-select-checkbox,.dx-checkbox'); })) {
              var cell = null;
              [].forEach.call(drows, function(tr){ [].forEach.call(tr.cells, function(td){ if (!cell && (td.innerText || '').trim() === store) cell = td; }); });
              if (cell) { __pwrTap(cell); return 'ok:row'; }
              continue;
            }
            try {
              var inst = null, DX = window.DevExpress;
              [grids[gi].closest('.dx-widget'), grids[gi].parentElement, grids[gi]].forEach(function(h){ try { if (!inst && h && DX) inst = DX.ui.dxDataGrid.getInstance(h); } catch(e) {} });
              if (inst) {
                var items2 = inst.getDataSource().items(), hit = null;
                for (var k = 0; k < items2.length && !hit; k++) for (var f in items2[k]) if (String(items2[k][f]).trim() === store) { hit = items2[k]; break; }
                if (hit) { inst.selectRows([inst.keyOf(hit)], false); apply(); return 'ok:api'; }
              }
            } catch (e) {}
            // Fallback: tap checkboxes so only the store's row is checked.
            var rows = grids[gi].querySelectorAll('tr.dx-data-row'), found = false;
            [].forEach.call(rows, function(tr){
              var mine = [].some.call(tr.cells, function(td){ return (td.innerText || '').trim() === store; });
              var cb = tr.querySelector('.dx-select-checkbox,.dx-checkbox'); if (!cb) return;
              var on = cb.classList.contains('dx-checkbox-checked') || cb.getAttribute('aria-checked') === 'true';
              if (mine) found = true;
              if (mine !== on) __pwrTap(cb);
            });
            if (found) { apply(); return 'ok:tap'; }
          }
          return 'notfound|' + (grids.length ? 'list has no ' + store : 'no store list opened');
        })(${JSONObject.quote(store)}, ${JSONObject.quote(phase)})
    """.trimIndent()

    private fun doExport(tab: String, retry: Boolean = false) {
        pendingTab = tab
        grabView.evaluateJavascript(clickExportJs) { r ->
            if (r == null || r.contains("notfound")) {
                readGrid(tab, "XLS button not found")
            } else {
                val token = System.nanoTime()
                exportToken = token
                ui.postDelayed({
                    if (pendingTab == tab && exportToken == token) {
                        if (!retry) doExport(tab, retry = true) else readGrid(tab, "XLS export didn't arrive")
                    }
                }, 15000)
            }
        }
    }
    private var exportToken = 0L

    // Fallback: read the report straight from PWR's table (DevExtreme grid) and save it as CSV.
    private fun readGrid(tab: String, why: String) {
        pendingTab = tab
        exportToken = System.nanoTime()
        val token = exportToken
        grabView.evaluateJavascript(gridJs, null)
        ui.postDelayed({
            if (pendingTab == tab && exportToken == token) {
                pendingTab = null
                log("error", tab, "$why, and the table couldn't be read either")
                nextTab()
            }
        }, 15000)
        gridWhy = why
    }
    private var gridWhy = ""

    private fun nextTab() {
        tabIdx++
        ui.postDelayed({ doTab() }, 1500)
    }

    private fun finishCycle() {
        busy = false; pendingTab = null
        val keep = tabs.map { fileName(it).replace(Regex("[^A-Za-z0-9 &()-]"), "_") }.toSet()
        folder.listFiles()?.filter { !it.name.startsWith("Manual") && it.nameWithoutExtension !in keep }?.forEach { it.delete() }
        prefs.edit().putLong("lastCycle", System.currentTimeMillis()).apply()
        updateStatus()
        dashJs("window.refresh && window.refresh()")
    }

    // Real finger-like tap: DevExtreme widgets ignore a bare click().
    private val tapJs = """
        function __pwrTap(el){
          var r = el.getBoundingClientRect(), x = r.left + r.width/2, y = r.top + r.height/2;
          var o = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0, pointerId: 1, pointerType: 'touch', isPrimary: true };
          ['pointerover','pointerenter'].forEach(function(n){ try { el.dispatchEvent(new PointerEvent(n, o)); } catch(e) {} });
          ['mouseover','mouseenter'].forEach(function(n){ el.dispatchEvent(new MouseEvent(n, o)); });
          try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch(e) {}
          el.dispatchEvent(new MouseEvent('mousedown', o));
          try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch(e) {}
          el.dispatchEvent(new MouseEvent('mouseup', o));
          el.dispatchEvent(new MouseEvent('click', o));
        };
    """.trimIndent() + "\n"

    private val visJs = """
        function __pwrNorm(s){ return (s||'').toLowerCase().replace(/[^a-z0-9#&%() ]+/g,' ').replace(/\s+/g,' ').trim(); }
        function __pwrVisible(){
          var out = [], docs = [document];
          [].forEach.call(document.querySelectorAll('iframe'), function(f){ try { if (f.contentDocument) docs.push(f.contentDocument); } catch(e){} });
          docs.forEach(function(d){ [].forEach.call(d.querySelectorAll('a,li,[role=menuitem],.dx-menu-item,.dx-tab,span'), function(e){
            if (!e.getClientRects().length) return; var s = __pwrNorm(e.innerText); if (s && s.length < 40 && out.indexOf(s) < 0) out.push(s); }); });
          return out;
        };
    """.trimIndent() + "\n"

    // snap = true: remember what's visible before tapping a menu, so the next step can look in
    // the newly opened dropdown first.
    private fun clickTabJs(name: String, snap: Boolean = false, afterMenu: Boolean = false) = tapJs + visJs + """
        (function(name, snap, afterMenu){
          if (snap) {
            window.__pwrBefore = __pwrVisible();
            // Mark what's on screen now; anything unmarked after the tap came from the menu.
            var mdocs = [document];
            [].forEach.call(document.querySelectorAll('iframe'), function(f){ try { if (f.contentDocument) mdocs.push(f.contentDocument); } catch(e){} });
            mdocs.forEach(function(md){ [].forEach.call(md.querySelectorAll('a,[role=tab],[role=menuitem],.dx-menu-item,li,span,div,button'), function(e){ e.__pwrOld = !!e.getClientRects().length; }); });
          }
          var before = afterMenu ? (window.__pwrBefore || []) : [];
          var norm = __pwrNorm;
          var want = norm(name).replace(/^real time /,'');
          var docs = [document];
          [].forEach.call(document.querySelectorAll('iframe'), function(f){ try { if (f.contentDocument) docs.push(f.contentDocument); } catch(e){} });
          for (var d = 0; d < docs.length; d++) {
            var els = docs[d].querySelectorAll('a,[role=tab],[role=menuitem],.dx-menu-item,li,span,div,button');
            var best = null, bestNew = null;
            for (var i = 0; i < els.length; i++) {
              var e = els[i]; if (!e.getClientRects().length) continue;
              var t = norm(e.innerText);
              if (t === want || t.replace(/^real time /,'') === want) {
                if (!best || best.contains(e)) best = e;
                if (!e.__pwrOld && (!bestNew || bestNew.contains(e))) bestNew = e;
              }
            }
            // After a menu, only take what the menu just showed, never a same-named word elsewhere.
            if (afterMenu) best = bestNew; else if (bestNew) best = bestNew;
            if (best) { var t = best.closest('a,[role=tab],li,button,.dx-tab,.dx-item') || best; __pwrTap(t); return 'ok'; }
          }
          var seen;
          if (afterMenu) { var now = __pwrVisible(); seen = now.filter(function(s){ return before.indexOf(s) < 0; }); seen = ['menu opened: ' + (seen.length ? seen.slice(0, 25).join(', ') : 'nothing new appeared')]; }
          else { seen = []; for (var d2 = 0; d2 < docs.length; d2++) [].forEach.call(docs[d2].querySelectorAll('.dx-tab,[role=tab],.dx-menu-item,nav a,ul li a'), function(e){ var s = norm(e.innerText); if (s && s.length < 40 && seen.indexOf(s) < 0) seen.push(s); }); }
          return 'notfound|' + seen.slice(0, 25).join(', ');
        })(${JSONObject.quote(name)}, $snap, $afterMenu)
    """.trimIndent()

    // Finds the export button by its label/icon (xls, excel, export) and taps it.
    private val clickExportJs = tapJs + """
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
            // Prefer the "Export to Excel" button itself, then the smallest match.
            cands.sort(function(a,b){ var ea = /export to excel/i.test(label(a)) ? 0 : 1, eb = /export to excel/i.test(label(b)) ? 0 : 1; if (ea !== eb) return ea - eb; var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return ra.width*ra.height - rb.width*rb.height; });
            if (cands.length) {
              var t = cands[0].closest('a,button,[onclick],[role=button],.dx-button') || cands[0];
              // DevExtreme split buttons: tap the action half, not the outer box or the arrow.
              var inner = t.querySelector && (t.querySelector('.dx-dropdownbutton-action') || t.querySelector('.dx-buttongroup-item:not(.dx-dropdownbutton-toggle)') || t.querySelector('.dx-button:not(.dx-dropdownbutton-toggle)'));
              if (inner) t = inner;
              __pwrTap(t);
              return 'clicked ' + t.tagName + ' ' + label(t).replace(/\s+/g,' ').trim();
            }
          }
          return 'notfound';
        })()
    """.trimIndent()

    // TM Schedule: a timeline of shifts (DevExtreme scheduler) plus "Active Shifts" cards.
    // Rows named "Name (Scheduled)" are the schedule, other rows are actual clock times.
    // Only shifts in today's business day (6 AM to 6 AM) are kept.
    private val schedJs = """
        function dxKinds(){
          var k = {}; [].forEach.call(document.querySelectorAll('[class*="dx-"]'), function(e){ String(e.className && e.className.baseVal !== undefined ? e.className.baseVal : e.className).split(/\s+/).forEach(function(c){ if (/^dx-[a-z]+$/.test(c) && !/^dx-(widget|state|item|button|icon|template|texteditor|overlay|popup|scrollable|scrollview|menu|tab|tabs|treeview|checkbox|list)$/.test(c)) k[c] = 1; }); });
          return Object.keys(k).slice(0, 12).join(' ') || 'no DevExtreme widgets';
        }
        function schedCsv(docs){
          var now = new Date(), biz = new Date(now); if (biz.getHours() < 6) biz.setDate(biz.getDate() - 1); biz.setHours(6, 0, 0, 0);
          var bizEnd = new Date(biz.getTime() + 86400e3), recs = {}, order = [];
          var fmt = function(d){ var h = d.getHours(), m = d.getMinutes(); return ((h % 12) || 12) + ':' + (m < 10 ? '0' : '') + m + ' ' + (h < 12 ? 'AM' : 'PM'); };
          var rec = function(n){
            n = String(n || '').replace(/\s+/g, ' ').trim(); var m = n.match(/^(.*?)\s*\(([^)]*)\)\s*$/);
            var base = m ? m[1] : n, tag = m ? m[2] : '';
            if (!recs[base]) { recs[base] = { name: base, pos: '', ci: '', co: '', si: '', so: '' }; order.push(base); }
            var r = recs[base]; if (tag && !/sched/i.test(tag)) r.pos = tag;
            return { r: r, sched: /sched/i.test(tag) };
          };
          var put = function(name, st, en){
            if (!name || !(st instanceof Date) || isNaN(st)) return;
            if (!(en instanceof Date) || isNaN(en)) en = now;
            if (!(en > biz && st < bizEnd)) return;
            var x = rec(name);
            if (x.sched) { x.r.si = fmt(st); x.r.so = fmt(en); } else { x.r.ci = fmt(st); x.r.co = en >= now ? '' : fmt(en); }
          };
          for (var d = 0; d < docs.length; d++) {
            var w = docs[d].defaultView, DX = w.DevExpress;
            [].forEach.call(docs[d].querySelectorAll('.dx-scheduler'), function(el){
              var inst = null;
              [el, el.closest('.dx-widget')].forEach(function(h){ try { if (!inst && h && DX) inst = DX.ui.dxScheduler.getInstance(h); } catch(e) {} });
              if (!inst) return;
              var items = []; try { items = inst.getDataSource ? inst.getDataSource().items() : (inst.option('dataSource') || []); } catch(e) {}
              if (!Array.isArray(items)) items = [];
              var sx = inst.option('startDateExpr') || 'startDate', ex = inst.option('endDateExpr') || 'endDate', tx = inst.option('textExpr') || 'text';
              var res = (inst.option('resources') || []).map(function(r){
                var ds = r.dataSource; try { if (ds && !Array.isArray(ds) && ds.items) ds = ds.items(); } catch(e) {}
                return { field: r.fieldExpr || r.field, list: Array.isArray(ds) ? ds : [], val: r.valueExpr || 'id', disp: r.displayExpr || 'text' };
              });
              items.forEach(function(it){
                var name = '';
                res.forEach(function(r){
                  var v = it[r.field]; if (Array.isArray(v)) v = v[0]; if (v == null) return;
                  var hit = r.list.filter(function(o){ return o && o[r.val] === v; })[0];
                  if (hit && !name) name = typeof r.disp === 'function' ? r.disp(hit) : hit[r.disp];
                });
                put(name || it[tx], new Date(it[sx]), it[ex] ? new Date(it[ex]) : null);
              });
            });
            // No scheduler data: read the drawn bars ("Kavee Hangero (Driver)" / "Scheduled - Name (Role)" over "4:56 PM - 10:11 PM").
            if (!order.length) [].forEach.call(docs[d].querySelectorAll('.dx-scheduler-appointment'), function(e){
              var m = (e.innerText || '').trim().match(/^([^\n]{3,80})\n\s*(\d{1,2}:\d\d\s*[AP]M)\s*-\s*(\d{1,2}:\d\d\s*[AP]M)\s*$/i); if (!m) return;
              var sch = /^scheduled\s*-\s*/i.test(m[1]), x = rec(m[1].replace(/^scheduled\s*-\s*/i, ''));
              if (sch) { x.r.si = m[2].toUpperCase(); x.r.so = m[3].toUpperCase(); } else { x.r.ci = m[2].toUpperCase(); x.r.co = m[3].toUpperCase(); }
            });
            // Active Shifts cards: "Name (Role)" over "3:57 PM - 1:45 AM". Still on the clock.
            [].forEach.call(docs[d].querySelectorAll('div,a,span,li,button'), function(e){
              if (!e.getClientRects().length || e.closest('.dx-scheduler')) return;
              var m = (e.innerText || '').trim().match(/^([^\n]{3,60})\n\s*(\d{1,2}:\d\d\s*[AP]M)\s*-\s*(\d{1,2}:\d\d\s*[AP]M)?\s*$/i);
              if (!m || [].some.call(e.children, function(c){ return /\d:\d\d/.test(c.innerText || '') && /\n/.test((c.innerText || '').trim()); })) return;
              var x = rec(m[1]); if (x.sched) return;
              if (!x.r.ci) x.r.ci = m[2].toUpperCase(); x.r.co = '';
              if (m[3] && !x.r.so) x.r.so = m[3].toUpperCase();
            });
          }
          var rows = order.map(function(k){ return recs[k]; }).filter(function(r){ return r.ci || r.si; });
          if (!rows.length) return null;
          var q = function(s){ s = String(s || ''); return /[",]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
          return ['Name,Position,Clock In,Clock Out,Scheduled In,Scheduled Out'].concat(rows.map(function(r){ return [r.name, r.pos, r.ci, r.co, r.si, r.so].map(q).join(','); })).join('\n');
        }
    """.trimIndent() + "\n"

    // Reads every DevExtreme data grid on the page (all columns, including ones scrolled off screen)
    // and hands it back as CSV.
    private val gridJs = schedJs + """
        (function(){ try {
          var docs = [document];
          [].forEach.call(document.querySelectorAll('iframe'), function(f){ try { if (f.contentDocument) docs.push(f.contentDocument); } catch(e){} });
          var out = schedCsv(docs);
          for (var d = 0; d < docs.length && !out; d++) {
            var w = docs[d].defaultView, DX = w.DevExpress;
            var grids = docs[d].querySelectorAll('.dx-datagrid');
            for (var g = 0; g < grids.length && !out; g++) {
              var el = grids[g].closest('.dx-widget') || grids[g], inst = null;
              try { inst = DX && DX.ui && DX.ui.dxDataGrid && DX.ui.dxDataGrid.getInstance(el); } catch(e) {}
              if (!inst && w.jQuery) { try { inst = w.jQuery(el).dxDataGrid('instance'); } catch(e) {} }
              if (!inst) continue;
              var cols = inst.getVisibleColumns ? inst.getVisibleColumns() : (inst.option('columns') || []);
              cols = cols.filter(function(c){ return c && (c.dataField || c.calculateCellValue) && !c.command && c.type !== 'groupExpand'; });
              var gds = inst.getDataSource && inst.getDataSource(), items = gds ? gds.items() : [];
              var rows = [];
              (function flat(list){ (list || []).forEach(function(it){ if (it && it.items && it.key !== undefined && !it.dataField) flat(it.items); else rows.push(it); }); })(items);
              if (!rows.length || !cols.length) continue;
              var fmt = function(v){ if (v == null) return ''; if (v instanceof Date) { var p = function(n){ return (n < 10 ? '0' : '') + n; }; return v.getFullYear()+'-'+p(v.getMonth()+1)+'-'+p(v.getDate())+'T'+p(v.getHours())+':'+p(v.getMinutes())+':'+p(v.getSeconds()); } return String(v); };
              var q = function(s){ s = fmt(s); return /[",\n]/.test(s) ? '"' + s.replace(/"/g,'""') + '"' : s; };
              var lines = [cols.map(function(c){ return q(c.caption || c.dataField); }).join(',')];
              rows.forEach(function(r){ lines.push(cols.map(function(c){ var v; try { v = c.calculateCellValue ? c.calculateCellValue(r) : r[c.dataField]; } catch(e) { v = r[c.dataField]; } return q(v); }).join(',')); });
              out = lines.join('\n');
            }
          }
          if (out) PWRGrab.onGrid(out); else PWRGrab.onGridError('no readable table on this tab (page has: ' + dxKinds() + ')');
        } catch(e) { PWRGrab.onGridError(String(e)); } })()
    """.trimIndent()

    inner class GrabBridge {
        @JavascriptInterface
        fun onBlob(b64: String, mime: String, name: String) {
            val tab = pendingTab ?: return
            try { saveFile(tab, Base64.decode(b64, Base64.DEFAULT), mime, "blob") }
            catch (e: Exception) { ui.post { log("error", tab, "Couldn't save the file: ${e.message}"); pendingTab = null; nextTab() } }
        }
        @JavascriptInterface
        fun onGrid(csv: String) {
            val tab = pendingTab ?: return
            // An empty table must not replace the last good report.
            if (csv.lines().count { it.isNotBlank() } < 2) { onGridError("the table was empty, kept the previous file"); return }
            saveFile(tab, csv.toByteArray(Charsets.UTF_8), "text/csv", "read from table; $gridWhy")
        }
        @JavascriptInterface
        fun onGridError(msg: String) {
            val tab = pendingTab ?: return
            ui.post { log("error", tab, "$gridWhy; table read failed: $msg"); pendingTab = null; nextTab() }
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
            head.isNotEmpty() && (head[0].isLetter() || head[0] == '"') -> "csv"
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
        @JavascriptInterface fun grabPageTitle(): String = prefs.getString("grabPageTitle", "") ?: ""
        @JavascriptInterface fun lastCycle(): Long = prefs.getLong("lastCycle", 0)
        @JavascriptInterface fun busy(): Boolean = busy
        @JavascriptInterface fun useCurrentPwrPage(): String {
            val latch = java.util.concurrent.CountDownLatch(1)
            var cur: String? = null
            ui.post { cur = pwrView.url; latch.countDown() }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            cur?.let { rememberPwrPage(it) }
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
