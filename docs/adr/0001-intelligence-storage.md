# ADR 0001 — Move the intelligence layer off Elasticsearch onto a Parquet lake with DuckDB

**Status:** Accepted (design), not yet implemented
**Date:** 2026-08-17
**Supersedes:** the "Elasticsearch is the integration bus" rule, for L4 only

---

## Context

Elasticsearch currently plays two incompatible roles.

**Role 1 — serving store.** The dashboard issues filtered, paginated, ad-hoc
queries: connector search by province × type × status × time × address ×
environment, performance search with six optional predicates and arbitrary sort.
This is what ES is *for*, and it does it well.

**Role 2 — ML substrate.** Every training run scrolls millions of documents out
of ES into pandas, and every model output is written back as a new index per day:
`features`, `forecast`, `opportunity`, `hourly_performance`, `hourly_forecast` —
five families × 365 days ≈ **1,825 indices per year**, each needing an index
template, a mapping, bulk-write error handling, and an `available-dates`
enumeration endpoint so the UI can discover it.

Role 2 is where the friction is:

- **Row-oriented reads.** A scroll returns whole `_source` documents. Training
  uses roughly 8 of ~40 fields, but pays to deserialize all of them. There is no
  column pruning and no predicate pushdown into a columnar layout.
- **Index-per-day at small sizes.** Each index here is 1–5 MB but carries a full
  shard: its own Lucene segments and an entry in cluster state. Thousands of tiny
  shards is a well-known way to degrade an ES cluster, and it buys nothing —
  nobody searches a features index.
- **Write-back is pure overhead.** Mappings and bulk-indexing exist so that data
  no human ever searches can be read back by station id and date — a point
  lookup.
- **No immutability contract.** ES has no way to express "this partition is
  final." So results cannot be cached, versioned, or checksummed, and every
  consumer re-derives what it needs.

Measured on the live cluster (2026-08-17): **2.52M documents, 350 MB total**,
largest index ~5 MB, 90-day training window ≈ 270 MB.

---

## Decision

**Split the two roles.** Elasticsearch keeps role 1 and loses role 2 entirely.

1. **A Parquet lake becomes the analytical substrate** — immutable, date-partitioned
   columnar files, written once per partition.
2. **DuckDB becomes the compute engine** — embedded, vectorized SQL over those
   files, replacing hand-written ES aggregation JSON for feature building.
3. **Elasticsearch becomes write-once-per-day and read-only thereafter**, fed
   only by L2 materialization. No ML process ever writes to it again.
4. **The five ML index families move to the lake** and are served to the
   dashboard by the Python service.

### Why not Apache Spark

Spark is the right answer to this problem shape at a different scale. At 350 MB
it is the wrong tool, and would make things measurably worse:

- Cluster coordination, JVM startup, task scheduling and shuffle serialization
  are fixed costs that dominate when the data fits in RAM many times over. Jobs
  would run *slower* than today's pandas.
- It is another service to run, monitor and upgrade on a single always-on VM
  that currently has four containers.
- Its programming model buys you nothing you lack: you have no distributed
  join, no out-of-core requirement, no streaming ingest at volume.

The important point: **choosing Parquet is not choosing against Spark.** Spark
reads Hive-partitioned Parquet natively. If this data grows past one machine —
multi-country, per-minute granularity, several networks — you point Spark at the
same `lake/` directory and nothing else changes. Parquet is the decision that
keeps Spark available; adopting Spark now is the decision that costs you
performance today for capability you would not use.

### Why not the alternatives

| Option | Why not |
|---|---|
| **Single rolling ES index** (date field instead of index-per-day) | Fixes the index sprawl, but keeps ES in the training loop — the actual complaint. Half a fix. |
| **Postgres / TimescaleDB** | Correctness and joins are excellent, and it could replace H2 too. But it is a service to operate, and row-store scans for training still lose to columnar by a wide margin. Reconsider if authoritative state outgrows H2 — that is a different problem from this one. |
| **Keep everything as is** | The write-back cost and shard sprawl grow linearly with time, and every new model output adds another index family. |

---

## Design

### Layout

Hive-style partitioning, so DuckDB, pyarrow, Polars and Spark all recognise the
partition column without configuration:

```
lake/                                    ← Docker volume, backed up like es_data
├── connector_slots/date=2026-08-17/part-0.parquet   ← mirror of L2 output (input)
├── station_perf/  date=2026-08-17/part-0.parquet    ← mirror of L2 output (input)
├── features/      date=2026-08-17/part-0.parquet    ← L4 output
├── forecast/      date=2026-08-17/part-0.parquet
├── opportunity/   date=2026-08-17/part-0.parquet
├── hourly_observed/date=2026-08-17/part-0.parquet
└── hourly_forecast/date=2026-08-17/part-0.parquet
```

Each partition directory also gets a `_manifest.json`:

```json
{ "rows": 1818, "written_at": "...", "feature_version": "1",
  "inputs": ["station_perf/date=2026-08-16"], "git_sha": "…", "duration_s": 4.1 }
```

The manifest is what makes the pipeline idempotent and auditable: a stage can
skip a partition whose manifest is newer than all its inputs', and a model's
provenance is recoverable months later.

### Reads become SQL

```python
con.execute("""
    SELECT station_id, dow_target, util_t, nearby_stations_count, …
    FROM read_parquet('lake/features/*/*.parquet', hive_partitioning := true)
    WHERE date BETWEEN ? AND ?
""", [start, end]).arrow()          # zero-copy to Arrow → pandas
```

Partition pruning skips untouched dates; column pruning reads only the projected
columns; the result lands in Arrow with no serialization step. The expected gain
is an order of magnitude or more over scroll, mostly from *not reading* bytes —
though the honest framing is that at 350 MB both are fast enough, and the real
wins are the ones below.

### What each store owns afterwards

| Data | Store | Why |
|---|---|---|
| `zes_connector_transform-{D}` | **ES** | genuine ad-hoc filtered search + pagination |
| `zes_station_performance-{D}` | **ES** | six-predicate search with arbitrary sort |
| `zes_station_environment` | **ES** | static enrichment lookup |
| `connector_slots`, `station_perf` | **lake** | immutable mirror, the training input |
| `features`, `forecast`, `opportunity`, `hourly_observed`, `hourly_forecast` | **lake** | never searched; read by (station_id, date) — a point lookup |

The rule to keep this from drifting: **searchable → Elasticsearch; computable →
lake.** If a new dataset needs full-text or many-predicate filtering, it goes to
ES; if it is scanned, joined or aggregated, it goes to the lake.

### Serving the intelligence pages

The 14 `/forecast/*`, `/opportunity/*`, `/pricing/*` endpoints move from
`core-api` to the Python service, backed by DuckDB. **nginx routes by path
prefix, so the console needs no changes at all:**

```nginx
location /zes/availability/forecast/     { proxy_pass http://intelligence:8001/; }
location /zes/availability/opportunity/  { proxy_pass http://intelligence:8001/; }
location /zes/availability/pricing/      { proxy_pass http://intelligence:8001/; }
location /zes/                           { proxy_pass http://core-api:8080; }
```

`zesApi.js` keeps its URLs verbatim. This deletes the whole Java `intelligence/`
layer — four services plus two controllers, ~1,200 lines whose only job was to
read back what Python had just written. That deletion is the clearest signal that
the write-back was accidental complexity rather than architecture.

### Automation

Replace the `curl`-chain in `deploy/daily-pipeline.sh` with an **asset-based
runner** (~200 lines, no new service). Each stage declares what it produces, what
it consumes, and how:

```python
@asset(name="forecast", inputs=["features", "station_perf"], partition="date")
def build_forecast(date, ctx): ...
```

The runner topologically sorts assets, skips partitions already fresh, and takes
`--from/--to` for backfill. Three properties follow directly, all of which the
current shell script lacks:

- **Idempotent** — re-running a date is a no-op unless inputs changed.
- **Restartable** — a failure resumes at the failed asset, not the top.
- **Backfillable** — historical repair is the same code path as the daily run.

If a UI and alerting become worth it later, **Dagster** is the natural step up
because its software-defined-asset model is exactly this shape; Airflow's
task-centric model would be a worse fit. Do not start there — 200 lines of
runner is proportionate to seven assets on one VM.

### Model lifecycle (closes roadmap R4)

With the lake in place, promotion becomes mechanical: train → score on holdout →
compare against the naive baseline and the incumbent's recorded MAE → write
`models/<version>/` with a manifest → repoint `current` **only if it improves**.
The backtest already computes the metric; today nothing retains it or gates on
it.

---

## Migration plan

Each step ships independently and is reversible on its own.

| # | Step | Risk | Payoff |
|---|---|---|---|
| 1 | Add `kernel/lake.py` (paths, manifests, read/write helpers) + DuckDB dependency. One-shot backfill of existing ES history → Parquet. | none — additive | the lake exists, nothing depends on it |
| 2 | Switch **training reads** to the lake (`features/`, `forecast/data_builder`). Verify identical model metrics on the same window. | low — verifiable by comparing metrics | ES leaves the training loop |
| 3 | Switch **ML writes** to Parquet, temporarily dual-writing to ES. | low — nothing has stopped reading ES yet | outputs versioned and immutable |
| 4 | Move the 14 endpoints to FastAPI + nginx path routing. Delete the Java `intelligence/` layer and the dual-write. | medium — a real cutover | ~1,200 lines deleted, single reader |
| 5 | Replace `daily-pipeline.sh` with the asset runner. | low | idempotent, restartable, backfillable |
| 6 | Drop the five ML index families from ES. | low — already unread | ES is static and much smaller |

Step 2 alone delivers most of what you asked for and is nearly risk-free: it is
verifiable by checking that the retrained model produces the same numbers.

---

## Consequences

**Gained**

- Elasticsearch is static: written once per day by L2, never by ML.
- ~1,825 indices/year of sprawl removed; ES shrinks to the three families the
  dashboard actually searches.
- Feature engineering becomes SQL instead of hand-built aggregation JSON.
- Immutable, manifested partitions make model provenance and backfill trivial.
- The Java `intelligence/` layer disappears.
- Forward-compatible: Spark, Polars or Trino can read the same files unchanged.

**Paid**

- Two storage systems instead of one. Mitigated by a single crisp rule
  (searchable → ES, computable → lake) and by the fact that they already are two
  systems in effect — one just happens to be spelled "Elasticsearch" twice.
- The Python service becomes a serving dependency, not just a batch job. At
  single-operator scale this is fine; it does mean its uptime now matters.
- The lake needs backup. Mitigated: like ES, it is *derived* state — rebuildable
  from H2 within retention and from ES/earlier partitions outside it.
- The "one integration bus" story in `ARCHITECTURE.md` becomes "two stores with
  distinct roles." That is a more honest description of what the system does.

**Revisit when**

The lake exceeds roughly 50 GB, or one partition's compute exceeds RAM. Then move
to Polars streaming or Spark — the layout does not change, only the engine.
