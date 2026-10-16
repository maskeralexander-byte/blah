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
  try { let id = setTimeout(() => {}, 0); for (let n = 0; id > 0 && n < 100000; n++, id--) { clearTimeout(id); clearInterval(id); } } catch (e) {}
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
// Grid widgets that split one grid into several tables (header, body, frozen columns).
const WIDGET_SEL = '.ui-jqgrid,.k-grid,.dx-datagrid,.dxgvControl,.ag-root-wrapper,.slick-grid,.e-grid,.mat-table,.mat-mdc-table,.rgMasterTable,.igrid';
// Div-based grids described only by ARIA roles.
const ARIA_SEL = '[role="grid"]:not(table),[role="treegrid"]:not(table),[role="table"]:not(table)';
// Copies of frozen columns that some grids draw on top of the real table.
const CLONE_SEL = '.frozen-div,.frozen-bdiv,.frozen-sdiv,.dx-datagrid-content-fixed,.k-grid-content-locked-clone';
const HEADER_AREA = '.ui-jqgrid-hdiv,.dx-datagrid-headers,.k-grid-header,.ag-header,.dxgvHeader';
const visible = el => el.getClientRects().length > 0;
const cellText = c => (c.innerText ?? c.textContent ?? "").replace(/\s+/g, " ").trim();
const inFooter = el => !!el.closest('tfoot,[class*="footer" i],.ui-jqgrid-sdiv');
const isHeaderRow = tr => !!tr.closest("thead") || !!tr.closest(HEADER_AREA) || /\b(ui-jqgrid-labels|dx-header-row|dxgvHeader)\b/.test(tr.className || "") ||
  (tr.cells.length > 0 && [...tr.cells].every(c => c.tagName === "TH" || c.getAttribute("role") === "columnheader"));
const skipRow = tr => inFooter(tr) || /group|foot|aggregate|total|summary|jqgfirstrow|freespace|virtual-row/i.test(tr.className || "") || ![...tr.cells].some(c => cellText(c));
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
    if (inFooter(t) || t.closest(CLONE_SEL) || /_frozen$/.test(t.id || "")) continue;
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
  const found = []; // raw grids, parsed below
  for (const d of docs()) {
    for (const r of d.querySelectorAll(WIDGET_SEL)) {
      if (!visible(r) || (r.parentElement && r.parentElement.closest(WIDGET_SEL))) continue;
      const tables = [...r.querySelectorAll("table")].filter(t => visible(t) && !(t.parentElement.closest("table") && r.contains(t.parentElement.closest("table"))));
      const grid = tables.length ? fromTables(tables) : fromAria(r);
      if (grid && grid.length > 1) found.push(grid);
    }
    for (const r of d.querySelectorAll(ARIA_SEL)) {
      if (!visible(r) || r.closest(WIDGET_SEL) || (r.parentElement && r.parentElement.closest(ARIA_SEL))) continue;
      const grid = fromAria(r);
      if (grid && grid.length > 1) found.push(grid);
    }
    // Plain tables outside known grid widgets. A header-only table directly followed by a
    // body-only table is treated as one grid.
    const plain = [...d.querySelectorAll("table")].filter(t => visible(t) && !t.closest(WIDGET_SEL) && !t.closest(ARIA_SEL) && !t.closest(CLONE_SEL) && !t.parentElement.closest("table"));
    for (let i = 0; i < plain.length; i++) {
      const t = plain[i], rows = [...t.rows].filter(tr => !skipRow(tr) || isHeaderRow(tr));
      const headOnly = rows.length && rows.every(isHeaderRow), next = plain[i + 1];
      if (headOnly && next && ![...next.rows].some(isHeaderRow)) { const g = fromTables([t, next]); if (g && g.length > 1) found.push(g); i++; continue; }
      const g = fromTables([t]); if (g && g.length > 1) found.push(g);
    }
  }
  let parsed = found.map(parseGrid).filter(p => p.cols.length && Object.keys(p.data).length);
  // If no table gave recognizable columns, read the screen the way a person does.
  if (!parsed.some(p => p.cols.some(c => c.key))) {
    const vis = docs().map(readVisual).filter(Boolean);
    if (vis.length) parsed = vis;
  }
  if (!parsed.length) for (const d of docs()) { const p = readTiles(d); if (p) parsed.push(p); }
  return parsed;
}

// What the page holds, for when no report table is found. Frames from another site can't be read.
function pageInfo() {
  const info = { tables: 0, grids: 0, frames: 0, blocked: [] };
  for (const d of docs()) {
    info.tables += [...d.querySelectorAll("table")].filter(visible).length;
    info.grids += [...d.querySelectorAll(WIDGET_SEL + "," + ARIA_SEL)].filter(visible).length;
    for (const f of d.querySelectorAll("iframe,frame")) {
      if (f === frame || !visible(f)) continue;
      info.frames++;
      let readable = false;
      try { readable = !!(f.contentDocument && f.contentDocument.documentElement); } catch (e) {}
      const box = f.getBoundingClientRect();
      if (!readable && f.src && /^https?:/.test(f.src) && box.width * box.height > 20000) { const r = box; info.blocked.push({ src: f.src, host: new URL(f.src).host, area: r.width * r.height }); }
    }
  }
  info.blocked.sort((a, b) => b.area - a.area);
  return info;
}

const storeId = v => { const m = String(v || "").trim().match(/^#?(\d{3,6})\b/); return m ? m[1] : null; };
function parseGrid(grid) {
  const hr = findHeaderRow(grid);
  const headers = grid[hr].map((h, i) => String(h || "").trim() || `Column ${i + 1}`);
  const rows = grid.slice(hr + 1).filter(r => r.filter(c => String(c).trim() !== "").length > 1);
  const map = autoMap(headers);
  let si = map.store;
  const idShare = j => rows.length ? rows.filter(r => storeId(r[j])).length / rows.length : 0;
  if (si < 0) si = headers.findIndex((_, j) => idShare(j) >= 0.6);
  const idLike = si >= 0 && idShare(si) >= 0.6;
  const recs = {};
  for (const r of rows) {
    const raw = si >= 0 ? String(r[si] || "").trim() : "This store";
    const st = idLike ? storeId(raw) : raw;
    if (!st || /^(grand\s*)?totals?\b|^group\b/i.test(st)) continue;
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
  const raw = {};
  for (const [st, r] of Object.entries(recs)) { data[st] = {}; raw[st] = {}; for (const c of cols) { data[st][c.id] = parseNumber(r[c.j]); raw[st][c.id] = String(r[c.j]).trim(); } }
  return { headers, cols, data, raw };
}

// Reads any store table by position on screen, however the page builds it: finds a column of
// store numbers, takes the numbers on the same line as each, and names each number by the header
// label directly above its column.
function readVisual(d) {
  if (!d.body) return null;
  const skipSel = "script,style,svg,canvas,select,option,noscript,[data-pwr-live]";
  const leaves = [];
  for (const el of d.body.querySelectorAll("*")) {
    if ([...el.children].some(c => (c.textContent || "").trim())) continue;
    const text = cellText(el);
    if (!text || text.length > 60 || el.closest(skipSel)) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) continue;
    leaves.push({ el, text, r, cy: r.top + r.height / 2 });
  }
  // Column geometry comes from the cell, not the text: short text like "# DE" is narrower than
  // its column, so use the nearest block-level box around inline text.
  const view = d.defaultView;
  for (const x of leaves) {
    let b = x.el;
    while (b.parentElement && b.parentElement !== d.body && /^(inline|contents)$/.test(view.getComputedStyle(b).display)) b = b.parentElement;
    const br = b === x.el ? x.r : b.getBoundingClientRect();
    x.left = br.left; x.right = br.right; x.cx = br.left + br.width / 2;
  }
  const groups = new Map();
  for (const x of leaves) if (/^\d{3,6}$/.test(x.text)) { const k = Math.round(x.r.left / 8); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(x); }
  let ids = [...groups.values()].filter(g => g.length >= 3).sort((a, b) => b.length - a.length)[0];
  if (!ids) return null;
  const seen = new Set(); ids = ids.filter(x => !seen.has(x.text) && seen.add(x.text)); // frozen-column copies
  const rowOf = idl => { const band = Math.max(6, idl.r.height * 0.6); return leaves.filter(x => x !== idl && x.cx > idl.r.right && Math.abs(x.cy - idl.cy) < band && parseNumber(x.text) != null); };
  const top = Math.min(...ids.map(x => x.r.top));
  const centers = rowOf(ids[0]).map(x => x.cx);
  const heads = leaves.filter(x => x.r.bottom <= top + 2 && x.r.bottom >= top - 260 && parseNumber(x.text) == null && !/^group\b/i.test(x.text) &&
    centers.filter(c => c >= x.left - 2 && c <= x.right + 2).length <= 1);
  const labelFor = cx => {
    const over = heads.filter(hd => hd.left - 2 <= cx && cx <= hd.right + 2).sort((a, b) => b.r.bottom - a.r.bottom);
    if (!over.length) return null;
    const stack = [over[0]]; let stackTop = over[0].r.top;
    for (const hd of over.slice(1)) if (hd.r.bottom >= stackTop - 6) { stack.push(hd); stackTop = Math.min(stackTop, hd.r.top); }
    return stack.sort((a, b) => a.r.top - b.r.top).map(x => x.text).join(" ");
  };
  const data = {}, raw = {}, labels = [];
  for (const idl of ids) {
    data[idl.text] = {}; raw[idl.text] = {};
    for (const c of rowOf(idl).sort((a, b) => a.cx - b.cx)) {
      const label = labelFor(c.cx) || `Column at ${Math.round(c.cx)}px`;
      if (label in data[idl.text]) continue;
      if (!labels.includes(label)) labels.push(label);
      data[idl.text][label] = parseNumber(c.text); raw[idl.text][label] = c.text;
    }
  }
  if (!labels.length) return null;
  const map = autoMap(labels);
  const keyOf = j => Object.keys(map).find(k => map[k] === j && M[k]) || null;
  return { headers: labels, cols: labels.map((label, j) => ({ id: label, label, key: keyOf(j) })), data, raw };
}

// Tile dashboards (like Service & Labor Summary): pair each number with the label just above it
// in the same small card.
const VALUE_RE = /^[↑↓▲▼⬆⬇+-]?\s*\(?-?\$?\d[\d,]*(\.\d+)?\s*%?\)?$/;
function readTiles(d) {
  if (!d.body) return null;
  const skip = "table,svg,canvas,script,style,select,option,noscript,[data-pwr-live]," + WIDGET_SEL + "," + ARIA_SEL;
  const items = [];
  for (const el of d.body.querySelectorAll("*")) {
    if ([...el.children].some(c => (c.textContent || "").trim())) continue; // not a text leaf
    const text = cellText(el);
    if (!text || text.length > 40 || !visible(el) || el.closest(skip)) continue;
    items.push({ el, text });
  }
  const labels = [], vals = [];
  items.forEach((x, i) => {
    if (!VALUE_RE.test(x.text)) return;
    for (let k = i - 1; k >= Math.max(0, i - 4); k--) {
      const y = items[k];
      if (VALUE_RE.test(y.text) || y.text.length < 2) continue;
      let anc = x.el.parentElement, depth = 0;
      while (anc && !anc.contains(y.el) && depth < 5) { anc = anc.parentElement; depth++; }
      if (!anc || !anc.contains(y.el) || cellText(anc).length > 160) break;
      if (!labels.includes(y.text)) { labels.push(y.text); vals.push(x.text); }
      break;
    }
  });
  if (labels.length < 2) return null;
  let st = "This store";
  for (const x of items) { const m = x.text.match(/^store[\s#:-]*(\d{3,6})$/i); if (m) { st = m[1]; break; } }
  const map = autoMap(labels);
  const keyOf = j => Object.keys(map).find(k => map[k] === j && M[k]) || null;
  const cols = labels.map((label, j) => ({ id: label, label, key: keyOf(j) }));
  const data = { [st]: Object.fromEntries(labels.map((l, j) => [l, parseNumber(vals[j])])) };
  const raw = { [st]: Object.fromEntries(labels.map((l, j) => [l, vals[j].replace(/^[↑↓▲▼⬆⬇]\s*/, "")])) };
  return { headers: labels, cols, data, raw };
}

/* ---------- Session history ---------- */
const KEY = "pwrLive.v1";
const L = { snaps: [], cols: [], colSeen: {}, raw: {}, acc: {}, accLog: [], checkedAt: null, lastChangeAt: {}, interval: 15, sort: null, sortDir: -1, metric: null, mini: false, err: "" };
try {
  const saved = JSON.parse(sessionStorage.getItem(KEY) || "null");
  if (saved && saved.snaps && saved.snaps.length && Date.now() - saved.snaps[saved.snaps.length - 1].t < 2 * 3600e3) Object.assign(L, { snaps: saved.snaps, cols: saved.cols || [], colSeen: saved.colSeen || {}, raw: saved.raw || {}, acc: saved.acc || {}, accLog: saved.accLog || [], lastChangeAt: saved.lastChangeAt || {} });
} catch (e) { /* storage blocked: history lasts until the page reloads */ }
const save = () => { try { sessionStorage.setItem(KEY, JSON.stringify({ snaps: L.snaps.slice(-720), cols: L.cols, colSeen: L.colSeen, raw: L.raw, acc: L.acc, accLog: L.accLog, lastChangeAt: L.lastChangeAt })); } catch (e) {} };

// Year-over-year %, SPLH (sales ÷ labor hours), and projected end of day (today × last year's full day ÷ last year so far).
function addDerived(data) {
  const byKey = k => L.cols.find(c => c.key === k && !c.derived);
  const sa = byKey("sales"), py = byKey("py_sales"), eod = byKey("py_eod");
  const addCol = (id, label, key) => { if (!L.cols.some(c => c.id === id)) L.cols.push({ id, label, key, derived: true }); };
  if (sa && py && !byKey("pcya")) { addCol("PCYA (calc)", "vs last year", "pcya"); for (const d of Object.values(data)) d["PCYA (calc)"] = d[sa.id] != null && d[py.id] ? (d[sa.id] - d[py.id]) / d[py.id] * 100 : null; }
  const hr = byKey("labor_hours");
  if (sa && hr && !byKey("splh")) { addCol("SPLH (calc)", "SPLH", "splh"); for (const d of Object.values(data)) d["SPLH (calc)"] = d[sa.id] != null && d[hr.id] > 0 ? d[sa.id] / d[hr.id] : null; }
  if (sa && py && eod) { addCol("Projected EOD", "Projected EOD", "proj_eod"); for (const d of Object.values(data)) d["Projected EOD"] = d[sa.id] != null && d[py.id] && d[eod.id] != null ? d[sa.id] * d[eod.id] / d[py.id] : null; }
}

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
    } else {
      const info = pageInfo();
      L.info = info;
      L.err = info.blocked.length
        ? `The report is in a section of the page that comes from ${info.blocked[0].host}, which PWR Live isn't allowed to read from here. Tap Open report to load that section by itself, then tap PWR Live again.`
        : frame ? "No report table on this screen yet. Open Real Time Sales (or another PWR report with a table)." : "No report table found on this page. Open Real Time Sales (or another PWR report with a table) and tap PWR Live again.";
    }
    render(); return;
  }
  L.err = ""; L.info = null;
  if (L.autoMini) { L.autoMini = false; api.show(); return; }
  // Merge everything on this screen into the running board, so each PWR tab you visit adds
  // its columns (Sales, Labor, Service summary...) to the same stores.
  const last = L.snaps[L.snaps.length - 1];
  const data = JSON.parse(JSON.stringify(last ? last.data : {}));
  for (const p of found) {
    for (const c of p.cols) {
      const have = L.cols.find(x => x.id === c.id);
      if (!have) L.cols.push({ id: c.id, label: c.label, key: c.key && !L.cols.some(x => x.key === c.key && !x.derived) ? c.key : null });
      L.colSeen[c.id] = L.checkedAt;
    }
    for (const [st, vals] of Object.entries(p.data)) Object.assign(data[st] = data[st] || {}, vals);
    for (const [st, vals] of Object.entries(p.raw || {})) Object.assign(L.raw[st] = L.raw[st] || {}, vals);
  }
  applyManual();
  addDerived(data);
  if (!last || JSON.stringify(last.data) !== JSON.stringify(data)) {
    for (const st of Object.keys(data)) if (!last || JSON.stringify(last.data[st]) !== JSON.stringify(data[st])) L.lastChangeAt[st] = L.checkedAt;
    L.snaps.push({ t: L.checkedAt, data });
    if (L.snaps.length > 720) L.snaps.splice(0, L.snaps.length - 720);
    save();
  }
  trackLabor(data, L.checkedAt);
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

// Column choices made in the panel (for example "this column is labor hours"), kept in this browser.
let manual = {};
try { manual = JSON.parse(localStorage.getItem("pwrLive.manual2") || "{}"); } catch (e) {}
function applyManual() {
  for (const [key, id] of Object.entries(manual)) {
    if (!L.cols.some(c => c.id === id)) continue;
    for (const c of L.cols) { if (c.id === id) c.key = key; else if (c.key === key && !c.derived) c.key = null; }
  }
}
function setManual(key, id) {
  if (id) manual[key] = id; else delete manual[key];
  try { localStorage.setItem("pwrLive.manual2", JSON.stringify(manual)); } catch (e) {}
  L.cols = L.cols.filter(c => !c.derived); // recompute derived columns
  const last = latest(); if (last) { applyManual(); addDerived(last.data); }
  render();
}

// SPLH goal: from the analyzer's Targets, or set right in the panel (kept in this browser).
let splhGoal = TGT.splh ?? null;
try { const g = localStorage.getItem("pwrLive.splhGoal"); if (g) splhGoal = +g; } catch (e) {}
/* ---------- Live labor and SPLH ----------
   PWR shows who is on the clock right now (Current Labor Hours, or # DE + # Inside) but not labor
   hours worked so far. PWR Live counts them itself: every check it adds people-on-the-clock ×
   time since the last check, per store, from the moment you open it. If PWR does show today's
   labor hours worked, that column is used instead. */
function rateOf(d) {
  const r = colBy("labor_rate"); if (r && d[r.id] != null) return d[r.id];
  const de = colBy("drivers_on"), ins = colBy("inside_on");
  const a = de ? d[de.id] : null, b = ins ? d[ins.id] : null;
  return a == null && b == null ? null : (a || 0) + (b || 0);
}
function trackLabor(data, t) {
  const sa = colBy("sales");
  for (const [st, d] of Object.entries(data)) {
    const a = L.acc[st] || (L.acc[st] = { hours: 0, t, rate: null, sales0: null, t0: t });
    // Phone asleep or tab closed: assume the crew stayed the same, up to 2 hours.
    if (a.rate != null) a.hours += a.rate * Math.min(Math.max(0, t - a.t), 2 * 3600e3) / 3600e3;
    a.t = t;
    const r = rateOf(d); if (r != null) a.rate = r;
    if (a.sales0 == null && sa && d[sa.id] != null) { a.sales0 = d[sa.id]; a.t0 = t; }
  }
  const last = L.accLog[L.accLog.length - 1];
  if (!last || t - last.t >= 60e3) {
    L.accLog.push({ t, h: Object.fromEntries(Object.entries(L.acc).map(([s, a]) => [s, a.hours])), s: sa ? Object.fromEntries(Object.entries(data).map(([s, d]) => [s, d[sa.id] ?? null])) : {} });
    while (L.accLog.length && t - L.accLog[0].t > 5 * 3600e3) L.accLog.shift();
    save();
  }
}
const laborMode = () => colBy("labor_hours") ? "pwr" : colBy("labor_rate") || colBy("drivers_on") || colBy("inside_on") ? "tracked" : null;
const crewNow = st => { const now = latest(); if (!now) return null; const ids = st == null ? Object.keys(now.data) : [st]; let n = 0, any = false; for (const s of ids) { const r = now.data[s] ? rateOf(now.data[s]) : null; if (r != null) { n += r; any = true; } } return any ? n : null; };
const isStale = st => L.checkedAt - (L.lastChangeAt[st] || 0) >= STALE_MIN * 60e3;
function logAt(mins) {
  const target = L.checkedAt - mins * 60e3; let best = null;
  for (const e of L.accLog) { if (e.t <= target) best = e; else break; }
  if (best) return best;
  const first = L.accLog[0];
  return first && L.checkedAt - first.t >= 10 * 60e3 ? first : null;
}
// SPLH over the last 30 min: new sales ÷ labor hours used in that time.
function splhRecent(st) {
  const sa = colBy("sales"), now = latest(); if (!sa || !now) return null;
  if (laborMode() === "pwr") {
    const hr = colBy("labor_hours"), old = snapAgo(PACE_MIN); if (!old) return null;
    const pick = (s, id) => st == null ? sumCol(s, id) : s.data[st]?.[id];
    const ds = pick(now, sa.id) - pick(old, sa.id), dh = pick(now, hr.id) - pick(old, hr.id);
    if (!isFinite(ds) || !isFinite(dh) || dh < 0.25) return null;
    return { mins: Math.round((now.t - old.t) / 60e3), ds, dh, splh: ds / dh };
  }
  if (laborMode() !== "tracked") return null;
  const e = logAt(PACE_MIN); if (!e) return null;
  let ds = 0, dh = 0;
  for (const s of st == null ? Object.keys(L.acc) : [st]) {
    const s1 = now.data[s]?.[sa.id], s0 = e.s[s], h1 = L.acc[s]?.hours, h0 = e.h[s];
    if ([s1, s0, h1, h0].some(v => v == null || !isFinite(v)) || isStale(s)) continue;
    ds += s1 - s0; dh += h1 - h0;
  }
  if (dh < 0.1) return null;
  return { mins: Math.round((L.checkedAt - e.t) / 60e3), ds, dh, splh: ds / dh };
}
// SPLH since you started watching (tracked), or for the whole day when PWR shows hours worked.
function splhSince(st) {
  const sa = colBy("sales"), now = latest(); if (!sa || !now) return null;
  let ds = 0, dh = 0, t0 = Infinity;
  for (const s of st == null ? Object.keys(L.acc) : [st]) {
    const a = L.acc[s], s1 = now.data[s]?.[sa.id];
    if (!a || a.sales0 == null || s1 == null) continue;
    ds += s1 - a.sales0; dh += a.hours; t0 = Math.min(t0, a.t0);
  }
  return dh >= 0.1 ? { ds, dh, t0, splh: ds / dh } : null;
}
function splhNow(st) {
  if (laborMode() === "tracked") return splhSince(st)?.splh ?? null;
  const now = latest(), sp = colBy("splh"); if (!now || !sp) return null;
  if (st != null) return now.data[st]?.[sp.id] ?? null;
  const sa = colBy("sales"), hr = colBy("labor_hours");
  if (sa && hr) { let s = 0, hh = 0; for (const d of Object.values(now.data)) if (d[sa.id] != null && d[hr.id] > 0) { s += d[sa.id]; hh += d[hr.id]; } return hh ? s / hh : null; }
  const v = Object.values(now.data).map(d => d[sp.id]).filter(x => x != null); return v.length ? v.reduce((a, b) => a + b, 0) / v.length : null;
}
// People the current sales pace supports at the SPLH goal.
function crewSupported(st) {
  const rec = splhRecent(st); if (!rec || !splhGoal || !rec.mins) return null;
  return rec.ds / (rec.mins / 60) / splhGoal;
}

function alerts() {
  const out = [], now = latest(); if (!now) return out;
  const stores = Object.keys(now.data);
  const pc = colBy("pcya");
  if (pc) for (const st of stores) { const v = now.data[st][pc.id]; if (v != null && v < 0) out.push({ sev: "bad", text: `${st} is ${Math.abs(v).toFixed(1)}% behind last year so far today.` }); }
  const slow = [], surge = [];
  for (const st of stores) {
    const p = pace(st);
    if (p && p.dp > 0 && p.pct != null && p.pct <= -15) slow.push({ st, p });
    if (p && p.dp > 0 && p.pct != null && p.pct >= 25) surge.push({ st, p });
  }
  if (slow.length >= 3) out.push({ sev: "warn", text: `Slower than last year in the last ${slow[0].p.mins} min: ${slow.map(x => `${x.st} ${x.p.pct.toFixed(0)}%`).join(", ")}.` });
  else for (const { st, p } of slow) out.push({ sev: "warn", text: `${st} slowed down: ${money(p.ds)} in the last ${p.mins} min vs ${money(p.dp)} at this time last year (${p.pct.toFixed(0)}%).` });
  if (surge.length >= 3) out.push({ sev: "good", text: `Ahead of last year's pace in the last ${surge[0].p.mins} min: ${surge.map(x => `${x.st} +${x.p.pct.toFixed(0)}%`).join(", ")}.` });
  else for (const { st, p } of surge) out.push({ sev: "good", text: `${st} is surging: ${money(p.ds)} in the last ${p.mins} min vs ${money(p.dp)} last year (+${p.pct.toFixed(0)}%).` });
  for (const c of L.cols) {
    if (!c.key || !M[c.key]?.dir || TGT[c.key] == null || c.key === "pcya" || c.key === "splh") continue;
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
  if (splhGoal && laborMode()) {
    const low = [], high = [], dayLow = [];
    for (const st of stores) {
      const day = splhNow(st), rec = splhRecent(st);
      const sup = crewSupported(st), crew = crewNow(st);
      if (rec && rec.splh < splhGoal * 0.8) low.push(`${st} ${money(rec.splh)}` + (sup != null && crew != null ? ` (${crew.toFixed(1)} on, sales support ${sup.toFixed(1)})` : ""));
      else if (rec && rec.splh > splhGoal * 1.4) high.push(`${st} ${money(rec.splh)}`);
      if (day != null && day < splhGoal * 0.9) dayLow.push(`${st} ${money(day)}`);
    }
    const mins = (splhRecent(null) || {}).mins || PACE_MIN;
    if (dayLow.length) out.push({ sev: "bad", text: `SPLH ${laborMode() === "tracked" ? "since you started watching" : "today"} under the ${money(splhGoal)} goal: ${dayLow.join(", ")}.` });
    if (low.length) out.push({ sev: "warn", text: `More labor on than sales need right now (SPLH last ${mins} min): ${low.join(", ")}.` });
    if (high.length) out.push({ sev: "warn", text: `Possibly short-handed (SPLH last ${mins} min well above goal; watch service): ${high.join(", ")}.` });
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
  if (L.err && L.info) {
    const i = L.info;
    if (i.blocked.length) body.append(h("div", { class: "row" }, h("button", { class: "btn", type: "button", onclick: () => { try { sessionStorage.removeItem("pwrLive.framed"); } catch (e) {} location.href = i.blocked[0].src; } }, "Open report")));
    body.append(h("div", { class: "muted" }, `What PWR Live sees: ${i.tables} table${i.tables === 1 ? "" : "s"}, ${i.grids} grid${i.grids === 1 ? "" : "s"}, ${i.frames} embedded section${i.frames === 1 ? "" : "s"}` +
      (i.blocked.length ? ` (${i.blocked.length} unreadable: ${[...new Set(i.blocked.map(b => b.host))].join(", ")})` : "") + ". If the report is on screen and this still shows nothing, send a screenshot of this panel."));
  }
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

  // Live SPLH tracker.
  const spBox = h("div", null, h("h3", null, "Live SPLH (sales per labor hour)"));
  const mode = laborMode();
  const goalVs = v => splhGoal && v != null ? h("span", { class: v >= splhGoal ? "up" : "down" }, `${v >= splhGoal ? "▲" : "▼"} ${money(Math.abs(v - splhGoal))} vs goal`) : "";
  if (mode) {
    const rec = splhRecent(null), since = mode === "tracked" ? splhSince(null) : null, day = mode === "pwr" ? splhNow(null) : null;
    const crew = crewNow(null), sup = crewSupported(null);
    const grid = h("div", { class: "kpis" },
      h("div", { class: "kpi" }, h("div", { class: "l" }, `Right now (last ${rec ? rec.mins : PACE_MIN} min)`), h("div", { class: "v" }, rec ? money(rec.splh) : "…"),
        h("div", { class: "n" }, rec ? [goalVs(rec.splh), h("div", null, `${money(rec.ds)} on ${rec.dh.toFixed(1)} labor hrs`)] : "Appears after 10 min of watching.")),
      mode === "tracked"
        ? h("div", { class: "kpi" }, h("div", { class: "l" }, since ? `Since ${clock(since.t0)}` : "Since you started"), h("div", { class: "v" }, since ? money(since.splh) : "…"),
            h("div", { class: "n" }, since ? [goalVs(since.splh), h("div", null, `${money(since.ds)} on ${since.dh.toFixed(1)} labor hrs`)] : "Counting labor hours…"))
        : h("div", { class: "kpi" }, h("div", { class: "l" }, "Today so far"), h("div", { class: "v" }, day != null ? money(day) : "–"), h("div", { class: "n" }, goalVs(day))),
      crew != null ? h("div", { class: "kpi" }, h("div", { class: "l" }, "On the clock now"), h("div", { class: "v" }, (Math.round(crew * 10) / 10).toString()),
        h("div", { class: "n" }, sup != null ? h("span", { class: sup >= crew ? "up" : "down" }, `Sales pace supports ${sup.toFixed(1)} at ${money(splhGoal)}`) : (splhGoal ? "Staffing check appears after 10 min." : "Set a goal to see the staffing check."))) : null);
    spBox.append(grid);
    if (mode === "tracked") spBox.append(h("div", { class: "muted", style: "margin-top:6px" }, `PWR doesn't show hours worked, so PWR Live counts them from people on the clock every check since you opened it. Keep the panel open (minimized is fine) and it keeps counting while you move around PWR.`));
  } else spBox.append(h("div", { class: "muted" }, "Open Real Time Labor so PWR Live can see who's on the clock (Current Labor Hours, # DE, # Inside). If it still doesn't appear, pick the columns below."));
  spBox.append(h("div", { class: "row", style: "margin-top:6px" }, h("label", { class: "muted", for: "splhGoal" }, "SPLH goal $"),
    h("input", { id: "splhGoal", type: "number", inputmode: "decimal", step: "any", value: splhGoal ?? "", placeholder: "e.g. 61", style: "width:90px;border:1px solid var(--line);border-radius:8px;padding:5px 6px;background:var(--bg);color:var(--ink)",
      onchange: e => { splhGoal = e.target.value === "" ? null : +e.target.value; try { e.target.value === "" ? localStorage.removeItem("pwrLive.splhGoal") : localStorage.setItem("pwrLive.splhGoal", String(splhGoal)); } catch (x) {} render(); } })));
  const numericCols = L.cols.filter(c => !c.derived);
  const picker = (key, label) => h("div", { class: "row", style: "margin-top:6px" }, h("span", { class: "muted" }, label),
    h("select", { "aria-label": label, onchange: e => setManual(key, e.target.value) },
      h("option", { value: "" }, "— choose —"), numericCols.map(c => h("option", { value: c.id, selected: c.key === key ? "" : null }, c.label))));
  if (numericCols.length) spBox.append(picker("sales", "Sales column"), picker("labor_rate", "People on the clock column"), picker("labor_hours", "Hours worked today column (only if PWR shows one)"));
  body.append(spBox);

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
  const lm = laborMode();
  const splhCols = lm ? [{ id: "__splh", label: `SPLH last ${PACE_MIN} min` }, lm === "tracked" ? { id: "__splhs", label: "SPLH since start" } : null, lm === "tracked" && splhGoal ? { id: "__crew", label: "Crew vs sales" } : null] : [];
  const show = [...(paceCol ? [cols[0], paceCol, ...cols.slice(1)] : cols), ...splhCols].filter(Boolean);
  const stores = Object.keys(now.data);
  const val = (st, c) => c.id === "__pace" ? (pace(st)?.pct ?? null) : c.id === "__splh" ? (splhRecent(st)?.splh ?? null) : c.id === "__splhs" ? (splhSince(st)?.splh ?? null) : c.id === "__crew" ? (crewNow(st) != null && crewSupported(st) != null ? crewNow(st) - crewSupported(st) : null) : now.data[st][c.id];
  if (L.sort) stores.sort((a, b) => { const x = val(a, L.sort), y = val(b, L.sort); if (x == null) return 1; if (y == null) return -1; return (x - y) * L.sortDir; });
  else stores.sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  const sortBy = id => { if (L.sort === id) L.sortDir *= -1; else { L.sort = id; L.sortDir = -1; } render(); };
  const tbl = h("table", null,
    h("thead", null, h("tr", null, h("th", { tabindex: "0", onclick: () => { L.sort = null; render(); } }, "Store"),
      show.map(c => { const age = !c.derived && L.colSeen[c.id] ? Math.round((L.checkedAt - L.colSeen[c.id]) / 60e3) : 0;
        return h("th", { tabindex: "0", title: age ? `From another PWR tab, last seen ${age} min ago` : null, onclick: () => sortBy(c.id), onkeydown: e => { if (e.key === "Enter") sortBy(c.id); } }, c.label + (age >= 2 ? ` · ${age}m ago` : "") + (L.sort === c.id ? (L.sortDir > 0 ? " ↑" : " ↓") : "")); }))),
    h("tbody", null, stores.map(st => h("tr", null,
      h("td", null, h("span", { class: "dot", style: `background:${colorFor(st)}` }), st),
      show.map(c => {
        if (c.id === "__pace") { if (now.t - (L.lastChangeAt[st] || now.t) >= STALE_MIN * 60e3) return h("td", { class: "warn" }, "no data"); const p = pace(st); return h("td", { class: p && p.pct != null ? (p.pct <= -15 ? "bad" : p.pct >= 0 ? "good" : "") : "" }, p ? (p.pct != null ? `${p.pct >= 0 ? "+" : ""}${p.pct.toFixed(0)}%` : money(p.ds)) : "…"); }
        const goalCls = v => v == null || !splhGoal ? "" : v >= splhGoal ? "good" : v < splhGoal * 0.8 ? "bad" : "warn";
        if (c.id === "__splhs") { const s = splhSince(st); return h("td", { class: goalCls(s && s.splh) }, s ? money(s.splh) : "…"); }
        if (c.id === "__crew") { const n = crewNow(st), sp = crewSupported(st); if (n == null || sp == null) return h("td", null, "…"); const d = n - sp; return h("td", { class: Math.abs(d) < 0.5 ? "good" : d > 0 ? "bad" : "warn", title: `${n.toFixed(1)} on the clock; current sales support ${sp.toFixed(1)} at the goal` }, Math.abs(d) < 0.5 ? "on target" : d > 0 ? `${d.toFixed(1)} over` : `${(-d).toFixed(1)} short`); }
        if (c.id === "__splh") { const s = splhRecent(st); return h("td", { class: s && splhGoal ? (s.splh >= splhGoal ? "good" : s.splh < splhGoal * 0.8 ? "bad" : "warn") : "" }, s ? money(s.splh) : "…"); }
        const v = now.data[st][c.id];
        const stt = c.key === "pcya" ? (v == null ? null : v >= 0 ? "good" : "bad") : c.key === "splh" ? (v == null || !splhGoal ? null : v >= splhGoal ? "good" : v >= splhGoal * 0.9 ? "warn" : "bad") : c.key ? status(c.key, v) : null;
        const shown = !c.derived && L.raw[st] && L.raw[st][c.id] ? L.raw[st][c.id] : fmtCol(c, v);
        return h("td", { class: stt || "" }, shown);
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
    h("button", { class: "btn", type: "button", onclick: () => { L.snaps = []; L.cols = []; L.colSeen = {}; L.lastChangeAt = {}; L.acc = {}; L.accLog = []; save(); tick(); } }, "Restart history"));
  body.append(tools);
  if (L.copied) body.append(h("div", { class: "muted" }, L.copied));
  body.append(h("div", { class: "muted" }, "Columns read: " + (L.cols.filter(c => !c.derived).map(c => c.label + (c.key ? ` (${M[c.key] ? M[c.key].label : c.key})` : "")).join(", ") || "none yet")));
  body.append(h("div", { class: "muted" }, "Numbers update when the PWR page updates. Visit other PWR tabs (Labor, Service & Labor Summary…) and their numbers are added to the board. Columns from a tab you're not on show how long ago they were read."));
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
