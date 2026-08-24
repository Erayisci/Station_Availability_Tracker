# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Three independently-deployed services that track the Turkish ZES EV charging network and produce utilization analytics, forecasts and pricing simulations.

**Read [ARCHITECTURE.md](ARCHITECTURE.md) first** — it is the authoritative description of the layer model, the storage contract, and what is implemented vs. decided-but-unbuilt. Each layer has a README next to its code. [DEPLOY.md](DEPLOY.md) is the Hetzner Cloud runbook.

Code comments, docs and all user-facing console strings are **Turkish**. Match that when editing existing files.

## Commands

**`core-api/` (Java 17, Spring Boot 3.2.5, port 8080)**
```bash
cd core-api
mvn spring-boot:run          # no mvnw wrapper in the repo
mvn -B clean package -DskipTests
```
There is no `src/test` and no test dependency in [pom.xml](core-api/pom.xml) — `mvn compile` is the only automated check.

**`console/` (React 18 + Vite)**
```bash
cd console
npm install && npm run dev   # proxies /zes → localhost:8080
npm run build                # only automated check; catches broken imports
```
No linter, no test runner. [vite.config.js](console/vite.config.js) proxies **only `/zes`** — the `/ml/` route exists in nginx (prod) but not in dev, and the console never calls it anyway (it reads ML output back through core-api). To exercise a Python endpoint while developing, `curl localhost:8001/...` directly.

**`intelligence/` (Python 3.10, FastAPI, port 8001)**
```bash
cd intelligence
source venv/bin/activate     # venv already exists
ES_HOST=http://localhost:9200 uvicorn app:app --reload --port 8001

python -m pricing.test_demand_model   # only test suite; plain asserts, no pytest
```

**Elasticsearch (required by both backends)**
```bash
docker run -d -p 9200:9200 -e discovery.type=single-node \
  -e xpack.security.enabled=false elasticsearch:8.13.4
```

**Full stack**
```bash
docker compose -f docker-compose.prod.yml up -d --build
```
[nginx.conf](console/nginx.conf) fronts everything on :80 — `/zes` → `core-api:8080`; `/ml/` → `intelligence:8001` with the prefix stripped (so `/ml/forecast/predict` hits FastAPI's `/forecast/predict`).

## Architecture essentials

### Shared storage is the integration bus

The three services never call each other. They exchange data through **date-partitioned datasets**, currently all Elasticsearch indices named `{providerId}_{dataset}-{YYYY-MM-DD}`. Java writes the raw chain; Python writes ML outputs; Java reads Python's output back for the console.

The provider prefix is reachable as a function (`IndexCatalog.connectorTransform(providerId)`, `index_catalog.connector_transform(...)`), and the `ZES_*` constants are pre-composed forms of the same thing. In L1/L2 code use the function; the constants are for readers outside the chain.

The naming contract is declared in exactly two files that must agree literally:
- [kernel/es/IndexCatalog.java](core-api/src/main/java/com/charging/kernel/es/IndexCatalog.java)
- [kernel/index_catalog.py](intelligence/kernel/index_catalog.py)

**Never hardcode a dataset name** — add it to both catalogs with its owning runtime. The one legitimate divergence today is `ZES_STATION_ANOMALY`, declared in Python only because no Java code reads it; a name becomes mandatory on both sides the moment the other runtime reads it.

⚠️ **This is mid-transition.** [ADR 0001](docs/adr/0001-intelligence-storage.md) moves the five L4 output datasets off Elasticsearch onto a Parquet lake queried with DuckDB, leaving ES for search and serving only. Before adding anything to the L4 storage path, read that ADR — new ES index families for ML output are going the wrong direction.

### Layered packages (same names in both runtimes)

```
kernel/           L0  ES config + catalog, JPA entities, admin/health
ingestion/        L1  provider adapters behind spi/ + raw payload landing zone
materialization/  L2  the only layer that writes derived datasets — canonical from here up
analytics/        L3  read-only aggregation
intelligence/     L4  Python computes; Java only reads results back
```
`console` is L5; its pages are grouped `pages/analytics/` and `pages/intelligence/` by the layer they consume. In the Python service the L4 packages sit at the root (`forecast/`, `pricing/`, `opportunity/`, `descriptive/`) because the whole service *is* that layer.

Named `kernel` not `platform` because `platform` is a Python stdlib module and a top-level package with that name would shadow it.

### The HTTP surface, by owning layer

Seven controllers, all mounted under `/zes`. Knowing which one owns a path saves grepping:

| Prefix | Controller | Layer |
|---|---|---|
| `/zes/fetch-nearby`, `/poll-now`, `/index-daily`, `/index-connector-transform` | `ingestion/api/ZesController` | L1 writes |
| `/zes/availability/station-performance/build`, `/backfill` | `materialization/api/ZesMaterializationController` | L2 writes |
| `/zes/availability/{connector-transform,station-performance,city,stats}/…` | `analytics/api/ZesAnalyticsController` | L3 reads |
| `/zes/enrich-stations` | `analytics/api/ZesEnrichmentController` | L3, writes `zes_station_environment` |
| `/zes/availability/{opportunity,forecast,pricing}/…` | `intelligence/api/ZesIntelligenceController` | L4 read-back |
| `/zes/backtest/…` | `intelligence/api/ZesBacktestController` | L4 read-back |
| `/zes/admin/perf-indices/…` | `kernel/admin/ZesAdminController` | L0 repair |

Python's own surface ([app.py](intelligence/app.py)) is unprefixed and reached as `/ml/…` through nginx: `/health`, `/audit`, `/assign`, `/train`, `/anomaly`, `/opportunity`, `/forecast/{train,predict,sanity-check}`, `/pricing/{build-hourly,predict-hourly,run-all}`. Only `forecast/predict` and `pricing/run-all` are in the daily pipeline; the rest are manual.

**Forecast validation** is a real surface that is easy to miss: `ZesForecastBacktestService` joins `zes_station_forecast-{D}` against the *actual* `zes_station_performance-{D}` for the same day and reports model-vs-naive MAE, rendered by [ZesBacktestPage.jsx](console/src/pages/intelligence/ZesBacktestPage.jsx). It only computes over dates where both indices exist. `intelligence/forecast/backtest.py` is the Python-side counterpart.

### Rules that are easy to break

- **One writer per stage.** Java must never write a Python-owned dataset (`zes_station_forecast`, `zes_station_opportunity`, `zes_station_hourly_*`, `zes_station_features`) and vice versa. Reading across the boundary is normal.
- **Reads never write — with two logged exceptions.** `analytics/` and `intelligence/` are read paths; the build endpoints live in `materialization/api` and `ingestion/api`. The exceptions are `POST /zes/enrich-stations` ([ZesEnrichmentController](core-api/src/main/java/com/charging/analytics/api/ZesEnrichmentController.java) → `analytics/enrichment`, which writes the static, non-date-suffixed `zes_station_environment` from Overpass) and the `DELETE /zes/admin/perf-indices/…` repair endpoints in `kernel/admin`. Both are operator-triggered one-offs, not pipeline stages — don't add a third silently.
- **Provider specifics stop at L1.** Anything provider-shaped above `ingestion/` is a defect. L2 holds this: it reads canonical `ConnectorRow`s from `StationPayloadMapper`, gets supply from `NearbySupplyProvider`, compares statuses via `ConnectorStatus`, and takes `providerId` as a parameter — a `Zes` import in `materialization/` is a regression. Capabilities are resolved through `ingestion/ProviderRegistry`, never by autowiring an SPI interface directly (that breaks the moment a second provider registers).
- **`{provider}_stations-{D}` belongs to L1, not L2.** It is the provider's payload verbatim — a landing zone whose ES mapping mirrors the ZES JSON schema. L2 starts at `{provider}_connector_transform-{D}`, the first schema the platform itself chose. Keep the raw index: it is what lets a *schema* change be repaired by re-running the mapper instead of re-polling.
- **H2 is authoritative, everything else is derived**, and H2 keeps 7 days (`zes.history.keepDays`). Older dates can only be rebuilt from the stage below them.
- Each ES-touching Java service builds its **own** `ElasticsearchClient` from `EsConfig.host()` and creates its index template in `@PostConstruct ensureTemplate()`. Follow that pattern.
- `/…/available-dates` endpoints enumerate real indices via `cat indices` — that is how the UI knows which dates exist.

### Scheduling chain (order matters)

1. 5-min poll → H2.
2. `zes.daily.cron` **23:59** — close open slots, build `zes_stations-{D}` then `zes_connector_transform-{D}`.
3. `zes.performance.autoBuildCron` **00:15** — build `zes_station_performance-` for **yesterday**; idempotent, skips if the target exists or the source is missing.
4. Host cron **00:30** — [deploy/daily-pipeline.sh](deploy/daily-pipeline.sh) POSTs `/ml/forecast/predict` then `/ml/pricing/run-all`. Forecast must come first: hourly pricing disaggregates the daily forecast.

Steps 1–2 are configured in [application.properties](core-api/src/main/resources/application.properties) (`zes.poll.*`, `zes.daily.cron`, `zes.history.keepDays`); `zes.performance.autoBuildCron` is *not* listed there — it exists only as the `@Scheduled` default in [ZesScheduler.java](core-api/src/main/java/com/charging/ingestion/providers/zes/ZesScheduler.java), so grep the annotations before assuming a schedule is unset.

### Adding an analytics feature

Five edits: a method on an `analytics/*` service → an endpoint on `ZesAnalyticsController` (read paths hang off `/zes/availability/...`) → a function in [api/zesApi.js](console/src/api/zesApi.js) (every call goes through the `fetchJson` helper against one of the three module bases — `BASE = '/zes/availability'`, `ADMIN_BASE`, `BACKTEST_BASE`) → a page under `console/src/pages/analytics/` → **an entry in the `NAV` array in [App.jsx](console/src/App.jsx)**, whose `layer` field puts the tab in the right group. There is no router: `App.jsx` switches pages with `useState`, so a page that isn't in `NAV` is unreachable.

For forecast/opportunity/pricing features use `ZesIntelligenceController` and `pages/intelligence/` — but check ADR 0001 first, since that controller is scheduled to move to Python.

### Console conventions

[index.css](console/src/index.css) is a design-token system (~94 classes, CSS custom properties, dark default, light via `[data-theme="light"]`) and [components/ui/index.jsx](console/src/components/ui/index.jsx) is the component kit — `Card`/`CardHeader`/`CardBody`, `ChartCard`, `MetricCard`, `Badge`/`TierBadge`/`RecBadge`, `Button`, `SegmentedControl`, `FilterChip`, `EmptyState`, `UtilBar`, `InfoTooltip`, plus `TOOLTIP_STYLE` for Recharts. Compose from those and reference colors as `var(--tx-primary)`, `var(--accent)`, `var(--clr-success)` etc.; a hex literal or a hand-rolled card in a page is a regression. Charts are Recharts; there is no CSS framework and no component library.

### Dynamic pricing — the browser holds the decision

Read [intelligence/pricing/README.md](intelligence/pricing/README.md) before touching `pricing/`. The city-level optimizer was deleted on 2026-06-25.

- Python only prepares data: observed hourly utilization + forecast disaggregated to hours.
- The operator sets hour-band discounts in [ZesPricingPage.jsx](console/src/pages/intelligence/ZesPricingPage.jsx); revenue is simulated live in [lib/pricingModel.js](console/src/lib/pricingModel.js).
- `pricingModel.js` is a **hand port of `demand_model.py` + `elasticity.py` and must stay numerically identical**. Constants are not duplicated — they come from `/zes/availability/pricing/config`, so assumptions stay server-authoritative.
- Every economic assumption is in [pricing_config.py](intelligence/pricing/pricing_config.py); none are fitted (the data has no price variation). Bump `PRICING_VERSION` when changing any. `BASE_PRICE` values are placeholders. EPDK rules mean discount-only: `d ∈ [0, D_MAX]`.
- "EPDK" in the pricing package means the **tariff regime**, not the regulator's station data — that data and its ingestion were removed on 2026-08-17.

### Where competitor supply comes from

`nearby_stations_count` comes from the **ZES nearby-station API** at a 1.0 km radius — reached through `NearbySupplyProvider`, so `StationPerformanceIndexService` never touches `ZesApiClient`. `nearby_avg_utilization` is computed in-process by haversine over the same day's stations, with no extra API call. Both feed `effective_supply` → `demand_supply_ratio` → `recommendation_code`. Not from any external register.

A failed lookup is a **value, not an exception**: `NearbySupply.unavailable(...)` yields `-1` counts, `supply_tier = UNKNOWN` and `recommendation_code = INSUFFICIENT_DATA`. Preserve that — ~1,800 lookups run concurrently per build and individual timeouts are routine. If no `NearbySupplyProvider` is registered for a provider, the stage still builds with those fields unmeasured.

## Known gaps (don't mistake these for bugs)

- `intelligence/descriptive/` runs but has no console surface and is not in the daily pipeline — see its README and `ARCHITECTURE.md § R2`.
- The L1 contract has exactly one implementation (ZES). Kept deliberately; unproven as an abstraction until a second network exists. L1 and L2 honour it, but **L3/L4/L5 still hardcode status wire strings** (`analytics/` literals, `intelligence/config.py`'s `CHARGING_STATUS`, `"Charging"` as a Recharts `dataKey` in three console charts) and every read endpoint is mounted under `/zes/`. `ConnectorStatus` documents those codes as a contract; it does not enforce them. `analytics/enrichment/ZesStationEnrichmentService` also reads ZES's camelCase `id`/`latitude`/`longitude` out of the raw landing index — the one reader above L1 that still touches a payload.
- No authentication anywhere, ES runs unsecured — deliberate for a firewalled single-operator VM, see `ARCHITECTURE.md § Deliberately absent`.
- Model promotion is manual; nothing gates on backtest error (`§ R3`).

## Notes

`REFACTORING_PLAN.md` and `DEPLOYMENT_PLAN.md` are pre-refactor planning docs describing the *old* structure and old folder names; `ARCHITECTURE.md` supersedes them. [REFACTORING_SUMMARY.md](REFACTORING_SUMMARY.md) records what the 2026-08-17 restructure changed. `intelligence/docs/ml-roadmap.md` is the original phased ML plan. `sunum.html` is a standalone presentation deck.
