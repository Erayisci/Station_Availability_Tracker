# `materialization/` — Deriving the index chain (L2)

**Status: ✅ complete · provider-agnostic since 2026-08-17**

## Responsibility

The only layer that writes derived state. It turns the landed provider snapshot
into the Elasticsearch chain, one package per stage:

```
connectors/    {provider}_connector_transform-{D}   ← explodes the snapshot into connector-slots
performance/   {provider}_station_performance-{D}   ← utilization, tiers, recommendations
api/           manual build + backfill triggers
```

The chain's first index, `{provider}_stations-{D}`, is **not** here — it lives in
`ingestion/providers/zes` because it holds the provider's payload verbatim. See
[Where the chain starts](#where-the-chain-starts).

## Why it is a separate layer

Because "rebuild one day" has to be correct, and this is the only place it can go
wrong. Ordering, idempotency and repair semantics are concentrated here so that
analytics and intelligence can assume their inputs are either complete or absent
— never half-written.

## Where the chain starts

`{provider}_stations-{D}` used to sit in this layer, in a `stations/` package. It
was moved out because it is not derived: its Elasticsearch mapping is a literal
copy of the ZES JSON schema (`evses`, `isRoamingLocation`, `maxElectricPower`,
camelCase throughout). Keeping it here meant the layer that writes *the
platform's own schemas* also wrote *a provider's schema*.

So the boundary is now: **L1 lands raw, L2 begins at the first canonical stage.**
Raw is worth keeping — when the canonical schema gains a field, history can be
recomputed by re-running the mapper over the snapshot instead of re-polling an
API that may no longer answer.

## How this layer stays provider-agnostic

Neither service reads a provider payload. Both consume canonical types from
`ingestion/spi`:

| L2 needs | Asks | Not |
|---|---|---|
| connector rows from a raw snapshot | `StationPayloadMapper.toConnectorRows()` | reading `evses[].connectors[]` itself |
| competing supply near a station | `NearbySupplyProvider.fetchNearbySupply()` | autowiring `ZesApiClient` |
| "was this connector charging?" | `ConnectorStatus.isCharging(status)` | `case "Charging"` |
| an index name | `IndexCatalog.connectorTransform(providerId)` | `IndexCatalog.ZES_CONNECTOR_TRANSFORM` |

Capabilities are resolved by `providerId` through `ingestion/ProviderRegistry`, so
**adding a network requires no edit in this package** — the test the layer
boundary is supposed to pass.

`NearbySupplyProvider` is optional. With none registered for a provider, the
`nearby_*` fields stay at `-1`, supply classifies as `UNKNOWN` and the
recommendation becomes `INSUFFICIENT_DATA`; the utilization half of the stage is
unaffected, so the build still completes.

## The ordering constraint

Each stage depends on the one above it, and the dependency is checked rather than
assumed:

- `connectors/` reads `{provider}_stations-{D}`.
- `performance/` reads `{provider}_connector_transform-{D}` and additionally
  measures nearby supply through the SPI (radius default 1.0 km).
- The 00:15 scheduler **skips** a date whose connector_transform is missing, and
  **skips** one whose performance index already exists. Both checks make the
  build safe to re-run at any time.

`PerformanceBackfillService` generalises this over a range: per date it verifies
the stage below exists, reuses or rebuilds, and reports
`SKIPPED` / `EXISTING` / `BUILT` / `REBUILT` / `FAILED` individually. One bad
date never aborts the range — a partial repair is more useful than an exception.

## Derived fields worth knowing

`StationPerformanceIndexService` documents the full list; the load-bearing ones:

| Field | Meaning |
|---|---|
| `charging_utilization_pct` | `total_charging_minutes / (connector_count × 1440) × 100` |
| `effective_supply` | `nearby_stations × (1 − nearby_avg_utilization/100)` — supply that is actually available, not merely present. `nearby_stations` comes from the provider's own **nearby-station query** at a 1.0 km radius, not from any external register; `nearby_avg_utilization` is computed in-process from the same day's stations by haversine, with no extra API call. |
| `demand_supply_ratio` | `utilization × 10 / (5 + effective_supply)`, 0–100. High = high demand against thin usable supply. |
| `recommendation_code` | from the (demand_tier × supply_tier) matrix; display mapping lives in the dashboard's `lib/recommendations.js` |

The nearby measurement is the one external call in this layer, made ~1,800 times
per build across 20 threads. A provider outage during the 00:15 window degrades
`nearby_*` for that date only: the failure is returned as a value
(`NearbySupply.unavailable`), recorded in `nearby_api_error`, and never propagated
as an exception — one station's timeout must not cost the whole day.

## Contracts to uphold

- **One writer per stage.** Never write a Python-owned index (forecast,
  opportunity, hourly_*) from here.
- **Never mutate a day in place.** Rebuild it.
- **Index names from `IndexCatalog`, parameterised by provider.** Prefer
  `IndexCatalog.connectorTransform(providerId)` over the pre-composed `ZES_*`
  constants — the constants exist for readers outside this chain.
- **No provider knowledge.** A `Zes` import in this package is a defect; use the
  SPI. If something is genuinely provider-shaped, it belongs in `ingestion/`.
- **Templates in `@PostConstruct`.** Each writing service ensures its own index
  template at startup — one per registered provider — so a fresh cluster is
  self-provisioning.

## Future work

**ADR 0001** mirrors this layer's two outputs into the Parquet lake as the
training input. That mirroring is additive: L2 keeps writing Elasticsearch,
because the console searches both of these datasets interactively.
