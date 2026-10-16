/* PWR Live: runs on the PWR page from a bookmark and analyzes the live table in real time.
   It only reads the page. It never clicks, types, submits, or sends anything anywhere.
   Built into the bookmark by tools/build.js together with the shared metric engine;
   `T` (targets) is passed in by the bookmark wrapper. */

if (window.__pwrLive) { window.__pwrLive.show(); return; }

/* ---------- Stay-open mode ----------
   A bookmark only lives on the page it was opened on, so signing in or moving between PWR
   screens would close the panel. Instead, the tab shows PWR in a full-screen frame under the
   panel: you sign in and browse PWR inside it and the panel stays put. PWR keeps full control
   of its own pages; if one chooses to leave the frame, it loads normally and tapping PWR Live
   again brings the panel back with its history. */
let frame = null;
const startUrl = location.href;
// If PWR left the frame last time (the panel vanished without being closed), use single-page
// mode for the rest of this tab so the bookmark keeps working.
let leftFrame = false;
try { leftFrame = !!sessionStorage.getItem("pwrLive.framed"); if (leftFrame) sessionStorage.removeItem("pwrLive.framed"); } catch (e) {}
try { if (leftFrame) sessionStorage.setItem("pwrLive.noFrame", "1"); leftFrame = leftFrame || !!sessionStorage.getItem("pwrLive.noFrame"); } catch (e) {}
if (window.top === window && !leftFrame) {
  try { sessionStorage.setItem("pwrLive.framed", "1"); } catch (e) {}
  // The old page is being replaced; stop its leftover timers (such as its own auto-refresh)
  // so they don't reload the tab out from under the frame.
  try { let id = setTimeout(() => {}, 0); while (id > 0) { clearTimeout(id); clearInterval(id); id--; } } catch (e) {}
  const title = document.title, de = document.documentElement;
  de.replaceChildren();
  de.removeAttribute("class");
  const head = document.createElement("head"), body = document.createElement("body");
  const vp = document.createElement("meta"); vp.name = "viewport"; vp.content = "width=device-width, initial-scale=1";
  const tt = document.createElement("title"); tt.textContent = title;
  head.append(vp, tt);
  de.style.cssText = "height:100%;margin:0;";
  body.style.cssText = "height:100%;margin:0;overflow:hidden;";
  frame = document.createElement("iframe");
  frame.style.cssText = "border:0;width:100%;height:100%;display:block;";
  frame.title = title;
  frame.src = startUrl;
  body.append(frame);
  de.append(head, body);
}

const norm = s => String(s ?? "").toLowerCase().replace(/[^a-z0-9]/g, "");
const TGT = Object.assign(Object.fromEntries(METRICS.filter(m => m.target != null).map(m => [m.key, m.target])), T || {});
const PALETTE = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"];
const PACE_MIN = 30, STALE_MIN = 20;

function status(key, v) {
  const t = TGT[key], def = M[key];
  if (!def || v == null || t == null || !def.dir) return null;
  const band = Math.max(Math.abs(t) * 0.05, def.unit === "pts" ? 0.1 : 0);
  if (def.dir === "up") return v >= t ? "good" : v >= t - band ? "warn" : "bad";
  return v <= t ? "good" : v <= t + band ? "warn" : "bad";
}

/* ---------- Reading the page (read-only) ---------- */
const ROOT_SEL = '.k-grid,.dx-datagrid,.ag-root-wrapper,.slick-grid,.e-grid,.mat-table,.mat-mdc-table,[role="grid"],[role="treegrid"]';
const visible = el => el.getClientRects().length > 0;
const cellText = c => (c.innerText ?? c.textContent ?? "").replace(/\s+/g, " ").trim();
const inFooter = el => !!el.closest('tfoot,[class*="footer" i]');
const isHeaderRow = tr => !!tr.closest("thead") || (tr.cells.length > 0 && [...tr.cells].every(c => c.tagName === "TH"));
const skipRow = tr => inFooter(tr) || /group|footer|aggregate|total|summary/i.test(tr.className || "");
function rowCells(tr) { const out = []; for (const c of tr.cells) { out.push(cellText(c)); for (let i = 1; i < (c.colSpan || 1); i++) out.push(""); } return out; }

// Header rows (with colspans/rowspans) -> one label per column.
function headerLabels(trs) {
  const g = [];
  trs.forEach((tr, r) => {
    g[r] = g[r] || []; let c = 0;
    for (const cell of tr.cells) {
      while (g[r][c] !== undefined) c++;
      const cs = Math.max(1, cell.colSpan || 1), rs = Math.max(1, cell.rowSpan || 1), txt = cellText(cell);
      for (let i = 0; i < rs; i++) for (let j = 0; j < cs; j++) (g[r + i] = g[r + i] || [])[c + j] = txt;
      c += cs;
    }
  });
  const width = Math.max(0, ...g.map(r => r.length));
  return Array.from({ length: width }, (_, j) => [...new Set(g.map(r => r[j]).filter(Boolean))].join(" "));
}

// Grids often split into separate tables: header vs body, and frozen columns vs the rest.
function fromTables(tables) {
  const heads = [], bodies = [];
  for (const t of tables) {
    if (inFooter(t)) continue;
    const rows = [...t.rows].filter(tr => tr.closest("table") === t);
    const hr = rows.filter(isHeaderRow), br = rows.filter(tr => !isHeaderRow(tr) && !skipRow(tr));
    if (hr.length) heads.push(headerLabels(hr));
    if (br.length) bodies.push(br.map(rowCells));
  }
  if (!bodies.length) return null;
  let body;
  if (bodies.every(b => b.length === bodies[0].length)) body = bodies[0].map((_, i) => bodies.flatMap(b => b[i]));
  else body = bodies.sort((a, b) => b.length - a.length)[0];
  const header = heads.flat();
  return header.length ? [header, ...body] : body;
}

function fromAria(root) {
  const groups = new Map(); let n = 0;
  for (const r of root.querySelectorAll('[role="row"]')) {
    if (!visible(r)) continue;
    const cells = [...r.querySelectorAll('[role="columnheader"],[role="gridcell"],[role="cell"],[role="rowheader"]')];
    if (!cells.length) continue;
    const isH = cells.every(c => c.getAttribute("role") === "columnheader");
    const idx = r.getAttribute("aria-rowindex") || r.getAttribute("row-index") || "n" + n++;
    const key = (isH ? "h" : "b") + idx;
    if (!groups.has(key)) groups.set(key, { isH, cells: [] });
    groups.get(key).cells.push(...cells.map(cellText));
  }
  const all = [...groups.values()], head = all.filter(g => g.isH).pop();
  const body = all.filter(g => !g.isH).map(g => g.cells);
  return body.length ? (head ? [head.cells, ...body] : body) : null;
}

function docs() {
  const out = [];
  const walk = (d, depth) => {
    out.push(d);
    if (depth > 3) return;
    for (const f of d.querySelectorAll("iframe,frame")) { try { if (f.contentDocument && f.contentDocument.documentElement) walk(f.contentDocument, depth + 1); } catch (e) { /* other site */ } }
  };
  walk(document, 0);
  return out;
}

function scan() {
  const found = [];
  for (const d of docs()) {
    for (const r of d.querySelectorAll(ROOT_SEL)) {
      if (!visible(r) || (r.parentElement && r.parentElement.closest(ROOT_SEL))) continue;
      const tables = [...r.querySelectorAll("table")].filter(t => visible(t) && !(t.parentElement.closest("table") && r.contains(t.parentElement.closest("table"))));
      const grid = tables.length ? fromTables(tables) : fromAria(r);
      if (grid && grid.length > 1) found.push(grid);
    }
    // Plain tables outside known grid widgets. A header-only table directly followed by a
    // body-only table is treated as one grid.
    const plain = [...d.querySelectorAll("table")].filter(t => visible(t) && !t.closest(ROOT_SEL) && !t.parentElement.closest("table"));
    for (let i = 0; i < plain.length; i++) {
      const t = plain[i], rows = [...t.rows];
      const headOnly = rows.length && rows.every(isHeaderRow), next = plain[i + 1];
      if (headOnly && next && ![...next.rows].some(isHeaderRow)) { const g = fromTables([t, next]); if (g && g.length > 1) found.push(g); i++; continue; }
      const g = fromTables([t]); if (g && g.length > 1) found.push(g);
    }
  }
  return found.map(grid => ({ grid, parsed: parseGrid(grid) })).filter(x => x.parsed.cols.length && Object.keys(x.parsed.data).length);
}

const ID_RE = /^\d{3,6}$/;
function parseGrid(grid) {
  const hr = findHeaderRow(grid);
  const headers = grid[hr].map((h, i) => String(h || "").trim() || `Column ${i + 1}`);
  const rows = grid.slice(hr + 1).filter(r => r.filter(c => String(c).trim() !== "").length > 1);
  const map = autoMap(headers);
  let si = map.store;
  const idShare = j => rows.length ? rows.filter(r => ID_RE.test(String(r[j] || "").trim())).length / rows.length : 0;
  if (si < 0) si = headers.findIndex((_, j) => idShare(j) >= 0.6);
  const idLike = si >= 0 && idShare(si) >= 0.6;
  const recs = {};
  for (const r of rows) {
    const st = si >= 0 ? String(r[si] || "").trim() : "This store";
    if (!st || (idLike && !ID_RE.test(st)) || /^(grand\s*)?totals?\b|^group\b/i.test(st)) continue;
    recs[st] = r;
  }
  const keyOf = j => Object.keys(map).find(k => map[k] === j && M[k]) || null;
  const cols = [];
  headers.forEach((label, j) => {
    if (j === si || j === map.date) return;
    const vals = Object.values(recs).map(r => parseNumber(r[j]));
    if (vals.length && vals.filter(v => v != null).length >= vals.length * 0.5) cols.push({ id: label, label, key: keyOf(j), j });
  });
  const data = {};
  for (const [st, r] of Object.entries(recs)) { data[st] = {}; for (const c of cols) data[st][c.id] = parseNumber(r[c.j]); }
  // Derived: year-over-year % and projected end of day from last year's shape of the day.
  const byKey = k => cols.find(c => c.key === k);
  const sa = byKey("sales"), py = byKey("py_sales"), eod = byKey("py_eod");
  if (sa && py && !byKey("pcya")) { cols.push({ id: "PCYA (calc)", label: "vs last year", key: "pcya", derived: true }); for (const d of Object.values(data)) d["PCYA (calc)"] = d[sa.id] != null && d[py.id] ? (d[sa.id] - d[py.id]) / d[py.id] * 100 : null; }
  if (sa && py && eod) { cols.push({ id: "Projected EOD", label: "Projected EOD", key: "proj_eod", derived: true }); for (const d of Object.values(data)) d["Projected EOD"] = d[sa.id] != null && d[py.id] && d[eod.id] != null ? d[sa.id] * d[eod.id] / d[py.id] : null; }
  return { headers, cols, data, sig: headers.join("|") };
}

/* ---------- Session history ---------- */
const KEY = "pwrLive.v1";
const L = { snaps: [], cols: [], checkedAt: null, lastChangeAt: {}, pick: 0, interval: 15, sort: null, sortDir: -1, metric: null, mini: false, err: "" };
try {
  const saved = JSON.parse(sessionStorage.getItem(KEY) || "null");
  if (saved && saved.path === location.pathname && Date.now() - saved.snaps[saved.snaps.length - 1].t < 2 * 3600e3) Object.assign(L, { snaps: saved.snaps, cols: saved.cols, lastChangeAt: saved.lastChangeAt || {} });
} catch (e) { /* storage blocked: history lasts until the page reloads */ }
const save = () => { try { sessionStorage.setItem(KEY, JSON.stringify({ path: location.pathname, snaps: L.snaps.slice(-720), cols: L.cols, lastChangeAt: L.lastChangeAt })); } catch (e) {} };

function tick() {
  let found;
  try { found = scan(); } catch (e) { L.err = "Couldn't read the page: " + e.message; render(); return; }
  L.found = found;
  L.checkedAt = Date.now();
  if (!found.length) {
    const signIn = docs().some(d => [...d.querySelectorAll('input[type="password"]')].some(visible));
    if (signIn) {
      L.err = "Sign in to PWR. The panel stays open and starts reading once you're on Real Time Sales.";
      if (!L.mini) { L.autoMini = true; api.minimize(); }
    } else L.err = frame ? "No report table on this screen yet. Open Real Time Sales (or another PWR report with a table)." : "No report table found on this page. Open Real Time Sales (or another PWR report with a table) and tap PWR Live again.";
    render(); return;
  }
  L.err = "";
  if (L.autoMini) { L.autoMini = false; api.show(); return; }
  const best = found.map((f, i) => ({ i, s: f.parsed.cols.filter(c => c.key).length * 10 + Object.keys(f.parsed.data).length })).sort((a, b) => b.s - a.s);
  const pick = found[L.pick] ? L.pick : best[0].i;
  L.pick = pick;
  const p = found[pick].parsed;
  if (L.sig && L.sig !== p.sig) { L.snaps = []; L.lastChangeAt = {}; }
  L.sig = p.sig; L.cols = p.cols.map(({ id, label, key, derived }) => ({ id, label, key, derived }));
  const last = L.snaps[L.snaps.length - 1];
  if (!last || JSON.stringify(last.data) !== JSON.stringify(p.data)) {
    for (const st of Object.keys(p.data)) if (!last || JSON.stringify(last.data[st]) !== JSON.stringify(p.data[st])) L.lastChangeAt[st] = L.checkedAt;
    L.snaps.push({ t: L.checkedAt, data: p.data });
    if (L.snaps.length > 720) L.snaps.splice(0, L.snaps.length - 720);
    save();
  }
  if (!L.metric || !L.cols.some(c => c.id === L.metric)) L.metric = (L.cols.find(c => c.key === "pcya") || L.cols.find(c => c.key === "sales") || L.cols[0]).id;
  render();
}

/* ---------- Analysis ---------- */
const colBy = k => L.cols.find(c => c.key === k);
const latest = () => L.snaps[L.snaps.length - 1];
function snapAgo(min) {
  const now = latest(); if (!now) return null;
  const target = now.t - min * 60e3;
  let best = null;
  for (const s of L.snaps) { if (s.t <= target) best = s; else break; }
  if (best) return best;
  const first = L.snaps[0];
  return now.t - first.t >= 10 * 60e3 ? first : null;
}
const sumCol = (snap, id) => { let s = 0, n = 0; for (const d of Object.values(snap.data)) if (d[id] != null) { s += d[id]; n++; } return n ? s : null; };

function fmtCol(col, v) {
  if (v == null || !isFinite(v)) return "–";
  if (col.key && M[col.key]) return fmt(col.key, v, true);
  if (col.key === "proj_eod") return fmt("sales", v, true);
  if (/%|pct|percent/i.test(col.label)) return v.toFixed(1) + "%";
  if (/sales|\$|dollar|revenue/i.test(col.label)) return fmt("sales", v, true);
  return Math.abs(v) >= 100 ? Math.round(v).toLocaleString() : (Math.round(v * 100) / 100).toString();
}
const money = v => fmt("sales", v, true);
const clock = t => new Date(t).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
const ago = t => { const m = Math.round((Date.now() - t) / 60e3); return m < 1 ? "just now" : m === 1 ? "1 min ago" : `${m} min ago`; };

function pace(st) {
  const sa = colBy("sales"), py = colBy("py_sales"), now = latest(), old = snapAgo(PACE_MIN);
  if (!sa || !now || !old) return null;
  const pick = (s, id) => st == null ? sumCol(s, id) : s.data[st]?.[id];
  const ds = pick(now, sa.id) - pick(old, sa.id);
  const dp = py ? pick(now, py.id) - pick(old, py.id) : null;
  if (!isFinite(ds)) return null;
  return { mins: Math.round((now.t - old.t) / 60e3), ds, dp, pct: dp ? (ds - dp) / Math.abs(dp) * 100 : null };
}

function alerts() {
  const out = [], now = latest(); if (!now) return out;
  const stores = Object.keys(now.data);
  const pc = colBy("pcya");
  if (pc) for (const st of stores) { const v = now.data[st][pc.id]; if (v != null && v < 0) out.push({ sev: "bad", text: `${st} is ${Math.abs(v).toFixed(1)}% behind last year so far today.` }); }
  for (const st of stores) {
    const p = pace(st);
    if (p && p.dp > 0 && p.pct != null && p.pct <= -15) out.push({ sev: "warn", text: `${st} slowed down: ${money(p.ds)} in the last ${p.mins} min vs ${money(p.dp)} at this time last year (${p.pct.toFixed(0)}%).` });
    if (p && p.dp > 0 && p.pct != null && p.pct >= 25) out.push({ sev: "good", text: `${st} is surging: ${money(p.ds)} in the last ${p.mins} min vs ${money(p.dp)} last year (+${p.pct.toFixed(0)}%).` });
  }
  for (const c of L.cols) {
    if (!c.key || !M[c.key]?.dir || TGT[c.key] == null || c.key === "pcya") continue;
    for (const st of stores) { const v = now.data[st][c.id]; if (status(c.key, v) === "bad") out.push({ sev: "bad", text: `${st} ${c.label}: ${fmtCol(c, v)} (goal ${M[c.key].dir === "up" ? "≥" : "≤"} ${fmt(c.key, TGT[c.key], true)}).` }); }
    if (M[c.key].unit === "min") {
      const old = snapAgo(15);
      if (old) for (const st of stores) {
        const a = old.data[st]?.[c.id], b = now.data[st][c.id];
        if (a != null && b != null && b - a >= Math.max(a * 0.1, c.key === "make_time" ? 0.25 : 1)) out.push({ sev: "warn", text: `${st} ${c.label} up ${fmt(c.key, b - a, true)} in ${Math.round((now.t - old.t) / 60e3)} min (now ${fmtCol(c, b)}).` });
      }
    }
  }
  const watchedFor = now.t - L.snaps[0].t, othersMoving = stores.filter(st => now.t - (L.lastChangeAt[st] || 0) < STALE_MIN * 60e3).length;
  if (watchedFor >= STALE_MIN * 60e3 && othersMoving) for (const st of stores) {
    const t = L.lastChangeAt[st] || L.snaps[0].t;
    if (now.t - t >= STALE_MIN * 60e3) out.push({ sev: "warn", text: `No new numbers from ${st} since ${clock(t)}. The store may not be reporting.` });
  }
  const rank = { bad: 0, warn: 1, good: 2 };
  return out.sort((a, b) => rank[a.sev] - rank[b.sev]);
}

/* ---------- UI (Shadow DOM, so the page's own styles and elements are untouched) ---------- */
const host = document.createElement("div");
host.setAttribute("data-pwr-live", "");
host.style.cssText = "all:initial;position:fixed;z-index:2147483000;";
const root = host.attachShadow({ mode: "open" });
const CSS = `
:host { --bg:#ffffff; --bg2:#f1f4f7; --line:#d8dee5; --ink:#111820; --ink2:#4a5663; --ink3:#6f7b88; --acc:#1b5c9e;
  --good:#0a7d0a; --goodbg:#e2f4e2; --warn:#8a5d00; --warnbg:#fdf0d0; --bad:#b02c2c; --badbg:#fbe3e3; }
@media (prefers-color-scheme: dark) { :host { --bg:#1a2027; --bg2:#232b34; --line:#36414d; --ink:#eef2f6; --ink2:#b4bfca; --ink3:#8794a2; --acc:#6aa7e6;
  --good:#5bd35b; --goodbg:#163a1a; --warn:#f5c04a; --warnbg:#3d3214; --bad:#f08080; --badbg:#432022; } }
* { box-sizing: border-box; }
.panel { position: fixed; top: 10px; right: 10px; bottom: 10px; width: min(440px, calc(100vw - 20px)); background: var(--bg); color: var(--ink);
  border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 12px 40px rgba(0,0,0,.25); display: flex; flex-direction: column;
  font: 13px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; overflow: hidden; }
@media (max-width: 700px) { .panel { top: auto; left: 0; right: 0; bottom: 0; width: auto; height: 72vh; border-radius: 14px 14px 0 0; padding-bottom: env(safe-area-inset-bottom, 0px); } }
.head { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--line); }
.title { font-weight: 700; font-size: 15px; letter-spacing: .01em; }
.sub { color: var(--ink3); font-size: 11.5px; }
.grow { flex: 1; min-width: 0; }
button, select { font: inherit; color: inherit; }
.ib { border: 1px solid var(--line); background: var(--bg); border-radius: 8px; min-width: 32px; height: 32px; cursor: pointer; font-size: 16px; line-height: 1; }
.btn { border: 1px solid var(--line); background: var(--bg); border-radius: 8px; padding: 6px 10px; cursor: pointer; font-size: 12.5px; font-weight: 600; }
.btn:focus-visible, .ib:focus-visible, select:focus-visible, th:focus-visible { outline: 2px solid var(--acc); outline-offset: 1px; }
select { border: 1px solid var(--line); background: var(--bg); border-radius: 8px; padding: 5px 6px; font-size: 12.5px; max-width: 100%; }
.body { overflow: auto; padding: 10px 12px 14px; display: flex; flex-direction: column; gap: 12px; flex: 1; }
.ro { display: flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--ink2); background: var(--bg2); border-radius: 8px; padding: 6px 8px; flex-wrap: wrap; }
.kpis { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 8px; }
.kpi { background: var(--bg2); border-radius: 10px; padding: 8px 10px; min-width: 0; }
.kpi .l { color: var(--ink2); font-size: 11.5px; }
.kpi .v { font-size: 20px; font-weight: 700; font-variant-numeric: tabular-nums; }
.kpi .n { color: var(--ink3); font-size: 11.5px; }
.up { color: var(--good); } .down { color: var(--bad); }
h3 { margin: 0 0 6px; font-size: 12px; text-transform: uppercase; letter-spacing: .08em; color: var(--ink3); }
.alerts { display: flex; flex-direction: column; gap: 6px; }
.al { border-radius: 8px; padding: 6px 8px 6px 28px; position: relative; font-size: 12.5px; }
.al::before { position: absolute; left: 9px; top: 6px; font-weight: 700; font-family: ui-monospace, monospace; }
.al.bad { background: var(--badbg); } .al.bad::before { content: "✕"; color: var(--bad); }
.al.warn { background: var(--warnbg); } .al.warn::before { content: "!"; color: var(--warn); }
.al.good { background: var(--goodbg); } .al.good::before { content: "✓"; color: var(--good); }
.muted { color: var(--ink3); }
.scroll { overflow-x: auto; border: 1px solid var(--line); border-radius: 8px; }
table { border-collapse: collapse; width: 100%; font-size: 12.5px; font-variant-numeric: tabular-nums; }
th, td { padding: 6px 8px; text-align: right; white-space: nowrap; border-bottom: 1px solid var(--line); }
th { font-size: 11px; color: var(--ink2); font-weight: 600; cursor: pointer; background: var(--bg2); vertical-align: bottom; }
th:first-child, td:first-child { text-align: left; position: sticky; left: 0; background: var(--bg); }
th:first-child { background: var(--bg2); }
tr:last-child td { border-bottom: 0; }
td.good { background: var(--goodbg); } td.warn { background: var(--warnbg); } td.bad { background: var(--badbg); color: var(--bad); }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: 1px; }
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
svg { display: block; width: 100%; }
.legend { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 11.5px; color: var(--ink2); margin-top: 4px; }
.legend i { display: inline-block; width: 14px; height: 2px; vertical-align: 3px; margin-right: 5px; }
.tip { position: absolute; pointer-events: none; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 6px 8px; font-size: 11.5px; box-shadow: 0 6px 18px rgba(0,0,0,.18); }
.tip div { display: flex; justify-content: space-between; gap: 10px; }
.mini { position: fixed; right: 12px; bottom: calc(12px + env(safe-area-inset-bottom, 0px)); background: var(--acc); color: #fff; border: 0; border-radius: 999px;
  padding: 10px 14px; font: 600 13px system-ui, sans-serif; box-shadow: 0 8px 24px rgba(0,0,0,.3); cursor: pointer; }
`;
try { const sh = new CSSStyleSheet(); sh.replaceSync(CSS); root.adoptedStyleSheets = [sh]; }
catch (e) { const st = document.createElement("style"); st.textContent = CSS; root.append(st); }

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") el.className = v; else if (k.startsWith("on")) el.addEventListener(k.slice(2), v); else el.setAttribute(k, v);
  }
  for (const c of kids.flat()) if (c != null) el.append(c.nodeType ? c : document.createTextNode(String(c)));
  return el;
}
const SVGNS = "http://www.w3.org/2000/svg";
function s(tag, attrs, ...kids) { const el = document.createElementNS(SVGNS, tag); for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v); for (const c of kids) el.append(c); return el; }

const panel = h("div", { class: "panel", role: "dialog", "aria-label": "PWR Live" });
const mini = h("button", { class: "mini", type: "button", hidden: "" });
mini.style.display = "none";
root.append(panel, mini);
document.documentElement.append(host);

const storeColor = {};
const colorFor = st => storeColor[st] || (storeColor[st] = PALETTE[Object.keys(storeColor).length % PALETTE.length]);

function render() {
  const now = latest();
  const sa = colBy("sales"), py = colBy("py_sales"), pc = colBy("pcya"), pe = colBy("proj_eod");
  const totPace = pace(null);
  if (L.mini) {
    const tot = now && sa && py ? (sumCol(now, sa.id) - sumCol(now, py.id)) / sumCol(now, py.id) * 100 : null;
    mini.textContent = "PWR Live" + (tot != null && isFinite(tot) ? ` ${tot >= 0 ? "▲" : "▼"} ${Math.abs(tot).toFixed(1)}%` : "") + (L.alertCount ? ` · ${L.alertCount} alert${L.alertCount === 1 ? "" : "s"}` : "");
    return;
  }
  const scroller = panel.querySelector(".body"), keepScroll = scroller ? scroller.scrollTop : 0;
  const tblScroll = panel.querySelector(".scroll"), keepX = tblScroll ? tblScroll.scrollLeft : 0;
  panel.replaceChildren();
  panel.append(h("div", { class: "head" },
    h("div", { class: "grow" }, h("div", { class: "title" }, "PWR Live"),
      h("div", { class: "sub" }, L.checkedAt ? `Checked ${new Date(L.checkedAt).toLocaleTimeString()} · watching since ${L.snaps[0] ? clock(L.snaps[0].t) : "now"}` : "Reading the page…")),
    h("button", { class: "ib", type: "button", title: "Minimize", "aria-label": "Minimize", onclick: () => api.minimize() }, "–"),
    h("button", { class: "ib", type: "button", title: frame ? "Leave PWR Live" : "Close", "aria-label": frame ? "Leave PWR Live" : "Close", onclick: () => api.close() }, "×")));
  const body = h("div", { class: "body" });
  panel.append(body);

  body.append(h("div", { class: "ro" }, h("b", null, "Read-only."), "Reads this page; never clicks, changes, or sends anything. Checks every",
    h("select", { "aria-label": "Check interval", onchange: e => { L.interval = +e.target.value; restart(); } },
      [5, 15, 30, 60].map(n => h("option", { value: n, selected: n === L.interval ? "" : null }, n < 60 ? `${n} sec` : "1 min")))));

  if (L.err) body.append(h("div", { class: "al warn" }, L.err));
  if (!now) return;

  // Headline numbers for the whole group on screen.
  const kp = h("div", { class: "kpis" });
  if (sa) {
    const tot = sumCol(now, sa.id), pyt = py ? sumCol(now, py.id) : null, ch = pyt ? (tot - pyt) / pyt * 100 : null;
    kp.append(h("div", { class: "kpi" }, h("div", { class: "l" }, "Sales so far"), h("div", { class: "v" }, money(tot)),
      h("div", { class: "n" }, ch != null ? h("span", { class: ch >= 0 ? "up" : "down" }, `${ch >= 0 ? "▲" : "▼"} ${Math.abs(ch).toFixed(1)}% vs last year`) : `${Object.keys(now.data).length} stores`)));
  }
  if (pe) kp.append(h("div", { class: "kpi" }, h("div", { class: "l" }, "Projected end of day"), h("div", { class: "v" }, money(sumCol(now, pe.id))),
    h("div", { class: "n" }, colBy("py_eod") ? `Last year: ${money(sumCol(now, colBy("py_eod").id))}` : "")));
  if (sa) kp.append(h("div", { class: "kpi" }, h("div", { class: "l" }, `Last ${totPace ? totPace.mins : PACE_MIN} min`),
    h("div", { class: "v" }, totPace ? money(totPace.ds) : "…"),
    h("div", { class: "n" }, totPace ? (totPace.pct != null ? h("span", { class: totPace.pct >= 0 ? "up" : "down" }, `${totPace.pct >= 0 ? "▲" : "▼"} ${Math.abs(totPace.pct).toFixed(0)}% vs ${money(totPace.dp)} last year`) : "")
      : `Pace appears after 10 min of watching (keep this open).`)));
  if (pc && !sa) {
    const vals = Object.values(now.data).map(d => d[pc.id]).filter(v => v != null);
    kp.append(h("div", { class: "kpi" }, h("div", { class: "l" }, pc.label), h("div", { class: "v" }, (vals.reduce((a, b) => a + b, 0) / vals.length).toFixed(1) + "%"), h("div", { class: "n" }, "average of stores")));
  }
  kp.append(h("div", { class: "kpi" }, h("div", { class: "l" }, "Last change seen"), h("div", { class: "v" }, clock(now.t)), h("div", { class: "n" }, ago(now.t))));
  body.append(kp);

  // Alerts.
  const al = alerts(); L.alertCount = al.filter(a => a.sev !== "good").length;
  const ab = h("div", null, h("h3", null, "Needs attention"));
  const list = h("div", { class: "alerts" });
  if (!al.length) list.append(h("div", { class: "muted" }, "Nothing off right now."));
  for (const a of al.slice(0, 8)) list.append(h("div", { class: "al " + a.sev }, a.text));
  if (al.length > 8) list.append(h("div", { class: "muted" }, `+ ${al.length - 8} more`));
  ab.append(list); body.append(ab);

  // Store board.
  const cols = [...L.cols];
  const paceCol = sa ? { id: "__pace", label: `Last ${PACE_MIN} min vs LY` } : null;
  const show = paceCol ? [cols[0], paceCol, ...cols.slice(1)].filter(Boolean) : cols;
  const stores = Object.keys(now.data);
  const val = (st, c) => c.id === "__pace" ? (pace(st)?.pct ?? null) : now.data[st][c.id];
  if (L.sort) stores.sort((a, b) => { const x = val(a, L.sort), y = val(b, L.sort); if (x == null) return 1; if (y == null) return -1; return (x - y) * L.sortDir; });
  else stores.sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  const sortBy = id => { if (L.sort === id) L.sortDir *= -1; else { L.sort = id; L.sortDir = -1; } render(); };
  const tbl = h("table", null,
    h("thead", null, h("tr", null, h("th", { tabindex: "0", onclick: () => { L.sort = null; render(); } }, "Store"),
      show.map(c => h("th", { tabindex: "0", onclick: () => sortBy(c.id), onkeydown: e => { if (e.key === "Enter") sortBy(c.id); } }, c.label + (L.sort === c.id ? (L.sortDir > 0 ? " ↑" : " ↓") : ""))))),
    h("tbody", null, stores.map(st => h("tr", null,
      h("td", null, h("span", { class: "dot", style: `background:${colorFor(st)}` }), st),
      show.map(c => {
        if (c.id === "__pace") { if (now.t - (L.lastChangeAt[st] || now.t) >= STALE_MIN * 60e3) return h("td", { class: "warn" }, "no data"); const p = pace(st); return h("td", { class: p && p.pct != null ? (p.pct <= -15 ? "bad" : p.pct >= 0 ? "good" : "") : "" }, p ? (p.pct != null ? `${p.pct >= 0 ? "+" : ""}${p.pct.toFixed(0)}%` : money(p.ds)) : "…"); }
        const v = now.data[st][c.id], stt = c.key ? (c.key === "pcya" ? (v == null ? null : v >= 0 ? "good" : "bad") : status(c.key, v)) : null;
        return h("td", { class: stt || "" }, fmtCol(c, v));
      })))));
  const sc = h("div", { class: "scroll" }, tbl);
  body.append(h("div", null, h("h3", null, "Stores"), sc));

  // Trend over the session.
  const tr = h("div", null, h("div", { class: "row", style: "justify-content:space-between;margin-bottom:6px" }, h("h3", { style: "margin:0" }, "Since you started watching"),
    h("select", { "aria-label": "Metric", onchange: e => { L.metric = e.target.value; render(); } }, L.cols.map(c => h("option", { value: c.id, selected: c.id === L.metric ? "" : null }, c.label)))));
  tr.append(chart());
  body.append(tr);

  // Tools.
  const tools = h("div", { class: "row" },
    h("button", { class: "btn", type: "button", onclick: copyTable }, "Copy table for the analyzer"),
    L.found && L.found.length > 1 ? h("select", { "aria-label": "Table on this page", onchange: e => { L.pick = +e.target.value; tick(); } },
      L.found.map((f, i) => h("option", { value: i, selected: i === L.pick ? "" : null }, `Table ${i + 1}: ${f.parsed.headers.slice(0, 3).join(", ")}`))) : null,
    h("button", { class: "btn", type: "button", onclick: () => { L.snaps = []; L.lastChangeAt = {}; save(); tick(); } }, "Restart history"));
  body.append(tools);
  if (L.copied) body.append(h("div", { class: "muted" }, L.copied));
  body.append(h("div", { class: "muted" }, "Numbers update when the PWR page updates. If the table isn't refreshing on its own, tap the page's refresh button; this panel picks up the new numbers."));
  body.scrollTop = keepScroll; sc.scrollLeft = keepX;
}

function chart() {
  const box = h("div", { style: "position:relative" });
  const col = L.cols.find(c => c.id === L.metric);
  const snaps = L.snaps;
  if (!col || snaps.length < 2) { box.append(h("div", { class: "muted" }, "The chart fills in as the numbers change.")); return box; }
  const stores = Object.keys(latest().data).slice(0, 8);
  const W = 400, H = 170, ml = 52, mr = 8, mt = 8, mb = 20;
  const t0 = snaps[0].t, t1 = snaps[snaps.length - 1].t;
  const vals = []; for (const sn of snaps) for (const st of stores) { const v = sn.data[st]?.[col.id]; if (v != null) vals.push(v); }
  const tgt = col.key && TGT[col.key] != null && M[col.key].dir ? TGT[col.key] : null;
  if (tgt != null) vals.push(tgt);
  if (!vals.length) { box.append(h("div", { class: "muted" }, "No numbers in this column yet.")); return box; }
  let lo = Math.min(...vals), hi = Math.max(...vals); const pad = (hi - lo) * 0.1 || Math.abs(hi) * 0.1 || 1; lo -= pad; hi += pad;
  const X = t => ml + (t1 === t0 ? 0 : (t - t0) / (t1 - t0)) * (W - ml - mr), Y = v => mt + (1 - (v - lo) / (hi - lo)) * (H - mt - mb);
  const svg = s("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": `${col.label} since ${clock(t0)}` });
  const ink3 = getComputedStyle(panel).getPropertyValue("--ink3") || "#6f7b88", line = getComputedStyle(panel).getPropertyValue("--line") || "#ddd";
  for (let i = 0; i <= 3; i++) {
    const v = lo + (hi - lo) * i / 3;
    svg.append(s("line", { x1: ml, x2: W - mr, y1: Y(v), y2: Y(v), stroke: line, "stroke-width": 1 }));
    const tx = s("text", { x: ml - 6, y: Y(v) + 4, "text-anchor": "end", "font-size": 10, fill: ink3 }); tx.textContent = fmtCol(col, v); svg.append(tx);
  }
  [t0, t1].forEach((t, i) => { const tx = s("text", { x: X(t), y: H - 4, "text-anchor": i ? "end" : "start", "font-size": 10, fill: ink3 }); tx.textContent = clock(t); svg.append(tx); });
  if (tgt != null) svg.append(s("line", { x1: ml, x2: W - mr, y1: Y(tgt), y2: Y(tgt), stroke: ink3, "stroke-dasharray": "4 3", "stroke-width": 1.2 }));
  for (const st of stores) {
    let d = "";
    for (const sn of snaps) { const v = sn.data[st]?.[col.id]; if (v == null) continue; d += `${d ? "L" : "M"}${X(sn.t).toFixed(1)},${Y(v).toFixed(1)}`; }
    if (d) svg.append(s("path", { d, fill: "none", stroke: colorFor(st), "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }));
  }
  const xh = s("line", { y1: mt, y2: H - mb, stroke: ink3, "stroke-width": 1, visibility: "hidden" });
  svg.append(xh);
  box.append(svg);
  const tip = h("div", { class: "tip", hidden: "" }); tip.style.display = "none"; box.append(tip);
  svg.addEventListener("pointermove", e => {
    const r = svg.getBoundingClientRect(), t = t0 + ((e.clientX - r.left) / r.width * W - ml) / (W - ml - mr) * (t1 - t0);
    let sn = snaps[0]; for (const x of snaps) if (Math.abs(x.t - t) < Math.abs(sn.t - t)) sn = x;
    xh.setAttribute("x1", X(sn.t)); xh.setAttribute("x2", X(sn.t)); xh.setAttribute("visibility", "visible");
    tip.replaceChildren(h("div", { class: "muted" }, new Date(sn.t).toLocaleTimeString()),
      ...stores.map(st => ({ st, v: sn.data[st]?.[col.id] })).sort((a, b) => (b.v ?? -Infinity) - (a.v ?? -Infinity))
        .map(({ st, v }) => h("div", null, h("span", null, h("span", { class: "dot", style: `background:${colorFor(st)}` }), st), h("b", null, fmtCol(col, v)))));
    tip.style.display = ""; tip.style.top = "0px";
    const px = (X(sn.t) / W) * r.width; tip.style.left = (px > r.width / 2 ? Math.max(0, px - tip.offsetWidth - 10) : px + 10) + "px";
  });
  svg.addEventListener("pointerleave", () => { xh.setAttribute("visibility", "hidden"); tip.style.display = "none"; });
  const lg = h("div", { class: "legend" }, stores.map(st => h("span", null, h("i", { style: `background:${colorFor(st)}` }), st)));
  if (tgt != null) lg.append(h("span", null, h("i", { style: `border-top:2px dashed ${ink3};height:0` }), "Goal"));
  box.append(lg);
  return box;
}

function copyTable() {
  const now = latest(); if (!now) return;
  const lines = [["Store", ...L.cols.map(c => c.label)].join("\t")];
  for (const st of Object.keys(now.data)) lines.push([st, ...L.cols.map(c => { const v = now.data[st][c.id]; return v == null ? "" : String(Math.round(v * 100) / 100); })].join("\t"));
  const text = lines.join("\n");
  const done = msg => { L.copied = msg; render(); setTimeout(() => { L.copied = ""; render(); }, 6000); };
  try {
    navigator.clipboard.writeText(text).then(() => done("Copied. Paste it into the PWR Analyzer (Paste from spreadsheet)."), () => done("Your browser blocked copying. Use the PWR page's own export instead."));
  } catch (e) { done("Your browser blocked copying. Use the PWR page's own export instead."); }
}

/* ---------- Lifecycle ---------- */
let timer = null, observer = null, debounce = null;
function restart() { clearInterval(timer); timer = setInterval(tick, L.interval * 1000); }
const api = {
  show() { L.mini = false; panel.style.display = ""; mini.style.display = "none"; tick(); },
  minimize() { L.mini = true; panel.style.display = "none"; mini.style.display = ""; mini.removeAttribute("hidden"); render(); },
  close() {
    if (frame) { try { sessionStorage.removeItem("pwrLive.framed"); } catch (e) {} let u = startUrl; try { u = frame.contentWindow.location.href; } catch (e) {} location.href = u; return; }
    clearInterval(timer); if (observer) observer.disconnect(); host.remove(); delete window.__pwrLive;
  },
};
// Each time PWR loads a new screen in the frame: show its address in the address bar and watch it.
let frameObserver = null;
function onFrameLoad() {
  let d = null;
  try { d = frame.contentDocument; } catch (e) {}
  if (!d || !d.documentElement) { L.err = "This screen can't be shown inside PWR Live (it's on another site, often a sign-in page). Tap × to leave, sign in, then tap PWR Live again."; render(); return; }
  try { const u = frame.contentWindow.location.href; if (u && u !== "about:blank") history.replaceState(null, "", u); if (d.title) document.title = d.title; } catch (e) {}
  try {
    if (frameObserver) frameObserver.disconnect();
    frameObserver = new MutationObserver(() => { clearTimeout(debounce); debounce = setTimeout(tick, 1200); });
    frameObserver.observe(d.body || d.documentElement, { childList: true, subtree: true, characterData: true });
  } catch (e) {}
  tick();
}
if (frame) frame.addEventListener("load", onFrameLoad);
mini.addEventListener("click", () => api.show());
window.__pwrLive = api;
try {
  // Also react as soon as the page redraws its table (we only observe; nothing is changed).
  observer = new MutationObserver(muts => {
    if (muts.every(m => host.contains(m.target) || m.target === host)) return;
    clearTimeout(debounce); debounce = setTimeout(tick, 1200);
  });
  observer.observe(document.body, { childList: true, subtree: true, characterData: true });
} catch (e) {}
tick();
restart();
