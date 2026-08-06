# tradelog

A self-hosted, multi-user, multi-profile **crypto-futures trading journal**. It syncs your **closed
positions** over REST from **Binance**, **Bybit**, **OKX**, **Bitget**, **Bitunix**, **BingX**,
**BitMart**, **Kraken Futures**, **Gate.io**, **MEXC** and **KuCoin Futures**, imports **Quantfury**
from its exported **PDF** "Trading History Report" and anything else from a **canonical Journal CSV**,
organises everything by **profiles** (personal trading and bot strategies), and lets you annotate
positions to analyse how you trade.

By **Sephilabs**. Licensed under **AGPL-3.0-only** (see [LICENSE](LICENSE)) — its network clause means
anyone running a modified version as a network service must publish the source.

> Infrastructure, auth, and tech stack mirror the sibling **shared-ledger** project; this is a
> separate, independent app and database.

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Tech stack](#tech-stack)
- [Running it](#running-it)
- [Connecting your accounts](#connecting-your-accounts)
  - [Exchanges (API)](#exchanges-api)
  - [Quantfury (PDF import)](#quantfury-pdf-import)
  - [Journal CSV import](#journal-csv-import)
- [Development](#development)
- [Status & caveats](#status--caveats)

## Features

- **Profiles** (`PERSONAL` / `BOT`), each owning its own data sources (1:N). Profiles are private to
  one user and never shared. Each bot strategy lives on its own sub-account / API key.
- **Thirteen sources behind one connector abstraction** — adding a source is a new module, not a core
  change. Every exchange falls into one of two shapes, and each shape's hard-won logic lives in one
  shared base class rather than in each connector:
  - **Closed-position venues** — the exchange serves finished positions with realized PnL, so each row
    maps 1:1 and every figure comes from the venue: **Bitunix** (full history since the account was
    opened), **OKX**, **Bitget** (Unified Trading Account), **Gate.io** (PnL arrives already split into
    trading result, funding and fees — the cleanest payload of the lot), **MEXC**, **KuCoin Futures**.
  - **Fill-reconstruction venues** — the exchange exposes only fills, which are pulled over time
    windows and folded into flat-to-flat positions: **Binance** (funding included), **Bybit** (funding
    included, ~2 years deep), **BingX**, **BitMart**, **Kraken Futures**.
  - **Quantfury** — parses the exported **PDF**; no public API.
  - **Journal CSV** — manual import in tradelog's canonical format, for hand-kept journals or dead
    exchanges.
- **Read-only API keys only**, everywhere. **OKX**, **Bitget** and **KuCoin Futures** also need the API
  **passphrase** you chose when creating the key; it is encrypted at rest exactly like the secret and
  never sent back to the browser. The connection form asks for it only for those three.
- **Kraken Futures is treated as its own venue**, not as "Kraken" — it is a separate platform with its
  own API domain, authentication and account, so a Kraken *spot* history imported by CSV keeps its own
  balance and ROI instead of merging into the futures one.
- **Canonical positions** are *flat-to-flat*: from net exposure leaving zero until it returns to zero
  is one position — scaling in/out within that lifecycle stays a single position.
- **Realized PnL, fees and funding are stored separately and summably** (USDT for now), never
  consolidated — leaving room for future currency conversion and breakdowns.
- **Incremental, idempotent sync** keyed by `(data source, external id)`; overlapping windows never
  duplicate, and the first connect backfills as far back as each source allows (Bitunix from account
  opening, **BingX only ~30 days**, Quantfury whatever the PDF holds — see
  [How far back each source goes](#how-far-back-each-source-goes)). Triggers:
  automatic **on login** (async, rate-limited per exchange), a **nightly scheduled sweep** of every
  active API source (default 04:00, `SYNC_SCHEDULE_CRON`), **manual** (per source or all), and the
  **Quantfury PDF upload**.
- **Annotations & management**: a customizable per-user **tag taxonomy** (seeded with an *Origen* group you edit), free-text notes per position, and full position leg operations viewable. Supports **bulk tagging** (apply Origen tags to selected trades or all matching search filters) and **soft-deletion** (single or bulk delete/hide trades).
- **Capital history & real ROI**: a dedicated **Capital** page tracks your trading capital as a
  dated history of **adjustments** (anchors: your real balance per exchange on a date) plus
  **automatic snapshots** carried forward from the latest anchor with the net PnL of trades closed
  since. An adjustment is a **hard cut** — PnL closed before it is considered settled into that
  balance — so deposits/withdrawals never read as trading gains. Snapshots are materialized by a background job
  (**daily / weekly / monthly**, set in Settings; `CAPITAL_SNAPSHOT_ENABLED` / `CAPITAL_SNAPSHOT_CRON`)
  and are editable to backfill history — **manual values always win**, editing a day turns it into
  an anchor, and individual snapshot days can be deleted or reset. Snapshot backfills can also be
  triggered on demand without waiting for the scheduled job. All day boundaries use the **user's time zone**.
  The first adjustment is the starting point: ROI and the capital chart stay empty until it exists.
- **Trading capital & risk**: the dashboard block shows the **estimated current capital** (per
  exchange and total, derived from the capital history) and the **maximum to lose per trade** at two
  configurable **risk percentages**. It follows the Exchange filter but ignores Period/Origen.
- **ROI card, capital evolution & Monthly ROI**: the Statistics panel gains a **ROI** for the selected
  period — net PnL of the period's trades (counted from the most recent anchor) ÷ capital at the
  period's first day (that day's snapshot or adjustment); blank when no snapshot/adjustment exists at
  or before the period start. A **Monthly ROI** widget (placed last on the dashboard and in performance view)
  displays a yearly bar chart (`‹ year ›` navigation) plotting ROI per month using the same same-month capital basis rule,
  showing gaps for months without starting capital data. A **stacked area chart** plots the snapshotted capital per exchange
  with anchor days overlaid as markers — capital over time (including your adjustments),
  deliberately not the same thing as the Cumulative profit (PnL) chart. ROI widgets react to the Exchange filter
  (and ignore Origen and Period filters).
- **Read-only, encrypted credentials**: API keys are AES-GCM encrypted at rest and decrypted only in
  the sync worker — never returned to the browser. Use **read-only keys** (no trading, no withdrawal);
  the app warns on permission errors but cannot enforce this on the exchange.
- **Backup & restore**: one-click **JSON export** of everything the authenticated user owns —
  including the capital history and risk settings, but never secrets — and a matching **import** that
  replaces the account's data, guarded by an explicit confirmation so a stray request can't wipe
  anything. Exports from older app versions import fine (the importer only refuses files newer than
  itself).
- **UI & Analytics**: Spanish/English (persisted per user), light/dark/system theme, responsive on desktop and
  mobile. Every widget features a **Maximize** button (`⤢`) to open a high-resolution fullscreen modal overlay with auto-resizing charts and full detail view. An analytics dashboard with a shared **Period / Exchange / Origen** filter bar across 7 dedicated view tabs:
  - **Summary**: KPIs and equity curve chart.
  - **Performance**: Activity breakdown, daily PnL, and monthly summary.
  - **Behavior**: Win rate by hour of day (0–23) and weekday, long vs. short direction performance, and trader style breakdown (scalper/day/swing with duration metrics).
  - **Streaks**: Winning & losing streak stats (average & longest length/PnL), recovery rate after losses, and a monthly PnL calendar.
  - **Pairs**: Rankings for most traded, most profitable, and least profitable pairs.
  - **Fees**: Daily fee breakdowns, cumulative fees chart, and return-on-fees efficiency ratio.
  - **Capital**: Trading capital risk calculator, capital evolution stacked area chart, and Monthly ROI.
  Settings clearly shows the active **time zone**, which governs day boundaries and ROI periods everywhere.

## How it works

Each connector maps its own naming and payloads to a single canonical `PositionRecord`
(`BASE/QUOTE` symbol, side, open/close times, quantity, entry/exit price, and separate
PnL/fees/funding). The sync engine upserts those records idempotently and advances a per-source
cursor.

- **Bitunix** returns already-aggregated closed positions, so realized PnL, fees and funding come
  straight from the exchange.
- **BingX** exposes only raw fills, which a shared reconstructor folds into flat-to-flat positions.
  Because BingX's fill endpoint reports **no per-fill PnL**, realized PnL is computed from the leg
  prices (gross of fees, which are tracked separately), and quantity is derived from notional ÷ price.
- **BitMart** also exposes only fills, folded by the same reconstructor — but its fills carry
  `realised_profit` and `paid_fees`, so PnL/fees come straight from the payload. Fill volume is in
  contracts; the displayed quantity is scaled by the contract size from BitMart's public details
  endpoint.
- **Binance** has no closed-position endpoint, and its fill endpoint *requires* a symbol — so the
  income endpoint (which takes none) is read first to learn which symbols actually traded in the
  window, and only those are fetched. The same response carries the funding rows, which are folded in
  as money-only fills, so Binance funding is real rather than zero.
- **Bybit** could use its closed-PnL endpoint, but that returns one row per closing *order* rather than
  per position, and rows cannot be recombined reliably once a position is scaled into between two
  partial closes. Fills give the correct flat-to-flat lifecycle instead, and funding arrives in the
  same execution stream.
- **OKX, Bitget, Gate.io, MEXC and KuCoin Futures** all serve finished positions. Each reports the money
  its own way — some give gross PnL with negative fee components, some give the net figure and leave the
  gross to be backed out — so every connector's mapping is asserted by deriving net back to the
  exchange's own published net for a sample payload. Gate.io needs the least work of any source: its
  payload already splits PnL into trading result, funding and fees, exactly as tradelog stores them.
- **Kraken Futures** is its own platform with its own signing scheme. Only linear perpetuals (`PF_`) are
  imported, and no fee is recorded at all — Kraken documents that the fee values these endpoints return
  no longer reflect what was charged, and `realized_pnl` is null on any paged request, so PnL comes from
  the leg prices instead.
- **Quantfury** PnL is likewise computed from the leg prices (its prices already include the spread
  and it charges no commission, so fees and funding are zero) and validated to match Quantfury's own
  printed totals.

## Tech stack

- **Backend**: Kotlin 2.4 · Spring Boot 4.1 (web, security, data-jpa, session-jdbc, actuator) ·
  Java 25 · PostgreSQL + Flyway · Argon2 · Bucket4j · Apache PDFBox · Micrometer/Prometheus.
- **Frontend**: React 19 · Vite 8 · TypeScript 7 · Tailwind 4 · TanStack Query · react-router 7 ·
  i18next · Recharts.
- **Infra**: Docker Compose; an externally-managed Postgres on a shared Docker network; the frontend
  is served by nginx (SPA + `/api` proxy) behind an external reverse proxy.

## Running it

Prerequisites: Docker, and a PostgreSQL reachable on the shared Docker network (default
`all_dockers`) with a database + user already created for tradelog. The migrations run
`CREATE EXTENSION IF NOT EXISTS pgcrypto`, so the tradelog role needs permission to create
extensions (or pre-create the extension as a superuser).

```bash
cp .env.example .env
```

Edit `.env`:

- set `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`,
- keep `REGISTRATION_MODE=open` so the first user can self-register,
- generate an encryption key for stored API credentials:
  `openssl rand -base64 32` → `TRADELOG_CRYPTO_KEY` (keep it **stable** — rotating it makes
  previously stored credentials undecryptable),
- for local plain-HTTP testing set `APP_COOKIE_SECURE=false`.

```bash
docker compose up --build
```

- Frontend: <http://localhost:8088> (override with `FRONTEND_PORT`)
- Backend API is proxied under `/api`; management endpoints (health/Prometheus) are on port `9090`.

Register the first account, create a profile, then add data sources. To seed an admin on first run
instead of self-registering, set `ADMIN_EMAIL` / `ADMIN_PASSWORD` and use a non-`open` registration
mode.

## Connecting your accounts

Open **Settings → Data sources** for the active profile and add a source.

### How far back each source goes

History coverage differs **per source** — it's a limit of each platform, not of tradelog:

| Source | History available | How |
| --- | --- | --- |
| **Bitunix** | Full — since you opened the account | Signed REST; closed positions, backfilled on first sync |
| **Bybit** | ~2 years | Closed positions are per closing *order*, so fills are used instead and folded flat-to-flat; funding included |
| **OKX** | **Last 90 days** | Closed positions with PnL, fees and funding all reported separately |
| **Bitget** | **Last 90 days** (30 per request) | Closed positions, Unified Trading Account API (v3) |
| **Binance** | **~3 months** | No closed-position API at all: fills are reconstructed, and since fills require a symbol, the income endpoint reveals which symbols traded — that endpoint's 3-month retention is the real limit, not the 6 months of fills. Funding included |
| **Gate.io** | As far as Gate retains | Closed positions, already flat-to-flat, with PnL pre-split into trading result / funding / fees |
| **MEXC** | As far as MEXC retains | Closed positions with PnL and fees. **MEXC restricts futures API access**, so a new key may simply be refused |
| **KuCoin Futures** | **Last 3 months** (7 per request) | Closed positions. Quantity is *derived* from the PnL and prices — this endpoint reports no size |
| **Kraken Futures** | As far as Kraken retains | Its own platform and auth. Linear perpetuals (`PF_`) only. **Fees are not imported** — Kraken states its API fee values no longer reflect what was charged |
| **BingX** | **Only ~the last 30 days** | The `allFillOrders` API serves no older fills; older trades can't be retrieved |
| **BitMart** | As far as its fill API retains — **the exchange closes on 2026-08-26** | Keyed REST; fills pulled in 7-day windows until the history runs dry |
| **Quantfury** | Full — whatever is in the exported PDF (typically your entire history) | Manual PDF import |
| **Journal CSV** | Whatever you import | Manual CSV in the canonical format — for hand-kept journals or exchanges that no longer exist |

**BitMart is shutting down on 2026-08-26.** Sync it one last time and withdraw your funds before
then; once the API is gone, any trade left there can no longer be imported. tradelog handles the
closure as a **freeze, never a deletion**: on that date the data source moves itself to `DISABLED` so
the nightly sweep stops calling a dead host, and everything already imported stays put — positions,
PnL, fees, capital snapshots and adjustments all keep counting in every analytic for the period they
cover. Record the withdrawal the ordinary way, as a capital **adjustment to 0** (an anchor, so it
reads as money leaving rather than as a trading loss); the BitMart column then simply carries 0
onward. A venue sitting at 0 with nothing left feeding it drops out of the dashboard's *Trading
capital & risk* block, but stays in the Exchange filter, on the Capital page and in every historical
view.

So for **BingX**, sync it regularly — anything older than ~30 days is gone for good once it ages out of the API. For full long-term history on a platform that doesn't expose it via API, a Quantfury-style PDF/export import is the only path. For **dead exchanges** with no export at all, convert whatever records you kept into the **Journal CSV** format (see [Journal CSV import](#journal-csv-import)) — the in-app format reference is written so any AI assistant can do the conversion for you.

### Exchanges (API)

The same three steps for every exchange — Binance, Bybit, OKX, Bitget, Bitunix, BingX, BitMart,
Kraken Futures, Gate.io, MEXC and KuCoin Futures:

1. On the exchange, create an API key with **read-only** permissions — **no trading and no
   withdrawal** scope. (tradelog never needs more; a key with write scope is flagged.)
2. In tradelog, add a data source of that kind, give it a label (e.g. the sub-account or strategy
   name), and paste the **API key** and **API secret**. Three exchanges need a third field:
   **OKX**, **Bitget** and **KuCoin Futures** also ask for the **API passphrase** you chose when
   creating the key — the form shows that input only for them. It is encrypted at rest like the
   secret and never shown again.
3. The source syncs automatically on your next login, or immediately via **Sync now**. The first sync
   backfills as much history as that exchange's API allows, which varies a great deal — see
   [How far back each source goes](#how-far-back-each-source-goes). If the credentials are rejected or
   lack read permission, the source moves to an **Error** state with an actionable message.

Per-exchange notes worth knowing before you connect:

- **Bitget** uses the **Unified Trading Account** (v3) API. A UTA key cannot call the older classic
  endpoints and vice versa, so a classic-account key will be refused — that is Bitget's own account
  split, not a tradelog setting.
- **Kraken Futures** is a separate platform from Kraken spot, with its own API key page. Only linear
  perpetuals (`PF_`) are imported, and **fees are not** — Kraken documents that the fee values its API
  returns no longer reflect the fees actually charged, so importing none beats importing a wrong one.
- **MEXC** restricts futures API access, so a newly created key may be refused outright. A permissions
  error there is that restriction rather than a misconfiguration.
- **KuCoin Futures** reports no position size on its closed-position endpoint, so quantity is derived
  from the PnL and the entry/exit prices — exact for a linear perpetual, except on a trade that opened
  and closed at the same price, which keeps a zero quantity. Its PnL, fees and funding are exact.
- **BitMart**'s private reads are keyed, not signed, so only the API key is actually used — and it
  **closes on 2026-08-26** (see above).

### Quantfury (PDF import)

Quantfury has no public API, so you import its official **Trading History Report** PDF. To download it:

1. Open Quantfury and go to **Trading History** — in the **side menu** of the mobile app, or the
   **main menu** of the [web platform](https://trading.quantfury.com/trading_history).
2. Open the **Closed Positions** tab.
3. *(Optional)* Tap the **filter icon** (top right) to filter by asset type or change the sort order.
   Importing is idempotent, so the filter you choose doesn't matter — you can export everything.
4. At the **bottom of the page**, tap the **PDF** icon to download the report.

Then in tradelog:

1. Add (or open) a data source of kind **Quantfury**.
2. Use its upload card: choose the downloaded PDF and press **Preview** to see how many closed
   positions were found, the date range, and the total realized PnL.
3. Press **Import** to save them.

Re-uploading a newer export is safe — positions are de-duplicated by a deterministic key, so existing
ones are skipped and only new closes are added. Quantfury's realized PnL is recomputed from the leg
prices (no separate fees/funding), and tradelog cross-checks the aggregate against Quantfury's own
printed totals.

> Steps follow Quantfury's [Trading History help article](https://help.quantfury.com/en/articles/5448773-trading-history)
> and may change with app versions.

### Journal CSV import

For trades that live nowhere else — a hand-kept spreadsheet, an exchange that has since shut down — you
import a CSV in tradelog's **canonical closed-position format**. Each row is one flat-to-flat closed
position, so no reconstruction is needed.

1. Add (or open) a data source of kind **Journal CSV**.
2. Expand **Show CSV format reference** on its upload card. It documents the dialect (UTF-8, `;`
   separator, `,`/`.` decimals, ISO dates), every column, and a worked example.
3. The reference includes an **AI hint**: paste the whole reference into any AI assistant together with
   your old records (a screenshot, a PDF statement, a messy spreadsheet) and ask it to emit a CSV in
   exactly that format. tradelog never parses your original mess — only the clean canonical CSV.
4. **Preview** validates the file and surfaces non-fatal warnings (rows whose close date precedes the
   open date; rows that look like positions already imported from another source — which would
   double-count PnL). Then **Import**.

The canonical header is:

```
symbol;side;opened_at;closed_at;quantity;entry_price;exit_price;realized_pnl;fees;funding;exchange;note
```

Only `symbol`, `side`, `opened_at`, `closed_at`, `entry_price` and `exit_price` are required. Realized
PnL is the supplied `realized_pnl` (gross, before fees) when present, otherwise computed from the leg
prices `(exit − entry) × qty`, negated for shorts — the same convention the other connectors use. If
you only have position totals and no per-unit price, set `quantity=1` and put the total invested /
returned in `entry_price` / `exit_price`. The optional `exchange` column records the venue the trade
happened on (e.g. `FTX`); when blank, the data source's label is used — so naming the source after the
venue and omitting the column works for a single-venue file. Every position carries this **exchange**
(for the live connectors it's simply the venue's own name), and the Positions page lets you
filter by it. Re-importing the same file is idempotent (positions are keyed by a deterministic hash); each
Journal CSV source has its own id namespace, so its rows can never collide with positions synced from
an exchange.

## Development

The backend builds with the standard Gradle wrapper (a JDK 25 locally), or via the Gradle Docker
image with no local toolchain:

```bash
cd backend && ./gradlew build          # compile + test + bootJar (local JDK)
docker build ./backend                 # compile + bootJar (no local toolchain)
```

**Tests.** Unit tests need nothing extra; the **integration tests use Testcontainers**, so the test
JVM needs access to a Docker daemon. Run with the host Docker socket mounted (and a host override so
Testcontainers' mapped ports are reachable from inside the build container):

```bash
docker run --rm \
  -v "$PWD/backend:/workspace" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -w /workspace gradle:9.6.1-jdk25 gradle --no-daemon test
```

On a host with a local Gradle + Docker, plain `gradle test` works directly. Integration tests
(`*IntegrationTest`, `*LifecycleTest`, `ApplicationContextTest`, …) spin up a real PostgreSQL via
Testcontainers and exercise the full Spring context, Flyway schema, JDBC sessions, the
profile-ownership interceptor, credential encryption, idempotent position upsert, the taxonomy, the
capital-history engine (anchors, carry-forward, snapshot cadence, ROI), and the Quantfury PDF import.

Frontend (Node 26):

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173, proxies /api -> http://localhost:8080
npm run build
npm run test
```

To validate the Quantfury parser against a real export, point an opt-in test at the file:

```bash
TRADELOG_REAL_PDF=/path/to/report.pdf \
  gradle --no-daemon test --tests "*RealPdfSmokeTest*"
```

(The test skips when the variable isn't set, so no personal data is committed.)

## Status & caveats

- **Bitunix** and **BingX** have been verified against real accounts: synced quantities, realized PnL,
  fees and funding match each exchange's own UI. Note the **history limits** above — **BingX only
  serves ~the last 30 days** of fills via its API, so older BingX trades cannot be backfilled. BingX
  realized PnL is computed from leg prices (gross; fees tracked separately) since its fill endpoint
  reports none; Bitunix reports its own PnL/fees/funding directly.
- The **BitMart** connector follows the same fill-reconstruction path as BingX (PnL/fees taken from
  the fill payload, mapping verified against BitMart's official SDK); history depth depends on how
  far back its fill API still serves data. BitMart **closes on 2026-08-26** — see above.
- The **eight newer exchanges** (Binance, Bybit, OKX, Bitget, Kraken Futures, Gate.io, MEXC, KuCoin
  Futures) were built against each venue's current official API documentation, with the endpoint,
  pagination model, symbol format and money semantics verified per exchange before any code was
  written. Their field mappings are unit-tested against representative payloads — each one asserting
  that net PnL derives back to the figure that exchange itself publishes — but they have **not yet been
  reconciled against live accounts** the way Bitunix and BingX have. Check the first sync's totals
  against the exchange's own UI before trusting them, and note the per-exchange caveats already listed:
  MEXC may refuse a new key outright, Kraken Futures imports no fees, and KuCoin Futures derives
  quantity from PnL and prices.
- The **Quantfury** parser is validated end-to-end against a real report (computed vs printed PnL agree
  to rounding). Re-uploading the same export is safe.
- **API keys must be read-only.** The app detects and warns on permission errors but cannot enforce
  key scope on the exchange — that's on you when creating the key.
- Out of scope (Phase 1): open/unrealized positions, currency conversion, and profiles shared between users. (Fine-grained analytics like win rate, expectancy, average R:R, hourly/weekday distribution, trader style, streaks, and fee efficiency are fully built and live in the dashboard.)
