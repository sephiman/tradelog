# tradelog

A self-hosted, multi-user, multi-profile **crypto-futures trading journal**. It syncs your **closed
positions** from **Bitunix** and **BingX** (signed REST APIs) and imports **Quantfury** from its
exported **PDF** "Trading History Report", organises everything by **profiles** (personal trading and
bot strategies), and lets you annotate positions to analyse how you trade.

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
  - [Bitunix / BingX (API)](#bitunix--bingx-api)
  - [Quantfury (PDF import)](#quantfury-pdf-import)
  - [Journal CSV import](#journal-csv-import)
- [Development](#development)
- [Status & caveats](#status--caveats)

## Features

- **Profiles** (`PERSONAL` / `BOT`), each owning its own data sources (1:N). Profiles are private to
  one user and never shared. Each bot strategy lives on its own sub-account / API key.
- **Three sources behind one connector abstraction** — adding a source is a new module, not a core
  change:
  - **Bitunix** — pulls already-closed positions (with realized PnL) via signed REST.
  - **BingX** — pulls fills and **reconstructs** flat-to-flat positions (no clean closed-position API).
  - **Quantfury** — parses the exported **PDF**; no public API.
- **Canonical positions** are *flat-to-flat*: from net exposure leaving zero until it returns to zero
  is one position — scaling in/out within that lifecycle stays a single position.
- **Realized PnL, fees and funding are stored separately and summably** (USDT for now), never
  consolidated — leaving room for future currency conversion and breakdowns.
- **Incremental, idempotent sync** keyed by `(data source, external id)`; overlapping windows never
  duplicate, and the first connect backfills as far back as each source allows (Bitunix from account
  opening, **BingX only ~30 days**, Quantfury whatever the PDF holds — see
  [How far back each source goes](#how-far-back-each-source-goes)). Triggers:
  automatic **on login** (async, rate-limited per exchange), **manual** (per source or all), and the
  **Quantfury PDF upload**.
- **Annotations**: a customizable per-user **tag taxonomy** (seeded with an *Origen* group you edit)
  plus a free-text note per position, with the operations (legs) of each position viewable.
- **Trading capital & risk**: record your **current capital per exchange** (USDT, entered manually)
  and two configurable **risk percentages**; the dashboard shows total and per-exchange capital plus
  the **maximum to lose per trade** at each risk %. It's a current balance — it follows the Exchange
  filter but ignores Period/Origen. Structured so balances can later be fetched from the exchange API.
- **Read-only, encrypted credentials**: API keys are AES-GCM encrypted at rest and decrypted only in
  the sync worker — never returned to the browser. Use **read-only keys** (no trading, no withdrawal);
  the app warns on permission errors but cannot enforce this on the exchange.
- **UI**: Spanish/English (persisted per user), light/dark/system theme, responsive on desktop and
  mobile. An analytics dashboard with a shared **Period / Exchange / Origen** filter bar, plus the
  trading-capital & risk block above.

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
- **Quantfury** PnL is likewise computed from the leg prices (its prices already include the spread
  and it charges no commission, so fees and funding are zero) and validated to match Quantfury's own
  printed totals.

## Tech stack

- **Backend**: Kotlin 2.2 · Spring Boot 4 (web, security, data-jpa, session-jdbc, actuator) · Java 24 ·
  PostgreSQL + Flyway · Argon2 · Bucket4j · Apache PDFBox · Micrometer/Prometheus.
- **Frontend**: React 19 · Vite 7 · TypeScript · Tailwind 4 · TanStack Query · react-router 7 ·
  i18next · Recharts.
- **Infra**: Docker Compose; an externally-managed Postgres on a shared Docker network; the frontend
  is served by nginx (SPA + `/api` proxy) behind an external reverse proxy.

## Running it

Prerequisites: Docker, and a PostgreSQL reachable on the shared Docker network (default
`all_dockers`) with a database + user already created for tradelog.

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
| **Bitunix** | Full — since you opened the account | Signed REST; backfilled on first sync |
| **BingX** | **Only ~the last 30 days** | The `allFillOrders` API serves no older fills; older trades can't be retrieved |
| **Quantfury** | Full — whatever is in the exported PDF (typically your entire history) | Manual PDF import |
| **Journal CSV** | Whatever you import | Manual CSV in the canonical format — for hand-kept journals or exchanges that no longer exist |

So for **BingX**, sync it regularly — anything older than ~30 days is gone for good once it ages out of the API. For full long-term history on a platform that doesn't expose it via API, a Quantfury-style PDF/export import is the only path. For **dead exchanges** with no export at all, convert whatever records you kept into the **Journal CSV** format (see [Journal CSV import](#journal-csv-import)) — the in-app format reference is written so any AI assistant can do the conversion for you.

### Bitunix / BingX (API)

1. On the exchange, create an API key with **read-only** permissions — **no trading and no
   withdrawal** scope. (tradelog never needs more; a key with write scope is flagged.)
2. In tradelog, add a data source of kind **Bitunix** or **BingX**, give it a label (e.g. the
   sub-account or strategy name), and paste the **API key** and **API secret**.
3. The source syncs automatically on your next login, or immediately via **Sync now**. The first sync
   backfills as much history as the exchange API allows — **Bitunix** from account opening, **BingX
   only ~the last 30 days** (see the table above). If the credentials are rejected or lack read
   permission, the source moves to an **Error** state with an actionable message.

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
(for the live connectors it's simply Bitunix/BingX/Quantfury), and the Positions page lets you filter
by it. Re-importing the same file is idempotent (positions are keyed by a deterministic hash); each
Journal CSV source has its own id namespace, so its rows can never collide with positions synced from
an exchange.

## Development

The backend builds and tests via the Gradle Docker image (no local Gradle needed):

```bash
docker build ./backend                 # compile + bootJar
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
  -w /workspace gradle:9.0.0-jdk24 gradle --no-daemon test
```

On a host with a local Gradle + Docker, plain `gradle test` works directly. Integration tests
(`*IntegrationTest`, `*LifecycleTest`, `ApplicationContextTest`, …) spin up a real PostgreSQL via
Testcontainers and exercise the full Spring context, Flyway schema, JDBC sessions, the
profile-ownership interceptor, credential encryption, idempotent position upsert, the taxonomy, and
the Quantfury PDF import.

Frontend (Node 24):

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
- The **Quantfury** parser is validated end-to-end against a real report (computed vs printed PnL agree
  to rounding). Re-uploading the same export is safe.
- **API keys must be read-only.** The app detects and warns on permission errors but cannot enforce
  key scope on the exchange — that's on you when creating the key.
- Out of scope (Phase 1): open/unrealized positions, currency conversion, profiles shared between
  users, and fine-grained analytics (win rate, R-multiple, expectancy, bot-vs-manual, by hour/day).
