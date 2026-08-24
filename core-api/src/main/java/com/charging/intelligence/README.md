# `intelligence/` — Predictive & prescriptive, Java side (L4)

**Status: ✅ complete as a read layer**

## Responsibility

Serve the outputs of the Python ML pipeline to the dashboard. **This package
contains no model code and computes no predictions.** Every service here is a
reader:

```
forecast/       reads zes_station_forecast-{D}; backtest vs realised performance
opportunity/    reads zes_station_opportunity-{D}
pricing/        reads zes_station_hourly_{performance,forecast}-{D}; serves assumptions
api/            HTTP edge — ZesIntelligenceController, ZesBacktestController
```

The writer of all four index families is
[`intelligence/`](../../../../../../../../intelligence).

## Why the split runs through the middle of one layer

The intelligence layer is conceptually single but physically split across two
runtimes, because the two halves have genuinely different requirements:
scikit-learn and pandas have no credible JVM equivalent, while the dashboard
needs a stable, typed HTTP surface. The Elasticsearch naming contract
(`kernel/es/IndexCatalog`) is what lets either half be rewritten without touching
the other.

The rule that keeps it honest: **Java never writes an index in this layer.** If a
computation is needed that Python does not produce, the answer is a new Python
stage plus a new catalog entry — not a quiet Java implementation that becomes a
second source of truth for the same question.

## `forecast/` — and why backtest lives here

`ZesForecastBacktestService` joins `zes_station_forecast-{D}` against the
*realised* `zes_station_performance-{D}` once that day actually happens, and
reports MAE, error distribution and gap-day breakdowns. It reads one Python-owned
and one Java-owned index, which makes it the natural place to catch a contract
break: if the two stop agreeing on station identity or field names, the backtest
is where it surfaces first.

## `pricing/` — data and assumptions only, never a decision

`ZesPricingService` exposes three things: the hourly forecast for a station, its
recent observed occupancy, and — via `/pricing/config` — the economic assumptions
themselves (base prices, elasticity η, `U_CAP`, discount tiers).

Serving the assumptions rather than duplicating them is the point. The discount
decision is made by the operator in the dashboard and revenue is simulated
browser-side in `lib/pricingModel.js`, which is a hand port of
`demand_model.py` + `elasticity.py`. Because the constants arrive from
`/pricing/config`, the port only replicates *formulas*, and the assumptions stay
server-authoritative and version-stamped (`PRICING_VERSION`).

**If you change the demand model, change all three:** `demand_model.py`,
`pricingModel.js`, and — if a new parameter is introduced — the `config()`
payload. Divergence here is silent: the page keeps rendering plausible revenue
numbers that no longer match the documented model.

## Why no server-side optimizer

There was one, at city level, and it was removed on 2026-06-25. The demand data
contains no price variation, so elasticity cannot be fitted from it — an
optimizer would have projected an unfalsifiable assumption into a precise-looking
recommendation. Making the assumption explicit and the human responsible for the
call is the more defensible design; see
[`pricing/README.md`](../../../../../../../../pricing/README.md).
