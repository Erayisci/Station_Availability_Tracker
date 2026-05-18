# ZES ML/Prediction Brainstorm — From Trained Model to Useful Metrics

## Context

The repo already has a strong descriptive analytics layer for ZES stations:
- Per-station daily metrics (`charging_utilization_pct`, `effective_supply`, `demand_supply_ratio`, `recommendation_code`) computed in `charging-tracker/src/main/java/com/charging/zes/elastic/ZesStationPerformanceIndexService.java`
- Time-slot status aggregation in `charging-tracker/src/main/java/com/charging/zes/elastic/ZesConnectorTransformAnalysisService.java` (15/30/60-min buckets, per-connector status)
- Dashboard views in `charging-dashboard/src/pages/ZesStatsPage.jsx` / `charging-dashboard/src/components/TimeSlotChart.jsx`

What exists today is a **snapshot view**: "yesterday's utilization was X, so do Y." A trained model lets us replace single-day snapshots with **forward-looking, smoothed, station-personalized expectations** — which is what unlocks new metrics rather than just prettier charts.

**Data available to train on:**
- `zes_connector_transform-{date}` (full availability arrays, ~2 months of daily indices)
- `zes_station_performance-{date}` (per-station daily aggregates)
- H2 `connector_status_history` (7-day rolling, fine grain)
- Static features: city, province, lat/lng, connector type, max_power_kw

**Stack decisions:**
- Standalone **Python FastAPI** service, called by Spring Boot
- Primary horizon: **next 7 days daily average per station**

The point of this document is to brainstorm what metrics/products that model output could power, evaluate them, and pick a phased path.

---

## The core idea: predicted utilization as a primitive

Once we have a model that outputs `expected_utilization(station, date)` for the next 7 days, we can derive many things from it. Below are the candidate "useful metrics," grouped by what they unlock.

---

## Candidate Metrics & Products

### A. Forward-looking versions of existing metrics (drop-in replacements)

**A1. `predicted_utilization_7d`** — average predicted utilization over the next 7 days per station.
- **Replaces:** yesterday's `charging_utilization_pct` as the demand input to `demand_supply_ratio`.
- **Why it helps:** today's metrics react to single-day noise (a holiday, an outage); a 7-day forecast smooths it and avoids ranking stations based on one weird day.
- **Effort:** low — same `demand_supply_ratio` formula, just swap the demand input.
- **Value:** high — improves every downstream view (overutilized list, recommendation_code, demand_tier).

**A2. `predicted_demand_tier`** — HIGH/MEDIUM/LOW derived from `predicted_utilization_7d` instead of yesterday.
- **Replaces:** current `demand_tier` calc.
- **Effort:** trivial once A1 exists.
- **Value:** high — feeds existing UI directly.

**A3. `trend_direction`** — sign + magnitude of `predicted_7d - actual_trailing_7d`.
- **New metric.** Three values: GROWING / STABLE / DECLINING.
- **Why it helps:** lets us sort the overutilized list by stations that are getting worse, not just stations that are bad today.
- **Effort:** low (it's just subtraction once the prediction exists).
- **Value:** high — this is the single most actionable thing the model gives us.

### B. Anomaly & operations metrics

**B1. `health_score`** — distance between actual today and predicted today.
- **Use case:** a station predicted to run 40% utilization but actually running 2% is probably broken or offline. Flag it.
- **Effort:** medium — needs a baseline of residual distributions per station to set sensible thresholds.
- **Value:** high if operator alerts matter; medium otherwise. **Note:** the status field already has "Offline" — model-based health detects subtler failures (a connector reporting Available but no one's plugging in because it's actually broken).

**B2. `volatility_index`** — std-dev of prediction residuals over trailing window.
- Stations with high residual variance are unpredictable (events, construction, seasonality not captured).
- **Effort:** medium.
- **Value:** medium — interesting but secondary; mainly useful as a filter ("ignore volatile stations when ranking").

### C. Pattern-discovery metrics (unsupervised, no forecast needed)

**C1. `usage_archetype`** — cluster stations on their typical (hour × day_of_week) profile.
- Examples: COMMUTER_PEAK (sharp 8am + 6pm), HIGHWAY (steady all day), NIGHT_OWL, WEEKEND_HEAVY.
- **Effort:** low-medium — k-means or hierarchical clustering on 168-dim vectors (24h × 7d).
- **Value:** high for analysis & UI storytelling; enriches the tier system with *why* a station looks busy, not just *that* it is.
- **Important:** this doesn't need a forecasting model — pure descriptive ML. Could ship before the forecaster.

**C2. `peer_group`** — stations with similar archetype AND geography. Compare a station against its peer group rather than the global average.
- **Effort:** small once C1 exists.
- **Value:** medium — refines underutilized/overutilized into "underutilized *for its peer group*."

### D. Long-shot / not recommended in phase 1

**D1. Per-hour next-24h forecast.** Useful for end-user routing, but we chose 7-day horizon — skip.
**D2. Wait-time prediction.** Requires queue model & no clear consumer in current UI — skip.
**D3. Counterfactual ("if I add a connector, what happens to neighbors?").** Causal, hard, low data. Defer.

---

## Evaluation Matrix

| Metric | Data sufficient? | New UI needed? | Effort | Marginal value over today |
|---|---|---|---|---|
| A1 predicted_utilization_7d | Yes (2mo enough for daily avg) | No | Low | High |
| A2 predicted_demand_tier | Yes | No | Trivial | High |
| A3 trend_direction | Yes | Small | Low | **Highest** |
| B1 health_score | Marginal (need more history for stable thresholds) | Yes (alerts page) | Medium | Medium-High |
| B2 volatility_index | Marginal | Maybe | Medium | Medium |
| C1 usage_archetype | Yes | Yes (badge/filter) | Medium | High |
| C2 peer_group | Yes | Small | Small | Medium |

**Risk note:** 2 months of data is the binding constraint. It's enough for hour-of-day and day-of-week patterns, but **not** for monthly seasonality, holidays, or weather effects. Keep models simple (gradient boosting on engineered features, not deep learning) and validate with a clean time-based holdout.

---

## Recommended Phased Plan

### Phase 1 — Descriptive ML (no forecast, ship first)
Ship **C1 usage_archetype** + **C2 peer_group**. This is the lowest-risk win: pure clustering on existing data, no forecasting, immediately enriches every existing dashboard view with a station "personality." Validates the Python service architecture before betting on forecasts.

**Concrete output:** a nightly-computed `zes_station_archetype-{date}` ES index with `{station_id, archetype, peer_group_id, profile_vector}`.

### Phase 2 — Forecast as a primitive
Train a per-station daily-utilization forecaster. Features:
- Lagged utilization (1/3/7-day)
- Day-of-week one-hot
- Station static features (city, connector_type, max_power_kw, archetype from Phase 1)
- Trailing 7-day mean nearby_avg_utilization

Model: gradient boosting (LightGBM) on a long-format table; one row per (station, date). Holdout the last 2 weeks for validation. Target: MAE on `charging_utilization_pct`.

**Concrete output:** nightly `zes_station_forecast-{date}` ES index with `{station_id, target_date, predicted_utilization_pct, prediction_interval_low/high}` for the next 7 target dates.

### Phase 3 — Derived metrics on top of forecast
Compute and expose **A1, A2, A3**. These are just transformations of the forecast index. Wire them into the existing recommendation pipeline in `ZesStationPerformanceIndexService.java` — swap `charging_utilization_pct` for `predicted_utilization_7d` in the `demand_supply_ratio` formula (or add it as a parallel metric for comparison).

### Phase 4 — Operations: health_score
Once a few weeks of forecasts exist to compare against actuals, build residual distributions per station and ship **B1 health_score** as an alerts feed. Defer **B2 volatility_index** until there's a clear consumer.

---

## Architecture sketch

```
+---------------------------+        +-----------------------+
|  Spring Boot (existing)   |        |  Python FastAPI (new) |
|  - exposes /zes/* APIs    |  HTTP  |  - /train             |
|  - reads ES indices       |<------>|  - /predict           |
|  - schedulers @ 00:15     |        |  - reads ES directly  |
+---------------------------+        +-----------------------+
            |                                   |
            +-----> Elasticsearch <-------------+
                    - zes_connector_transform-*
                    - zes_station_performance-*
                    - zes_station_archetype-*    (Phase 1, new)
                    - zes_station_forecast-*     (Phase 2, new)
```

Two viable integration patterns:
1. **Batch:** Python writes prediction indices nightly; Spring Boot just reads them. Simpler, no inference latency, no online dependency. **Recommended.**
2. **Online:** Spring Boot calls Python `/predict` on demand. Only worth it for use cases needing per-request inference (none yet).

Go with batch.

---

## Critical files to modify (when implementation starts)

- **New:** `model_training/` directory with FastAPI app, training script, model artifacts. Likely a `model_training/app.py`, `model_training/train.py`, `model_training/features.py`.
- **New:** `charging-tracker/src/main/java/com/charging/zes/elastic/ZesStationArchetypeService.java` (Phase 1 reader)
- **New:** `charging-tracker/src/main/java/com/charging/zes/elastic/ZesStationForecastService.java` (Phase 2 reader)
- **Modify:** `ZesStationPerformanceIndexService.java:104-277` — add optional predicted-utilization input to `demand_supply_ratio` calc (Phase 3)
- **Modify:** `charging-dashboard/src/pages/ZesStatsPage.jsx` and `charging-dashboard/src/api/zesApi.js` — surface archetype badge and trend direction
- **Reuse:** existing time-slot aggregation in `ZesConnectorTransformAnalysisService.java:59-157` to build the 168-dim profile vectors for clustering (or replicate the aggregation in pandas reading from ES directly)

---

## Verification plan (per phase)

- **Phase 1:** sanity-check archetypes by eyeballing 3-5 stations per cluster — do they actually have similar curves? Plot the per-cluster average profile alongside individual members.
- **Phase 2:** time-based holdout — train on weeks 1-6, validate on weeks 7-8. Report MAE & coverage of prediction intervals. Compare against trivial baseline (last week's same-day-of-week utilization); the model needs to beat this to justify itself.
- **Phase 3:** A/B the overutilized list before/after swapping in `predicted_utilization_7d`. Stations whose rank changes a lot are the interesting cases — review them manually.
- **Phase 4:** for `health_score`, take 10 known-offline stations (use the existing "Offline" status as ground truth) and confirm they score in the top decile of anomalies.

---

## Open question

**Which phase to start with?** Recommendation: Phase 1 (archetypes) — cheapest validation that the Python service architecture works, ships value on its own, and provides a feature for Phase 2's forecaster. If the real goal is forward-looking expansion decisions, jumping straight to Phase 2 is defensible.
