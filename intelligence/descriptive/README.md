# `descriptive/` — Descriptive ML

**Status: 🔄 partially implemented — runs and writes, but has no consumer**
**Roadmap: `ARCHITECTURE.md § R2` — surface it or retire it**

## Responsibility

The descriptive half of the intelligence layer: characterise stations from
observed history *without* forecasting. In the intended progression —
descriptive → predictive → prescriptive — this is the first stage, and the
forecast and pricing packages are the later two.

| Module | Purpose | Endpoint |
|---|---|---|
| `audit.py` | data-quality report over the training window: coverage, gaps, clustering viability | `POST /audit` |
| `assign.py` | assign each station to a behavioural cluster (k=3–4) using the trained model | `POST /assign` |
| `train.py` | fit and persist the clustering model → `models/latest.json` | `POST /train` |
| `anomaly.py` | flag stations deviating from their own 90-day baseline → `zes_station_anomaly-{D}` | `POST /anomaly` |

## Why it is marked partial

All four run and produce output. None of it reaches a user:

- No dashboard page reads these results — the UI has no clustering or anomaly view.
- `deploy/daily-pipeline.sh` does not call them, so nothing refreshes on a schedule.
- `zes_station_anomaly-{D}` has a declared name in the catalog and no Java reader.

They are reachable only by a manual `POST`. Code that executes correctly but is
unreachable is the clearest signal of an architecture drifting from its
documentation, which is why R2 asks for a decision rather than more features.

## Why it was kept

Two reasons. The progression descriptive → predictive → prescriptive is part of
the platform's design story, and this is the only implementation of its first
stage. And `audit.py` retains standalone value: it answers "is there enough
signal in this window to model at all," which is the question to ask *before*
retraining a forecast, independent of whether clustering ever ships.

`validate.py` — a fifth module in this family with zero importers and no endpoint
— was deleted in the same refactor. The distinction: these four are wired to the
API, that one was wired to nothing.

## Resolving R2 — either direction is acceptable

**Surface it.** Add a page that reads cluster assignments and anomalies, put
`/anomaly` in the daily pipeline, and add a Java reader for
`zes_station_anomaly-{D}`. Anomaly detection is the strongest candidate: it maps
onto a real operator need (which stations broke today) that no current page
answers.

**Retire it.** Delete this package and its four endpoints. The forecast layer
subsumes the predictive value, and `zes_station_anomaly-*` indices can be dropped.

What is not acceptable is leaving it at 🔄 indefinitely — that is how a
blueprint stops describing the code.

## Contracts, while it exists

- Reads via `ingestion.extract` and `features.snapshot` like every other module.
- Writes only `zes_station_anomaly-{D}`, declared in `kernel/index_catalog.py`.
- Model artifacts go to `config.MODELS_DIR` behind the `latest.json` pointer —
  the same convention the forecast model uses (`latest_forecast.json`).
