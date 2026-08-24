# System Architecture

EV charging network analytics platform — architectural blueprint.

This document describes the **intended** architecture. Some components are
complete, some partial, and some are decided but not yet built; the
[Implementation Status](#implementation-status) section labels each one. The
repository layout mirrors this document, so the code tree can be read as the
diagram.

---

## Overview

The platform observes Türkiye's ZES charging network and turns raw connector
status into operational decisions: which stations are saturated, which will be
tomorrow, where new capacity pays off, and what hourly discount an operator
should offer under EPDK's discount-only tariff regime.

| Component | Runtime | Responsibility |
|---|---|---|
| `core-api` | Java 17 / Spring Boot | Ingestion, materialization, analytics, read APIs |
| `intelligence` | Python 3.10 / FastAPI | Features, forecast, opportunity, pricing data |
| `console` | React 18 / Vite | Presentation, and the pricing decision itself |
| Elasticsearch 8 | — | Search and serving store |

### The central design decision: stores as interfaces

The three services **never call each other**. They exchange data exclusively
through shared storage, so a dataset name is the API. Two consequences drive the
whole design:

1. **Ownership is per stage, not per service.** Each stage has exactly one
   writer. Any number of readers may cross the language boundary — Java freely
   reads Python's forecast output — but two writers on one stage would mean two
   sources of truth for a day.
2. **Every stage is independently rebuildable.** State is partitioned by day, so
   one date can be dropped and recomputed without touching another. Backfill and
   repair are routine operations rather than incidents.

The naming contract is declared once per runtime and nowhere else:
[`kernel/es/IndexCatalog.java`](core-api/src/main/java/com/charging/kernel/es/IndexCatalog.java)
and [`kernel/index_catalog.py`](intelligence/kernel/index_catalog.py). These two
files must agree literally.

> **In transition.** Today that shared storage is Elasticsearch for every stage.
> [ADR 0001](docs/adr/0001-intelligence-storage.md) accepts splitting it: ES keeps
> search and serving, while the L4 intelligence stages move to a date-partitioned
> Parquet lake queried with DuckDB. The ownership and rebuildability rules above
> are unchanged by that move — only the medium changes. Sections below mark which
> statements describe today and which describe the accepted target.

---

## Core Modules

The Java backend is organised by **pipeline layer**. Layer numbers are referenced
throughout the codebase as L1–L5.

### L0 — `kernel/` · Shared kernel

Cross-cutting infrastructure every layer depends on and which depends on nothing:
the Elasticsearch connection (`EsConfig`), the naming contract (`IndexCatalog`),
the JPA entities for authoritative state (`persistence/`), and operational
endpoints for index health and repair (`admin/`).

**Why it matters:** without this layer the naming contract has no home and
degenerates into duplicated string literals — the condition this refactor removed
(29 declarations across 15 services).

### L1 — `ingestion/` · Acquisition behind a provider contract

Polls the charging network, detects connector status transitions, persists them to
the authoritative relational store, and lands the day's raw payload. Everything
provider-specific is confined to adapters behind three interfaces in
[`spi/`](core-api/src/main/java/com/charging/ingestion/spi/):

| Interface | Answers | Optional? |
|---|---|---|
| [`StationStatusProvider`](core-api/src/main/java/com/charging/ingestion/spi/StationStatusProvider.java) | poll the network, persist transitions | no |
| [`StationPayloadMapper`](core-api/src/main/java/com/charging/ingestion/spi/StationPayloadMapper.java) | interpret a landed payload → canonical `ConnectorRow`s | no |
| [`NearbySupplyProvider`](core-api/src/main/java/com/charging/ingestion/spi/NearbySupplyProvider.java) | competing capacity within *r* km | **yes** |

`ProviderRegistry` resolves them by `providerId`, so layers above never name an
implementation class. The split is not decorative: polling and mapping are
separately optional because an archived network keeps its mapper for backfill
after its API stops answering, and a network with no proximity query simply has
no third implementation — L2 then degrades to unmeasured supply fields rather
than failing.

**Why it matters:** the ~5,000 lines above this layer consume only "a station had
this connector in this state for this interval" — never a provider's payload
shape, auth scheme or status vocabulary. Pinning that boundary means adding a
network is an adapter, not a fork of the pipeline. Providers also poll
independently, so one network's outage or rate limit cannot stall another's chain.

**Honest status:** ZES is currently the *only* implementation. A second network
(ASTOR/Beefull) was implemented and removed on 2026-08-17 as out of scope. The
contract is retained deliberately, on the judgement that a second network is
likely and that the boundary is cheap to hold — but until one exists, this is an
interface with one implementor, and should be described that way rather than as a
demonstrated abstraction.

### L2 — `materialization/` · Deriving the index chain

The only layer that writes derived state. One package per stage: `connectors/`
(connector-level explosion of the landed snapshot) → `performance/` (utilization,
demand/supply tiers, recommendation codes).

**Why it matters:** "rebuild one day" has to be correct, and this is the only
place it can go wrong. Ordering, idempotency and repair semantics are concentrated
here so that analytics and intelligence can assume their inputs are either
complete or absent — never half-written.

**Where the chain starts.** `{provider}_stations-{D}` is *not* in this layer. Its
Elasticsearch mapping is a literal copy of the provider's JSON schema, so it is a
landing zone, not a derived dataset, and it lives with the adapter that produced
it. The boundary is therefore: **L1 lands raw, L2 begins at the first canonical
stage** — the first schema the platform itself chose. Keeping the raw payload is
what makes a *schema* change repairable, not just a data loss: when the canonical
row gains a field, history is recomputed by re-running the mapper, with no need
for a provider API that may no longer answer the same way.

Competitor supply is computed here too: `nearby_stations_count` comes from the
**provider's own nearby-station query** at a 1.0 km radius — obtained through
`NearbySupplyProvider`, not by calling a provider client — and
`nearby_avg_utilization` is derived in-process from the same day's stations by
haversine. Together they feed `effective_supply` → `demand_supply_ratio` →
`recommendation_code`.

### L3 — `analytics/` · Descriptive read model

Strictly read-only aggregation over materialized indices: station queries, city
rollups, environment breakdowns, plus OpenStreetMap enrichment. `api/` is the HTTP
edge.

**Why it matters:** separating reads from the write path means dashboard traffic
cannot corrupt or block the chain, and query services can be cached or scaled
independently of ingestion.

### L4 — `intelligence/` · Predictive and prescriptive

Split across two runtimes by nature of the work. **Python** computes: feature
snapshots, the 1-day-ahead utilization forecast, opportunity scores, and hourly
pricing inputs. **Java** only reads those results back for the dashboard —
`intelligence/forecast/`, `opportunity/`, `pricing/` contain no model code at all.

**Why it matters:** the ML toolchain (scikit-learn, pandas) has no credible JVM
equivalent, and model iteration cadence differs from backend release cadence. The
storage contract lets each side be rewritten or redeployed without touching the
other.

**Accepted change ([ADR 0001](docs/adr/0001-intelligence-storage.md)):** this
layer's five output datasets move from Elasticsearch to a Parquet lake, and its
14 read endpoints move from `core-api` to the Python service behind nginx path
routing — which deletes the Java half of this layer entirely. The reason is that
writing model output into an index nobody searches, then reading it back by
(station_id, date) from another runtime, is machinery with no consumer benefit.

### L5 — `console` · Presentation, and the pricing decision

Pages are grouped by the layer they consume (`pages/analytics/`,
`pages/intelligence/`). Uniquely, this layer also *owns a decision*: dynamic
pricing is human-in-the-loop. The operator drags hour-bands and sets discounts;
revenue is simulated in the browser by
[`lib/pricingModel.js`](console/src/lib/pricingModel.js).

**Why it matters:** the demand data contains no price variation, so an elasticity
parameter cannot be fitted from it. Rather than hide an unfalsifiable assumption
inside a server-side optimizer, the assumption is served explicitly from
`/pricing/config` and the human makes the call. `pricingModel.js` is a hand port
of `demand_model.py` and **must stay numerically identical**; its constants are
fetched from the backend rather than duplicated, so the assumptions remain
server-authoritative and version-stamped.

---

## Architectural Principles

**1. One writer per stage; readers may cross languages.**
Ownership is declared in the catalog. A Python-owned stage read by Java is normal;
a stage written by both is a defect.

**2. Authoritative state is relational; everything else is derived.**
H2 holds observed transitions for `zes.history.keepDays` (7). Every downstream
dataset can be recomputed — from H2 inside the retention window, from the previous
stage outside it. This is what makes the analytical stores disposable and the
backup strategy cheap.

**3. Partition by day, never mutate in place.**
Each stage writes one partition per date. Repair is "drop and rebuild that date."
No migrations, no partial-update semantics, no cross-day locking.

**4. Provider specifics stop at layer 1.**
Enforced by the SPI. The test: adding a network must not require edits above
`ingestion/`. **Holds for L2** since 2026-08-17 — `grep -i zes` in
`materialization/` returns only the legacy URL prefix, and both stages take
`providerId` as a parameter. Still violated above: L3, the Python service and
three console charts compare `ConnectorStatus` wire strings directly,
`analytics/enrichment` reads ZES's camelCase coordinates out of the landing index,
and every read endpoint is mounted under `/zes/`. Untested in the strongest sense — one
implementation (see L1) — but no longer contradicted by the layer that matters
most, since L2 is where the cost of onboarding a network would land.

**5. Reads never write.**
`analytics/` and `intelligence/` are read-only. The only write endpoints are
`materialization/api` (rebuild) and `ingestion/api` (poll now), both for repair.

**6. Storage role follows access pattern.**
Searchable → Elasticsearch. Computable → the lake. A dataset needing full-text or
many-predicate filtering belongs in ES; one that is scanned, joined or aggregated
belongs in Parquet. Applying this rule is what ADR 0001 is.

**7. Assumptions are explicit, versioned, and served — never buried.**
Every unfitted economic parameter lives in
[`pricing_config.py`](intelligence/pricing/pricing_config.py) behind
`PRICING_VERSION`, and is served to the client rather than duplicated. An
assumption a reviewer cannot find is one that cannot be challenged.

**8. Failure is contained per stage and per date.**
Backfill reports per date (`SKIPPED` / `EXISTING` / `BUILT` / `FAILED`) rather
than aborting a range; schedulers catch per provider.

---

## Data Flows

### Steady state (daily)

```
        ZES API                              (every 5 min)
           │
   ┌───────▼─────────────────────────────────────────┐
   │ L1 ingestion — StationStatusProvider            │
   │   detect status transitions → persist slots     │
   │   23:59  finalize → land zes_stations-{D} raw   │
   └───────────────┬─────────────────────────────────┘
                   ▼
              H2 (authoritative, 7-day retention)
              zes_stations-{D}  (raw payload, kept indefinitely)
                   │  StationPayloadMapper → ConnectorRow
                   ▼
   ┌─────────────────────────────────────────────────┐
   │ L2 materialization  (canonical from here on)    │
   │   zes_connector_transform-{D}                   │
   │ 00:15    └─▶ zes_station_performance-{D-1}  (idempotent)
   └───────────────┬─────────────────────────────────┘
                   ▼
   ┌─────────────────────────────────────────────────┐
   │ L4 intelligence (Python, 00:30)                 │
   │   features → forecast → hourly pricing inputs   │
   │   opportunity                                   │
   └───────────────┬─────────────────────────────────┘
                   ▼
   L3 analytics + L4 readers → REST → L5 console
```

Ordering is a hard constraint, not a convention: the performance build needs
connector_transform, and hourly pricing disaggregates the daily forecast, so
forecast must precede pricing. Today that ordering lives in
[`deploy/daily-pipeline.sh`](deploy/daily-pipeline.sh); ADR 0001 replaces it with
a dependency-resolving asset runner so the constraint is declared rather than
implied by line order.

### Request path

```
browser → nginx :80
            ├─ /zes/*  → core-api:8080      (L3 + L4 read controllers)
            ├─ /ml/*   → intelligence:8001  (prefix stripped)
            └─ /       → static SPA
```

After ADR 0001 step 4, the three intelligence prefixes route to
`intelligence:8001` instead, leaving the console's URLs unchanged.

### Repair path

```
POST /zes/availability/station-performance/backfill?startDate=&endDate=
   → PerformanceBackfillService: per date, verify the stage below exists,
     skip / reuse / rebuild, report outcome per date
```

---

## Technology Stack Rationale

| Choice | Why |
|---|---|
| **Elasticsearch for search and serving** | The dashboard's connector and performance searches are many-predicate, paginated and ad-hoc over millions of connector-slot documents. That is ES's core competence and the reason it stays. |
| **Parquet + DuckDB for the ML substrate** | Columnar, immutable, date-partitioned files read zero-copy into Arrow; DuckDB gives vectorized SQL with partition and column pruning, embedded, with no service to operate. See [ADR 0001](docs/adr/0001-intelligence-storage.md). |
| **Not Spark** | At 350 MB total, cluster coordination and shuffle overhead would make jobs slower than pandas is today, for capability that is not needed. Parquet keeps Spark available for free if the data ever outgrows one machine — the same files, a different engine. |
| **H2 (embedded, file-based) for authoritative state** | Only ~7 days are authoritative, single-writer, single-node. An embedded database removes a network hop and a deployment unit; the retention bound is what keeps this defensible. Replace it — not the design — if retention grows. |
| **Spring Boot** | Scheduling, DI and REST in one runtime; `@Scheduled` with externalised cron expressions matches the ingestion cadence requirement exactly. |
| **Python for the ML layer** | scikit-learn / pandas have no JVM equivalent, and model iteration cadence differs from backend release cadence. FastAPI keeps stages triggerable over HTTP. |
| **React + Vite, no router, no state library** | A single-operator analytical tool: eight pages, one date selector, no deep-linking requirement yet. `useState` in `App.jsx` suffices; the absence of a router is a scope decision to revisit when sharing links matters. |
| **Browser-side revenue simulation** | The pricing decision is human-in-the-loop, so the model must respond within a drag gesture. A server round-trip per band adjustment would make the interaction unusable. |
| **Docker Compose on one always-on VM** | Continuous polling makes serverless a poor fit, and the workload is one small cluster's worth. Only `console` (nginx) publishes a port. |

---

## Implementation Status

### ✅ Complete and in use

| Component | Location |
|---|---|
| ZES provider adapter + 3-capability contract + registry | `ingestion/providers/zes/`, `ingestion/spi/`, `ingestion/ProviderRegistry` |
| Raw payload landing zone | `ingestion/providers/zes/ZesStationSnapshotIndexService` |
| Canonical index chain: connectors → performance | `materialization/` |
| Backfill / repair | `materialization/performance/PerformanceBackfillService` |
| Station, city, environment analytics | `analytics/` |
| OSM environment enrichment | `analytics/enrichment/` |
| Naming contract (both runtimes) | `kernel/es/IndexCatalog`, `kernel/index_catalog.py` |
| Index health + admin | `kernel/admin/` |
| Feature snapshot, 1-day forecast, backtest | `intelligence/features/`, `intelligence/forecast/` |
| Opportunity scoring | `intelligence/opportunity/` |
| Hourly pricing data + browser revenue engine | `intelligence/pricing/`, `console/src/lib/pricingModel.js` |
| Console (8 pages) | `console/src/pages/` |

### 🔄 Partially implemented

| Component | State |
|---|---|
| **Descriptive ML** (`intelligence/descriptive/`) | `assign`, `audit`, `anomaly`, clustering `train` run and write results, but have no dashboard surface and are not in the daily pipeline — reachable only by manual `POST`. See R2. |
| **Model lifecycle** | A `models/latest.json` pointer with backtest metrics, but no registry, no drift detection, no promotion gate. See R3. |
| **Provider abstraction** | Contract defined, one implementor, so unproven until a second network exists. L1 and L2 honour it; L3/L4/L5 still compare status wire strings directly and all read endpoints sit under `/zes/`. See L1 § Honest status for the remaining list. |

### 📋 Decided, not yet built

| Component | Reference |
|---|---|
| **Parquet lake + DuckDB for L4** | [ADR 0001](docs/adr/0001-intelligence-storage.md) — six-step migration, each independently shippable |
| **Asset-based pipeline runner** | ADR 0001 § Automation — replaces `deploy/daily-pipeline.sh` |

### ❌ Deliberately absent

Authentication, multi-tenancy and any outward-facing API are **not** part of this
architecture. Elasticsearch runs with `xpack.security.enabled=false`, the console
has no login, and access control is the VM firewall (single source IP). This is
sound for a single-operator internal tool on a locked-down VM and unacceptable the
moment a second tenant or a public endpoint appears. Recorded here so the omission
reads as a decision rather than an oversight.

---

## Development Roadmap

Ordered by architectural leverage.

**R1 — Execute ADR 0001, steps 1–3.**
Stand up the lake, move training reads to Parquet, dual-write outputs. First
because it removes Elasticsearch from the training loop — the largest source of
accidental complexity in the system — and because step 2 is verifiable by
checking that the retrained model reproduces its metrics.

**R2 — Resolve the descriptive-ML fork.**
Either surface `assign` / `audit` / `anomaly` in the console or delete them. Code
that runs but is unreachable is the strongest predictor of an architecture
drifting from its documentation.

**R3 — Model registry and promotion gate.**
Version model artifacts with the feature version and training window, and promote
a new model only when it beats both the naive baseline and the incumbent's
recorded error. The backtest already computes the metric; nothing retains or gates
on it. Cheap once ADR 0001 lands, since manifests carry the provenance.

**R4 — Execute ADR 0001, steps 4–6.**
Move the intelligence endpoints to FastAPI, delete the Java `intelligence/` layer,
replace the shell pipeline with the asset runner, drop the ML indices. Deferred
behind R2/R3 only because it is the one step with a real cutover.

**R5 — Security, before any second consumer.**
Authentication on the console, credentials on Elasticsearch, tenant scoping in
persistence. Prerequisite for anything outward-facing.
