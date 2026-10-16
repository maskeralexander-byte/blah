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
