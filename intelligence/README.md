# `intelligence/` — Intelligence layer (L4, Python side)

**Status: ✅ forecast + pricing + opportunity in production · 🔄 descriptive ML has no UI**

FastAPI service on port 8001. Computes everything the Java backend cannot, and
hands results back through Elasticsearch — the two runtimes never call each other
directly.

## Layout

Package names mirror the Java layers, so the same architecture reads the same way
in both runtimes (`ARCHITECTURE.md` describes the whole).

```
app.py                        API edge — one endpoint per pipeline stage
config.py                     shared config; MODELS_DIR / REPORTS_DIR anchor here
kernel/index_catalog.py       the ES naming contract (mirror of Java IndexCatalog)
ingestion/extract.py          ES readers — the only module that queries raw indices
features/snapshot.py          feature engineering → zes_station_features-{D}
intelligence/
  forecast/                   ✅ 1-day-ahead utilization model + backtest
  opportunity/score.py        ✅ 0-100 growth-potential score
  pricing/                    ✅ hourly demand data + economic assumptions
  descriptive/                🔄 clustering, audit, anomaly — no dashboard surface
docs/ml-roadmap.md            original phased ML plan (historical, still useful)
```

`config.py` must stay at the package root: `MODELS_DIR` resolves relative to its
own location and maps to the `ml_models` Docker volume at `/app/models`.

## Commands

```bash
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
ES_HOST=http://localhost:9200 uvicorn app:app --reload --port 8001

python -m pricing.test_demand_model    # only test suite; plain asserts
```

Typical first run against a populated cluster:

```bash
curl -X POST "localhost:8001/features/build?date=2026-06-15"
curl -X POST "localhost:8001/forecast/train"
curl -X POST "localhost:8001/forecast/predict?target_date=2026-06-16"
```

In production nginx exposes this service under `/ml/` with the prefix stripped,
and `deploy/daily-pipeline.sh` drives it from a host cron at 00:30.

## Ownership rule

This pipeline is the **sole writer** of five index families:

| Index | Written by |
|---|---|
| `zes_station_features-{D}` | `features/snapshot.py` |
| `zes_station_forecast-{D}` | `forecast/predict.py` |
| `zes_station_opportunity-{D}` | `opportunity/score.py` |
| `zes_station_hourly_performance-{D}` | `pricing/hourly_data_builder.py` |
| `zes_station_hourly_forecast-{D}` | `pricing/hourly_predict.py` |

It **reads but never writes** the Java-owned stages
(`zes_connector_transform-*`, `zes_station_performance-*`). Writing one of those
from here would give a day two sources of truth. Declare any new stage in
`kernel/index_catalog.py` — and in the Java mirror — before writing it.

## Ordering constraint

`pricing/hourly_predict.py` disaggregates the *daily* forecast into
hours, so `forecast/predict` must run before `pricing/run-all`. The daily script
enforces this; if you trigger stages by hand, keep the order.

## Module notes

- **`ingestion/extract.py`** is the only module that talks to raw indices. It is
  imported by nearly everything else, which is intentional: one place knows the
  document shapes, so a mapping change has one blast radius.
- **`forecast/`** — trees over consecutive `(t, t+1)` performance
  pairs joined to the latest feature snapshot. `sanity_check.py` compares model
  families against the naive baseline and persists nothing; use it before
  concluding a model change helped.
- **`pricing/`** — read
  [its README](pricing/README.md) before changing anything. The
  decision is human-in-the-loop; this package only prepares data and publishes
  assumptions.
- **`descriptive/`** — see
  [its README](descriptive/README.md). Runs, writes results, has no
  UI, not in the daily pipeline.
