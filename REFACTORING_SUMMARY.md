# Refactoring Summary — 2026-08-17

Restructured the repository to match the layered architecture now documented in
[ARCHITECTURE.md](ARCHITECTURE.md). Behaviour-preserving by intent: no feature
was added or removed, and no live endpoint changed.

**Totals:** 118 files changed · 2,567 insertions · 3,125 deletions · 67 renames

**Decisions this followed** (chosen at the start of the session):
layered pipeline + provider adapters · delete the legacy `/sarjet` stack · keep
descriptive ML as a module but drop `validate.py` · placeholder for the EPDK
provider adapter only.

---

## 1. Verification

Every phase was checked before the next began. Nothing below is asserted without
a command behind it.

| Check | Result |
|---|---|
| `mvn -o clean compile` | **BUILD SUCCESS**, 48 classes |
| Endpoint surface diff (before vs after, all 67 mappings) | only the 16 legacy `/sarjet/*` routes removed; **every `/zes/*` and `/astor/*` path byte-identical** |
| `python -m compileall` over the pipeline | OK |
| Import check, all 17 Python modules | all import |
| `python -m pricing.test_demand_model` | all tests pass |
| Index-name values after catalog migration | identical strings (spot-checked 5 prefixes) |
| `npm run build` | 912 modules transformed, built |

No test suite exists for Java or the frontend (none did before), so `mvn compile`
and `npm run build` are the strongest available checks there. The endpoint diff is
the real safety net for the controller split.

---

## 2. Deleted — 2,718 lines

### Legacy `/sarjet` stack (1,832 lines Java + 730 lines frontend)

Unreachable from the running application. `App.jsx` renders only `Zes*`/`Astor*`
pages, so three pages were orphaned, and they were the sole callers of the entire
Java stack beneath them.

| File | Why |
|---|---|
| `controller/ChargingController.java` | only caller was the orphaned frontend |
| `controller/AvailabilityController.java` | same |
| `service/ChargingService.java` | only used by the two controllers above |
| `client/ApiClient.java` | only used by `ChargingService` |
| `elastic/AvailabilityAnalysisService.java` | only used by `AvailabilityController` |
| `elastic/SocketTransformService.java` | only used by `ChargingController` |
| `elastic/IndexInitService.java` | managed only the legacy `stations` / `station_availability-*` templates — verified unrelated to any `zes_*` index |
| `pages/{SearchPage,StatsPage,CityPage}.jsx` | not imported by `App.jsx` |
| `components/{BrandChart,DistrictChart,SocketTypeChart,CityChart}.jsx` | only used by those three pages |
| `api/availabilityApi.js` | only client of `/sarjet/availability` |

`TimeSlotChart` was *not* deleted despite being used by `StatsPage` — it is also
used by the live `ZesStatsPage` and `AstorStatsPage`.

Shared code was extracted out of the deleted packages first, not deleted with
them: `EsConfig` (used by all 20 ES services) and
`ConnectorStatusHistory`/`ConnectorStatusHistoryRepository` (used by the live ZES
tracker) moved to `kernel/`.

Also removed: the `/sarjet` proxy entries in `nginx.conf` and `vite.config.js`,
now that nothing serves that prefix.

### `ZesAnalysisController.java` (482 lines) — split, not dropped

Replaced by three layer-specific controllers; see §4.

### `intelligence/validate.py` (156 lines)

Zero importers and no endpoint. The four descriptive modules that *are* wired to
the API (`assign`, `audit`, `anomaly`, `train`) were kept per your decision.

### Also removed

An untracked copy of `ZesConnectorTransformAnalysisService.java` reappeared at
its old path mid-refactor (the IDE had the file open and re-saved the buffer).
It held the pre-refactor content — old package, imports referencing
now-deleted classes — and would have broken the build. Diffed against the moved
version first to confirm it contained no edits of yours; the bodies were
identical.

---

## 3. Reorganized — 67 renames

### Java: by provider → by pipeline layer

The old tree had `zes/` (5,017 lines) and `astor/` (916 lines) as parallel
copy-paste stacks, each with its own `client/ service/ elastic/ controller/`.

| Old | New | Layer |
|---|---|---|
| `elastic/EsConfig.java` | `kernel/es/` | L0 |
| `model/`, `repository/`, `astor/model/`, `astor/repository/` | `kernel/persistence/` | L0 |
| `zes/controller/ZesAdminController`, `zes/elastic/ZesPerformanceHealthService` | `kernel/admin/` | L0 |
| `zes/client/`, `zes/service/`, `zes/scheduler/` | `ingestion/providers/zes/` | L1 |
| `astor/client/`, `astor/service/`, `astor/scheduler/` | `ingestion/providers/astor/` | L1 |
| `zes/controller/ZesController`, `astor/controller/AstorController` | `ingestion/api/` | L1 |
| `zes/elastic/ZesIndexService`, `astor/elastic/AstorIndexService` | `materialization/stations/` | L2 |
| `zes/elastic/ZesConnectorTransformService` | `materialization/connectors/` | L2 |
| `zes/elastic/ZesStationPerformanceIndexService` | `materialization/performance/` | L2 |
| `zes/elastic/EpdkConnectorSnapshotService` | `materialization/epdk/` | L2 |
| `zes/elastic/Zes{Analysis,ConnectorTransformAnalysis,StationPerformanceQuery}Service`, `astor/elastic/AstorAnalysisService` | `analytics/station/` | L3 |
| `zes/elastic/ZesCityAnalysisService` | `analytics/city/` | L3 |
| `zes/elastic/ZesEnvironmentAnalysisService` | `analytics/environment/` | L3 |
| `zes/overpass/` | `analytics/enrichment/` + `analytics/api/` | L3 |
| `astor/controller/AstorAnalysisController` | `analytics/api/` | L3 |
| `zes/elastic/ZesForecast{,Backtest}Service` | `forecast/` | L4 |
| `zes/elastic/ZesOpportunityService` | `opportunity/` | L4 |
| `zes/elastic/ZesPricingService` | `pricing/` | L4 |
| `zes/controller/ZesBacktestController` | `intelligence/api/` | L4 |

Rule applied consistently: **HTTP controllers live in `<layer>/api/`**, so
provider packages stay pure adapters.

### Python: same layer names as Java

| Old | New |
|---|---|
| `extract.py` | `ingestion/extract.py` |
| `features.py` | `features/snapshot.py` |
| `assign.py`, `audit.py`, `anomaly.py`, `train.py` | `descriptive/` |
| `opportunity.py` | `opportunity/score.py` |
| `forecast/` | `forecast/` |
| `pricing/` | `pricing/` |
| `README.md` (ML brainstorm) | `docs/ml-roadmap.md` — preserved, replaced by a module README |

`app.py` and `config.py` deliberately stay at the package root: `config.MODELS_DIR`
resolves relative to its own location and maps to the `ml_models` Docker volume,
and the container entrypoint is `uvicorn app:app`.

**Test command changed:** `python -m pricing.test_demand_model` →
`python -m pricing.test_demand_model`.

### Frontend: pages grouped by the layer they consume

`pages/analytics/` — ZesStats, ZesSearch, ZesPerformance, ZesCity, AstorStats,
AstorSearch. `pages/intelligence/` — ZesPrediction, ZesPricing, ZesOpportunity,
ZesBacktest.

---

## 4. New structural code

### `kernel/es/IndexCatalog.java` + `kernel/index_catalog.py`

The index-naming contract, previously 29 private string constants across 15 Java
services and 9 Python modules. Both files now declare every stage with its
**owning runtime**, and all 29 Java declarations plus 17 Python ones delegate to
them. Values verified unchanged.

This is the single highest-leverage addition: the index name *is* the interface
between the three services, and until now no file described the pipeline.

### `ingestion/spi/StationStatusProvider.java`

The provider contract. Notable: `ZesStatusTrackerService` and
`AstorStatusTrackerService` already had **identical signatures**
(`loadTodayOpenSlots()`, `pollAndTrack()`, `indexDailyToElastic(boolean)`), so
this interface was extracted from the code rather than imposed on it. Both now
implement it and declare `providerId()`.

### Controller split — `ZesAnalysisController` → three controllers

| New controller | Endpoints | Layer |
|---|---|---|
| `analytics/api/ZesAnalyticsController` | 17 read endpoints (connector-transform, station-performance queries, city, environment, EPDK, available-dates) | L3 |
| `intelligence/api/ZesIntelligenceController` | 14 endpoints (forecast, opportunity, pricing) | L4 |
| `materialization/api/ZesMaterializationController` | 2 write endpoints (performance build, backfill) | L2 |

All three keep `@RequestMapping("/zes/availability")`, so **no client change was
required** — confirmed by the endpoint diff. The split makes the read/write
boundary structural: the only write endpoints under that prefix are now visibly
in the materialization layer.

### `materialization/performance/PerformanceBackfillService.java`

The 66-line backfill loop moved out of the controller into a service. Logic
copied verbatim.

### `console/src/lib/recommendations.js`

`REC_META` + `recLabel` extracted from `ZesPerformancePage.jsx`.
`ZesOpportunityPage` had been importing a constant *from another page* — a
coupling that would have crossed the new analytics/intelligence boundary. Now
both import from a shared module.

### `ingestion/providers/epdk/` — placeholder

`EpdkStationProvider` implementing the SPI with `TODO(R1)` markers, plus a README
carrying the full contract, the API endpoint, the cadence problem (a register is
not a poll), and the migration path from `scripts/`. Deliberately **not** a
`@Component`, so Spring never wires a throwing bean into a running app.

---

## 5. Documentation

- **`ARCHITECTURE.md`** — the blueprint: layer responsibilities with rationale,
  seven architectural principles, data flows, technology-stack rationale,
  ✅/🔄/📋 status table, and a five-item roadmap (R1–R5).
- **Layer READMEs** — `kernel/`, `ingestion/`, `materialization/`, `analytics/`,
  `intelligence/`, `ingestion/providers/epdk/`, `intelligence/`,
  `descriptive/`, `console/`, `scripts/`.
  Each states responsibility, why it matters, contracts to uphold, and status.
- **`CLAUDE.md`** — rewritten; the version written earlier in this session
  described the pre-refactor tree.
- **`README.md`** — layout section replaced; two index names in its architecture
  diagram were wrong (`zes_pricing_hourly-{date}`,
  `epdk_connector_snapshot-{date}`) and now match the catalog. `./mvnw` → `mvn`
  (no wrapper exists in the repo).

---

## 6. Dependencies — no changes needed

Checked rather than assumed:

- **`pom.xml`** — all six dependencies still used after the deletions:
  `starter-web` (controllers), `elasticsearch-java` + `rest-client` (20 services),
  `jackson-databind` (3 files), `h2` + `starter-data-jpa` (`kernel/persistence`).
- **`requirements.txt`** — `matplotlib` still used by `forecast/backtest.py` and
  `descriptive/audit.py`; `python-dateutil` has no direct import but is a pandas
  requirement; `uvicorn` is the CLI entrypoint. Nothing to remove.
- **CI** — no workflows exist for the removed paths. The `.github/java-upgrade/`
  and `.github/modernize/` directories are untracked local agent scaffolding and
  were left alone.

---

## 7. Flagged for your review

**1. `analytics/station/ZesAnalysisService.java` is now orphaned.**
Its only consumer was a field in `ZesAnalysisController` that *you* had already
commented out before this session. It queries `zes_stations-{date}` (the raw
snapshot) via one method, `searchStations` — a capability the UI covers with
`/connector-transform/search`. I kept the file rather than deleting it, since
losing raw-snapshot search wasn't part of the agreed scope. Delete it if you
agree it is superseded.

**2. Your uncommitted edit to `ZesAnalysisController` was carried forward.**
The working tree had 29 comment-only deletions (removing notes about previously
dropped endpoints). The three new controllers were written from that version, so
your edit is preserved — but the file it lived in no longer exists.

**3. `REFACTORING_PLAN.md` (29 KB) and `DEPLOYMENT_PLAN.md` (8 KB) now describe
the old architecture.** `ARCHITECTURE.md` and `DEPLOY.md` supersede them. I left
them in place — they may be useful as the "before" state for the paper — but they
now contradict the repo. Delete or move to `docs/` when you've extracted what you
need.

**4. Cross-layer scheduler call.** `ZesScheduler` (L1) triggers the L2
performance build at 00:15. This is deliberate — cadence is an ingestion
property — but it is the one place a layer reaches downward, noted in
`ingestion/README.md`.

**5. `descriptive/` needs a decision (R2).** Kept per your choice,
but it runs, writes `zes_station_anomaly-{D}`, and nothing consumes it. Its
README lays out both directions.

Everything is staged but **not committed** — review `git diff --cached` before
committing.

---

# Round 2 — same day: scope reduction + renaming

## Renamed

| Old | New | Why |
|---|---|---|
| `charging-tracker/` | `core-api/` | it is the platform's HTTP core (L1–L4 serving), not a "tracker" |
| `model_training/` | `intelligence/` | it forecasts, scores and prices — training is one endpoint of several |
| `charging-dashboard/` | `console/` | operator console |
| compose service `tracker` | `core-api` | matches the directory |
| compose service `pricing` | `intelligence` | it was never pricing-only — an outright misnomer |
| compose service `web` | `console` | matches the directory |
| volume `tracker_data` | `core_api_data` | consistency |

Inside `intelligence/`, the `intelligence/` sub-package was **flattened** — the
service *is* the L4 layer, so `intelligence/intelligence/forecast/` was noise. Its
packages now sit at the root: `forecast/`, `pricing/`, `opportunity/`,
`descriptive/`. Import paths shortened accordingly, and the test command is back to
`python -m pricing.test_demand_model`.

nginx upstreams and the compose build contexts were updated to match; `docker
compose config` validates.

**Root folder + GitHub repo were not renamed** — see § Your move below.

## Removed

**ASTOR / Beefull** (9 Java files, 2 pages, 1 API module, plus config): provider
adapter, tracker, scheduler, index service, analysis service, both controllers, the
JPA entity and repository, the `astor.*` property block, `ASTOR_API_AUTHORIZATION`
from compose and `.env.example`, the `/astor` proxy in nginx and Vite, and the
console's brand switcher.

`App.jsx` was simplified as a result: with one network there is no brand dimension,
so the two-axis `brand × tab` state collapsed to a single `tab`, and the page
dispatch became a lookup table instead of 10 chained conditionals.

**EPDK, all data paths**: `EpdkConnectorSnapshotService`, the `providers/epdk/`
placeholder and its README, the 2 `/epdk-connectors/*` endpoints, both catalog
entries, the console's EPDK chart (`ZesStatsPage` 7 charts → 6), the two API
client functions, and the whole `scripts/` directory (5 ingestion scripts + README).

> **A correction:** the round-1 docs claimed EPDK competitor data fed
> `effective_supply` and the opportunity score, and made that the justification for
> roadmap R1. That was wrong. `nearby_stations_count` and `nearby_avg_utilization`
> come from the **ZES nearby-station API** inside
> `ZesStationPerformanceIndexService`; the opportunity score reads them from the
> performance index. No shipped feature depended on EPDK data, which made its
> removal safe. Corrected in `ARCHITECTURE.md`, the materialization README and
> `CLAUDE.md`.

"EPDK" survives in `pricing/` as the **tariff regime** (discount-only ceiling),
which is core to the pricing model and unrelated to the removed dataset.

**`ZesAnalysisService`** — the orphan flagged in round 1, confirmed dead and deleted.

## Consequence to be aware of

`StationStatusProvider` now has **one implementation and no queued second** — you
chose to keep the contract, and removing EPDK removed the adapter that was going to
prove it. That is defensible as intent, but it is no longer a demonstrated
abstraction, and a paper reviewer will notice. Recorded honestly in
`ARCHITECTURE.md § L1`, the ingestion README, and the interface's own javadoc,
along with the fact that **layer 2 was never provider-agnostic anyway** — the
transform chain is keyed to the ZES payload and its nearby API, and that is where
the real cost of onboarding a network sits.

## Added

**[docs/adr/0001-intelligence-storage.md](docs/adr/0001-intelligence-storage.md)** —
the accepted design for moving L4 off Elasticsearch onto a Parquet lake with
DuckDB, with the measured scale that rules out Spark, the store-ownership split, a
six-step migration where each step ships independently, and the asset-runner design
that replaces the cron `curl` chain.

## Verification

| Check | Result |
|---|---|
| `mvn -o clean compile` | BUILD SUCCESS, 34 classes (was 48) |
| Endpoint diff | exactly 7 removed (5 ASTOR + 2 EPDK); all 44 remaining paths unchanged |
| `npm run build` | built |
| 18 Python modules import | OK |
| `python -m pricing.test_demand_model` | all pass |
| `docker compose config` | valid |

## Your move

**1. Rename the root folder and repo.** Not done from here: your editor has the
folder open, and the GitHub rename has to happen in repo settings first. When ready:

```bash
# a) GitHub → Settings → Repository name → ev-charging-platform
# b) then locally:
cd ~/Desktop
mv Charging_Scripts ev-charging-platform
cd ev-charging-platform
git remote set-url origin https://github.com/Erayisci/ev-charging-platform.git
git remote -v
```

Nothing inside the repo refers to the root directory by name, so no file edits are
needed after the move. Reopen the folder in your editor afterwards.

**2. Delete `REFACTORING_PLAN.md` and `DEPLOYMENT_PLAN.md`** once you have
extracted anything you want for the paper. They still describe the pre-refactor
architecture *and* the old folder names, so they now contradict the repo twice over.

**3. Decide on `descriptive/`** (`ARCHITECTURE.md § R2`) — still runs, still has no
consumer.

---

# Round 3 — same day: making layer 2 provider-agnostic

Round 1 introduced `StationStatusProvider` and claimed provider specifics stopped
at L1. Round 2's own docs admitted that was only half true: **L2 was the layer
that knew the ZES schema best.** This round closes that gap.

## What was actually coupled

Not diffuse — three concrete things in 1,041 lines of L2:

| Coupling | Where | Lines |
|---|---|---|
| The provider→canonical field mapping | `extractConnectors` read `isRoamingLocation`, `maxElectricPower`, `evses[].connectors[]` | ~68 |
| L2 autowired the provider's HTTP client | `enrichWithNearby` used `ZesApiClient` and walked `data[].evses[].connectors[]` | ~37 |
| The landing index was a raw payload dump | `stations/ZesIndexService`'s ES mapping is a copy of the ZES JSON schema | ~90 |

Plus `case "Charging"` / `case "Available"` string literals in two files. The other
~800 lines — ES aggregation over canonical fields, half-hour bucketing, haversine,
tier thresholds, the recommendation matrix, bulk indexing, the backfill loop — were
already provider-neutral.

## The move: push the boundary down, don't genericise upward

No type parameters, no strategy hierarchy. L1 grew two capabilities and a canonical
vocabulary; L2 lost the ability to see a payload.

**New — `ingestion/spi/` (7 files):** `ProviderScoped`, `ConnectorStatus`,
`AvailabilityWindow`, `ConnectorRow`, `NearbySupply`, `StationPayloadMapper`,
`NearbySupplyProvider`.

**New — wiring and adapters (3 files):** `ingestion/ProviderRegistry`,
`providers/zes/ZesStationPayloadMapper`, `providers/zes/ZesNearbySupplyProvider`.

**Moved (3):**

| From | To | Why |
|---|---|---|
| `materialization/stations/ZesIndexService` | `ingestion/providers/zes/ZesStationSnapshotIndexService` | Its mapping *is* the ZES JSON schema — a landing zone, not derived state |
| `materialization/connectors/ZesConnectorTransformService` | `…/ConnectorTransformService` | No longer reads a payload |
| `materialization/performance/ZesStationPerformanceIndexService` | `…/StationPerformanceIndexService` | No longer calls a provider |

`materialization/stations/` is gone. **L1 lands raw; L2 begins at the first
canonical stage** — the first schema the platform itself chose.

`IndexCatalog` gained `stations(providerId)` / `connectorTransform(providerId)` /
`stationPerformance(providerId)`; the `ZES_*` constants are now composed from
`PROVIDER_ZES` + a private dataset suffix, so they remain compile-time constants
and cannot drift from the functions. Mirrored in `kernel/index_catalog.py`.
**No index name changed** — `zes_stations` was always `{provider}_{dataset}`, so
parameterising the prefix is a no-op on the wire and L3/L4/L5 needed no edit.

## One thing that only showed up at runtime

`ProviderRegistry` first used constructor `List<StationStatusProvider>` injection.
That compiles and is idiomatic, and it deadlocks the context: creating the registry
creates every tracker, `ZesStatusTrackerService` depends on L2's transform service,
which depends back on the registry. Spring Boot rejects bean cycles by default, so
the app would have failed to start with a green build.

Fixed with `ObjectProvider` and per-capability resolution: L2's `@PostConstruct`
pulls in mappers alone (leaf beans), trackers resolve at first scheduler or request
use. The capability log moved to `ApplicationReadyEvent` for the same reason.

## Verification

`mvn compile` cannot see any of the risks here, so:

1. **Route surface unchanged** — annotation diff against the staged versions of both
   touched controllers: identical. The two new `?provider=` params are optional.
2. **Context wiring** — a real startup reached
   `[ES] Template 'zes_connector_transform_template' created.` and
   `'zes_station_performance_template' upserted.` before failing on *port 8080 in
   use*. Every bean wired; template names byte-identical to before.
3. **Output equivalence, 5,179 documents** — the refactored path
   (`ZesStationPayloadMapper` + `ConnectorTransformService.toDocument`) was run
   offline over the real 1,833 station payloads of `zes_stations-2026-08-13` and
   compared field-by-field against `zes_connector_transform-2026-08-13`, which the
   pre-refactor code built from that same source:

   ```
   before: 5179 docs   after: 5179 docs
   only in before: 0   only in after: 0
   identical docs: 5179 / 5179
   No field differences.
   ```

   Run offline deliberately: a live instance was already on 8080, and a second
   poller would have written duplicate slots into the authoritative H2.

   *A false alarm worth recording:* the first comparison showed all 5,179 docs
   differing on `availability`, because the harness serialised with a plain
   `ObjectMapper` while production hands the map to the ES client, whose
   `JacksonJsonpMapper` is configured `NON_NULL`. That is why the live index has no
   `end_time` key on open slots. Using the client's own mapper made it exact.

## One intentional behaviour change

`recommendationText()` no longer says "ZES": *"Bu bölgeye yeni **ZES** istasyonu
kurulmalı"* → *"…yeni istasyonu kurulmalı"*. A provider-agnostic layer should not
name an operator in its output. This text is rendered in
`ZesPerformancePage.jsx:538`, so it is user-visible — on **newly built dates only**;
existing indices keep the old wording until rebuilt. `recommendation_code` and the
console's `REC_META` labels are untouched.

## What is still coupled — stated rather than implied

- **L3, L4 and L5 compare status wire strings directly**: `analytics/` literals,
  `intelligence/config.py`'s `CHARGING_STATUS`, and `"Charging"` as a Recharts
  `dataKey` in three console charts. `ConnectorStatus` documents the codes as a
  contract but cannot stop a bypass. Changing this means a data migration, so it
  was left.
- **Every read endpoint is under `/zes/`** — the console's API layer hard-codes the
  prefix.
- **The landing mapping is ZES-shaped** — correctly, and now correctly located, but
  a second network needs its own landing writer.
- **`analytics/enrichment/ZesStationEnrichmentService` reads that landing index
  directly** (`id`, `latitude`, `longitude`), making it the only reader above L1
  still touching a raw payload. Not fixed here: the canonical stage carries the
  same coordinates as `lat`/`lng` but is one document per *connector*, so it is not
  a rename, and it is L3 work rather than the L2 boundary this round is about.
- Still **one implementation**. `ARCHITECTURE.md` Principle 4 now reads "holds for
  L2, violated above it" instead of a flat "untested".
