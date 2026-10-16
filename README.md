# PWR Analyzer

A single-page tool for digging into Domino's store PWR (performance) reports. Open `index.html` in a browser, then import your export. Everything runs locally in the browser; no data is uploaded.

## What it does

- **Imports** CSV, TSV, or Excel (`.xlsx`/`.xls`) exports, or a table pasted straight from Excel/Sheets. Title rows above the header and "Total" rows are skipped automatically.
- **Matches columns** by name (e.g. `ADT`, `Avg Delivery Time`, `Royalty Sales`, `Ticket Count`, `Actual Labor %`). Anything it misses can be mapped by hand under *Column mapping*.
- **Reads report formats**: `24:35` times become minutes:seconds, and `$`, `%`, commas and `(negatives)` are handled.
- **KPI tiles** for every metric found, each with status against target, a sparkline, and change vs the prior day/week/month.
- **Trend chart** combined or per store, with the target line and a hover readout.
- **Day-of-week pattern** when the data is daily.
- **Store scorecard**: every store against every target, sortable, with "targets hit" counts.
- **What stands out**: automatic findings such as metrics off target, stores trending worse than the rest, the operational metric most tied to delivery time (load, rack, make time…), and unusual days.
- **Targets** are editable and saved in your browser.
- **Copy summary** puts a plain-text recap (with a store table) on the clipboard for email or chat.

## Metrics recognized

Net sales, orders, avg ticket, deliveries, delivery mix, avg delivery time (ADT), load time, make time, rack time, on-time delivery %, deliveries per driver hour, labor %, food cost %, food variance, remakes, and OSAT. Avg ticket and delivery mix are calculated when the report has sales, orders and deliveries but not those columns.

Default targets (ADT ≤ 25:00, load ≤ 13:00, make ≤ 1:30, rack ≤ 4:00, OTD ≥ 85%, labor ≤ 25%, food ≤ 28%, …) are starting points. Set them to your own goals under *Targets*.

## Files

- `index.html` – the app (no build step).
- `sample-pwr.csv` – example data in the expected shape (made-up stores and figures).

## Pull data straight from the report website (view-only)

The `connector/` folder is a small program you run on your own computer. It opens the report website in a normal browser window, you sign in there yourself, and the analyzer reads the report from that window.

**First time setup** (needs [Node.js](https://nodejs.org) 18 or newer):

```
cd connector
npm run setup
```

**Each time:**

```
cd connector
node server.js --url=https://<your report site address>
```

Then open http://localhost:8787. A **Live site** panel appears:

1. **Open site**: a browser window opens. Sign in there (including any 2-step code).
2. Go to the PWR report and pick the stores and dates.
3. **Read report from page** pulls the table you're looking at into the analyzer. If the page has several tables, pick the right one from the list. Or click the site's own Export button and then **Load latest export**.

Your sign-in is kept in `connector/.browser-profile` on your computer so you don't have to log in every time. Delete that folder to sign out completely. It is never committed (see `.gitignore`).

### How it stays view-only

- The connector never clicks, types, or submits anything on the site. It only reads the text of tables on the page you have open, and files you export yourself.
- The **view-only lock** (on by default) blocks every request from that browser window that could change data: form submissions, saves, edits, deletes (POST/PUT/PATCH/DELETE). Sign-in requests are the only exception. Blocked requests are listed in the Live site panel.
- If a report page won't load while locked (some sites use a "submit" request just to run a report), turn the lock off in the panel, load the report, and turn it back on. Use `--allow=<text in the URL>` to always allow one specific request instead.
- The connector only accepts connections from your own computer, and only from the analyzer page it serves.

Check your company's policy on automated tools before using this with a work account.
