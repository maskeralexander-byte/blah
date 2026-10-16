# PWR Analyzer

Live analysis for Domino's PWR (pwr.dominos.com). The main tool is **PWR Live**, a bookmark that runs on the PWR sections you already use (Real Time, Keys, Service & Labor…); see below. `index.html` sets it up and can also analyze a table pasted or opened from a file. Everything runs locally in the browser; no data is uploaded.

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

## PWR Live: real-time analysis on pwr.dominos.com (no install)

PWR Live is a bookmark. Tap it while you're on a PWR report page (for example Real Time Sales) and the analyzer opens as a panel on top of the page. It works on that page because it runs there, in your own signed-in browser. Nothing is installed and no password is involved.

What it shows, refreshed every 5 to 60 seconds:

- **Sales so far** for the stores on screen, and change vs last year.
- **Projected end of day**: today's sales × (last year's full day ÷ last year at this time).
- **Last 30 min vs last year**, for the group and for each store, so you can see who is slowing down right now.
- **Needs attention**: stores behind last year, stores whose last-30-minute pace is 15%+ under last year, metrics past your targets (for example ADT), times rising fast, and stores that stopped reporting for 20+ minutes.
- A **store table** you can sort by tapping a column, and a **chart** of any column since you started watching.
- **Live SPLH** (sales per labor hour) from PWR's **Real Time Hourly Sales** tab: each hour's sales ÷ (average # DE + # Inside × hours in the block; the current hour counts only the minutes so far). Shows today so far, this hour, and an hour-by-hour table against your SPLH goal. Open Hourly Sales for each store you want. On Real Time Labor (no hourly table), PWR Live falls back to counting labor hours from people on the clock while it's open, with a staffing check against the goal.
- **Every PWR tab you visit adds to the board.** It reads tables (Real Time Sales, Real Time Labor, …) and tile screens (Service & Labor Summary: sales, PCYA, order count, Average Load, cDOT %, extreme deliveries, …) and merges them by store. Columns from a tab you're not on show how long ago they were read.
- If the report sits in an embedded section from another address that the browser won't let it read, the panel says so and offers **Open report** to load that section by itself.
- **Copy table for the analyzer** copies the current table so you can paste it into the full analyzer.

**It stays open while you use PWR.** Tap it on any PWR page, even the sign-in page. The tab then shows PWR in a full-screen frame under the panel, so signing in, switching reports and page reloads don't close it. PWR keeps full control of its own pages: if one ever leaves the frame, it loads normally and the next tap opens the panel on that page instead.

It's read-only: it reads the text of the table on screen and never clicks, types, submits, or sends anything. The history it builds while you watch stays in that browser tab (session storage), so it survives a page refresh but nothing is sent anywhere. Tap the bookmark again after a full page reload.

**Set it up** from the PWR Live section in the analyzer (index.html): copy the bookmark code and save it as a bookmark named "PWR Live". On Android Chrome, open it by typing "PWR Live" in the address bar while on the PWR page and tapping the bookmark.

Check your company's policy before using browser bookmarks like this on work systems.

## Development

- `live/pwr-live.js` is the bookmark's source. It shares the metric engine with `index.html` (the `/*ENGINE:BEGIN*/ … /*ENGINE:END*/` blocks).
- After editing either, rebuild the bookmark that's embedded in `index.html`:

```
npm i --no-save terser   # optional, makes the bookmark smaller
node tools/build.js
```

The bookmark loads `live/pwr-live.min.js` from jsDelivr at this branch, so pushing a new build updates everyone's bookmark. jsDelivr caches branch files for a while; after pushing, refresh it with the `curl https://purge.jsdelivr.net/...` command that `tools/build.js` prints.
