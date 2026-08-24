# `analytics/` — Descriptive read model (L3)

**Status: ✅ complete**

## Responsibility

Answer questions about what *has* happened, by aggregating indices that layer 2
already built. Strictly read-only: no service in this package writes to
Elasticsearch.

```
station/       per-station and per-connector queries, search, tier distributions
city/          81-province rollups + single-city drill-in (choropleth source)
environment/   OSM environment-type breakdowns (mall / highway / urban …)
enrichment/    Overpass API client that populates zes_station_environment
api/           HTTP edge — ZesAnalyticsController, ZesEnrichmentController
```

## Why reads are separated from the write path

Dashboard traffic is bursty and user-driven; the chain is scheduled and
long-running. Keeping them in different layers means a slow query cannot block a
build, a query service can be cached or scaled on its own, and — most practically
— no read endpoint can corrupt a day's data. The only write endpoints in the
whole ZES namespace live in `materialization/api`.

## `enrichment/` is the one exception, and it is deliberate

`ZesStationEnrichmentService` *writes* `zes_station_environment` from the
Overpass (OpenStreetMap) API. It sits here rather than in `materialization/`
because what it produces is not a pipeline stage: it is a static, non-dated
classification of where a station physically is (mall, highway, residential),
refreshed only when the station inventory changes. It has no daily cadence and
nothing downstream rebuilds from it, so placing it in the chain would imply an
ordering dependency that does not exist.

Triggered manually via `POST /zes/enrich-stations`.

## Contracts to uphold

- **Read-only.** If a change here needs to write a dated index, it belongs in
  `materialization/`.
- **Absent input is a normal case, not an error.** A date whose index was never
  built must produce an empty or explicit response, because the UI's date pickers
  are driven by `/…/available-dates` and users can request any date.
- **`available-dates` endpoints enumerate reality.** They list actual indices via
  `cat indices` on the prefix rather than computing a range — the source of truth
  for "what can be displayed" is the cluster.

## Why this layer stays on Elasticsearch

[ADR 0001](../../../../../../../docs/adr/0001-intelligence-storage.md) moves L4's
datasets to a Parquet lake but deliberately leaves L3 where it is. The queries here
are the ones ES is actually for: `/connector-transform/search` filters on seven
optional predicates with pagination, and `/station-performance/search` takes six
plus arbitrary sort. That is an ad-hoc search workload, not a scan — the rule is
*searchable → Elasticsearch, computable → lake*.
