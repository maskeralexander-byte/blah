#!/usr/bin/env node
// PWR connector: opens the report website in a real browser window so you can sign in
// yourself, then lets the PWR Analyzer read report tables and exports from it.
//
// View-only by design:
//   - This program never clicks, types, or submits anything on the site. It only reads
//     the text of tables on the page you're looking at, and files you choose to export.
//   - The view-only lock (on by default) also blocks every request from the browser that
//     could change data (POST, PUT, PATCH, DELETE), except sign-in requests.
//   - It never sees your password: you type it into the site itself.
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");

let chromium;
try { ({ chromium } = require("playwright")); }
catch { console.error("Playwright isn't installed. Run:  cd connector && npm run setup"); process.exit(1); }

const args = Object.fromEntries(process.argv.slice(2).map(a => {
  const m = a.match(/^--([^=]+)(?:=(.*))?$/); return m ? [m[1], m[2] ?? true] : [a, true];
}));
if (args.help) {
  console.log(`Usage: node server.js --url=https://report-site.example.com [options]

  --url=URL           Page to open when you click "Open site" (or set PWR_URL)
  --port=8787         Port for the analyzer (or set PWR_PORT)
  --allow=PATTERN     Extra URL text that may send data while locked (repeatable via commas),
                      for a sign-in page the built-in list doesn't catch
  --unlocked          Start with the view-only lock off
  --headless          Run the browser without a window (testing only; you can't sign in)`);
  process.exit(0);
}

const START_URL = String(args.url || process.env.PWR_URL || "");
const PORT = +(args.port || process.env.PWR_PORT || 8787);
const PROFILE = path.join(__dirname, ".browser-profile");
const APP = path.join(__dirname, "..", "index.html");
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
// Sign-in traffic is the one kind of write the lock lets through.
const SIGN_IN = [/login/i, /log-in/i, /signin/i, /sign-in/i, /logon/i, /\/auth/i, /oauth/i, /saml/i, /\/sso/i, /openid/i,
  /\/token\b/i, /mfa/i, /2fa/i, /okta\.com/i, /oktapreview\.com/i, /login\.microsoftonline\.com/i, /pingidentity|pingone/i, /duosecurity\.com/i,
  ...String(args.allow || "").split(",").filter(Boolean).map(s => new RegExp(s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"))];

const state = {
  locked: !args.unlocked,
  blocked: [],       // recent blocked writes, newest first
  blockedCount: 0,
  download: null,    // { name, data: Buffer, at }
};
let context = null, launching = null;

async function ensureBrowser() {
  if (context) return context;
  if (launching) return launching;
  launching = (async () => {
    const ctx = await chromium.launchPersistentContext(PROFILE, {
      headless: !!args.headless,
      viewport: null,
      acceptDownloads: true,
      serviceWorkers: "block", // so every request passes through the lock below
    });
    await ctx.route("**/*", route => {
      const req = route.request();
      if (state.locked && !SAFE_METHODS.has(req.method()) && !SIGN_IN.some(re => re.test(req.url()))) {
        state.blockedCount++;
        state.blocked.unshift({ method: req.method(), url: req.url().slice(0, 200), at: new Date().toISOString() });
        state.blocked.length = Math.min(state.blocked.length, 10);
        console.log(`Blocked ${req.method()} ${req.url().slice(0, 120)}`);
        return route.abort("blockedbyclient");
      }
      return route.continue();
    });
    const watch = page => page.on("download", async dl => {
      try {
        const file = await dl.path();
        state.download = { name: dl.suggestedFilename(), data: fs.readFileSync(file), at: new Date().toISOString() };
        console.log(`Export captured: ${state.download.name}`);
      } catch (e) { console.log("Couldn't read the export:", e.message); }
    });
    ctx.pages().forEach(watch);
    ctx.on("page", watch);
    ctx.on("close", () => { context = null; console.log("Browser closed."); });
    context = ctx;
    return ctx;
  })();
  try { return await launching; } finally { launching = null; }
}

function activePage() {
  if (!context) return null;
  const pages = context.pages().filter(p => !p.isClosed());
  return pages.reverse().find(p => p.url() !== "about:blank") || pages[0] || null;
}

// Runs inside the site's page. Reads table text only; changes nothing.
function extractTables() {
  const text = el => (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();
  const out = [];
  for (const t of document.querySelectorAll("table")) {
    if (!t.offsetParent && getComputedStyle(t).position !== "fixed") continue; // hidden
    const rows = [];
    for (const tr of t.rows) {
      if (tr.closest("table") !== t) continue;
      const row = [];
      for (const c of tr.cells) { row.push(text(c)); for (let i = 1; i < (c.colSpan || 1); i++) row.push(""); }
      if (row.some(Boolean)) rows.push(row);
    }
    if (rows.length > 1) out.push({ kind: "table", caption: t.caption ? text(t.caption) : (t.getAttribute("aria-label") || ""), rows });
  }
  // Grid widgets built from divs (role="grid"). Only rows the site has drawn are visible here.
  for (const g of document.querySelectorAll('[role="grid"], [role="table"], [role="treegrid"]')) {
    if (g.tagName === "TABLE") continue;
    const rows = [];
    for (const r of g.querySelectorAll('[role="row"]')) {
      const cells = [...r.querySelectorAll('[role="gridcell"], [role="cell"], [role="columnheader"], [role="rowheader"]')].map(text);
      if (cells.some(Boolean)) rows.push(cells);
    }
    if (rows.length > 1) out.push({ kind: "grid", caption: g.getAttribute("aria-label") || "", rows });
  }
  return out;
}

async function capture() {
  const page = activePage();
  if (!page) throw new Error("The site isn't open. Click Open site and sign in first.");
  const tables = [];
  for (const frame of page.frames()) {
    const found = await frame.evaluate(extractTables).catch(() => []);
    for (const t of found) tables.push({ ...t, frame: frame === page.mainFrame() ? "" : frame.url() });
  }
  return { url: page.url(), title: await page.title().catch(() => ""), at: new Date().toISOString(), tables };
}

/* ---------- Local server for the analyzer ---------- */
const json = (res, code, body) => { res.writeHead(code, { "Content-Type": "application/json", "Cache-Control": "no-store" }); res.end(JSON.stringify(body)); };
const readBody = req => new Promise(r => { let b = ""; req.on("data", c => { b += c; if (b.length > 1e5) req.destroy(); }); req.on("end", () => { try { r(JSON.parse(b || "{}")); } catch { r({}); } }); });

const server = http.createServer(async (req, res) => {
  // Only answer this computer, and only pages served from here (blocks other websites
  // and DNS-rebinding tricks from driving the connector).
  const host = String(req.headers.host || "");
  if (!/^(localhost|127\.0\.0\.1)(:\d+)?$/.test(host)) return json(res, 403, { error: "Forbidden host" });
  const url = new URL(req.url, `http://${host}`);
  if (url.pathname.startsWith("/api/") && req.method !== "GET" && req.headers["x-pwr"] !== "1") return json(res, 403, { error: "Missing header" });
  if (req.headers.origin && req.headers.origin !== `http://${host}`) return json(res, 403, { error: "Forbidden origin" });

  try {
    if (url.pathname === "/" || url.pathname === "/index.html") {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
      return res.end(fs.readFileSync(APP));
    }
    if (url.pathname === "/api/status" && req.method === "GET") {
      const page = activePage();
      return json(res, 200, {
        connector: true, browserOpen: !!context, startUrl: START_URL,
        page: page ? { url: page.url(), title: await page.title().catch(() => "") } : null,
        locked: state.locked, blockedCount: state.blockedCount, blocked: state.blocked,
        download: state.download ? { name: state.download.name, size: state.download.data.length, at: state.download.at } : null,
      });
    }
    if (url.pathname === "/api/open" && req.method === "POST") {
      const ctx = await ensureBrowser();
      let page = activePage();
      if (!page) page = await ctx.newPage();
      if (START_URL && (page.url() === "about:blank" || (await readBody(req)).home)) await page.goto(START_URL).catch(() => {});
      await page.bringToFront().catch(() => {});
      return json(res, 200, { ok: true });
    }
    if (url.pathname === "/api/capture" && req.method === "POST") return json(res, 200, await capture());
    if (url.pathname === "/api/lock" && req.method === "POST") {
      const body = await readBody(req);
      state.locked = body.locked !== false;
      console.log(`View-only lock ${state.locked ? "on" : "OFF"}`);
      return json(res, 200, { locked: state.locked });
    }
    if (url.pathname === "/api/download" && req.method === "GET") {
      if (!state.download) return json(res, 404, { error: "No export yet" });
      res.writeHead(200, { "Content-Type": "application/octet-stream", "X-Filename": encodeURIComponent(state.download.name), "Cache-Control": "no-store" });
      return res.end(state.download.data);
    }
    json(res, 404, { error: "Not found" });
  } catch (e) {
    json(res, 500, { error: e.message });
  }
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`PWR Analyzer: http://localhost:${PORT}`);
  console.log(`View-only lock: ${state.locked ? "on" : "off"}${START_URL ? `  ·  Site: ${START_URL}` : "  ·  No --url given; you can type the address in the browser window."}`);
});
process.on("SIGINT", async () => { if (context) await context.close().catch(() => {}); process.exit(0); });
