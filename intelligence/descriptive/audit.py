"""Coverage audit: quantify how sparse the source data is across (station, dow, hour).

Generates reports/coverage_YYYY-MM-DD.md plus PNG heatmaps. This is the gate
that decides whether the optional clustering step is worth running.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

import config
from ingestion.extract import fetch_station_aggregates, list_source_indices, make_client

DOW_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def _ensure_report_dir() -> Path:
    out = config.REPORTS_DIR / date.today().isoformat()
    out.mkdir(parents=True, exist_ok=True)
    return out


def _coverage_heatmap(long_df: pd.DataFrame, out_path: Path) -> dict:
    """Heatmap of (dow x hour) -> number of distinct stations observed in that cell."""
    pivot = (
        long_df.assign(observed=long_df["minutes_observed"] > 0)
        .groupby(["dow", "hour"])["station_id"]
        .nunique()
        .unstack(fill_value=0)
        .reindex(index=range(7), columns=range(24), fill_value=0)
    )

    fig, ax = plt.subplots(figsize=(12, 4))
    im = ax.imshow(pivot.values, aspect="auto", cmap="viridis")
    ax.set_yticks(range(7))
    ax.set_yticklabels(DOW_NAMES)
    ax.set_xticks(range(24))
    ax.set_xticklabels([f"{h:02d}" for h in range(24)])
    ax.set_xlabel("Hour of day")
    ax.set_title("Unique stations observed per (dow x hour) cell")
    plt.colorbar(im, ax=ax, label="n_stations")
    fig.tight_layout()
    fig.savefig(out_path, dpi=110)
    plt.close(fig)

    total_cells = 7 * 24
    populated_cells = int((pivot.values > 0).sum())
    return {
        "populated_cells": populated_cells,
        "total_cells": total_cells,
        "global_cell_coverage_pct": 100.0 * populated_cells / total_cells,
        "pivot": pivot,
    }


def _per_station_distribution(long_df: pd.DataFrame, out_path: Path) -> dict:
    obs = long_df[long_df["minutes_observed"] > 0]
    per_station_hours = obs.groupby("station_id")["minutes_observed"].sum() / 60.0
    per_station_distinct_cells = obs.groupby("station_id").size()
    per_station_dows = obs.groupby("station_id")["dow"].nunique()

    fig, axes = plt.subplots(1, 3, figsize=(15, 4))
    axes[0].hist(per_station_hours, bins=40, color="#3b82f6")
    axes[0].set_title("Total observed hours per station")
    axes[0].set_xlabel("hours")
    axes[1].hist(per_station_distinct_cells, bins=40, color="#10b981")
    axes[1].set_title("Distinct (dow x hour) cells per station")
    axes[1].set_xlabel("cells (max 168)")
    axes[2].hist(per_station_dows, bins=range(0, 9), color="#f59e0b")
    axes[2].set_title("Distinct DOWs observed per station")
    axes[2].set_xlabel("days-of-week (max 7)")
    fig.tight_layout()
    fig.savefig(out_path, dpi=110)
    plt.close(fig)

    return {
        "n_stations": int(per_station_hours.shape[0]),
        "median_observed_hours": float(per_station_hours.median()),
        "p10_observed_hours": float(per_station_hours.quantile(0.10)),
        "p90_observed_hours": float(per_station_hours.quantile(0.90)),
        "median_distinct_cells": float(per_station_distinct_cells.median()),
        "median_distinct_dows": float(per_station_dows.median()),
        "stations_all_7_dows": int((per_station_dows == 7).sum()),
        "stations_geq_4_dows": int((per_station_dows >= 4).sum()),
        "stations_geq_100h": int((per_station_hours >= 100).sum()),
    }


def _decision(stats: dict) -> tuple[str, str]:
    crit = config.CLUSTERING_VIABILITY
    median_hours_ok = stats["median_observed_hours"] >= crit["min_median_observed_hours"]
    median_dows_ok = stats["median_distinct_dows"] >= crit["min_distinct_dows"]
    if median_hours_ok and median_dows_ok:
        return ("GREENLIGHT", "Median station has enough coverage to support low-k clustering on robust features.")
    reasons = []
    if not median_hours_ok:
        reasons.append(f"median observed hours = {stats['median_observed_hours']:.1f} (need ≥ {crit['min_median_observed_hours']})")
    if not median_dows_ok:
        reasons.append(f"median distinct DOWs = {stats['median_distinct_dows']:.1f} (need ≥ {crit['min_distinct_dows']})")
    return ("SKIP", "Clustering not recommended yet — " + "; ".join(reasons))


def _index_coverage(client, indices: list[str]) -> pd.DataFrame:
    if not indices:
        return pd.DataFrame(columns=["index", "date", "docs"])
    rows_resp = client.cat.indices(index=",".join(indices), h="index,docs.count", format="json")
    counts = {r["index"]: int(r["docs.count"]) for r in rows_resp}
    rows = []
    for idx in indices:
        day = idx.split("-", 1)[1]
        rows.append({"index": idx, "date": day, "docs": counts.get(idx, 0)})
    return pd.DataFrame(rows).sort_values("date")


def run_audit(lookback_days: int = config.TRAINING_LOOKBACK_DAYS) -> Path:
    out_dir = _ensure_report_dir()
    client = make_client()

    indices = list_source_indices(client, lookback_days)
    if not indices:
        raise RuntimeError(f"No source indices in last {lookback_days} days")
    idx_df = _index_coverage(client, indices)

    print(f"[audit] pulling {len(indices)} indices...")
    long_df, meta_df = fetch_station_aggregates(client, lookback_days=lookback_days, indices=indices)
    print(f"[audit] {len(long_df):,} cells, {long_df['station_id'].nunique()} stations")

    heat_path = out_dir / "coverage_heatmap.png"
    hist_path = out_dir / "per_station_distribution.png"
    heat_stats = _coverage_heatmap(long_df, heat_path)
    station_stats = _per_station_distribution(long_df, hist_path)

    verdict, rationale = _decision(station_stats)

    # Calendar gap stats
    dates_present = sorted(set(idx_df["date"]))
    first_day = pd.to_datetime(dates_present[0])
    last_day = pd.to_datetime(dates_present[-1])
    span_days = (last_day - first_day).days + 1
    missing_days = span_days - len(dates_present)
    dow_counts = pd.Series([pd.to_datetime(d).weekday() for d in dates_present]).value_counts().sort_index()
    dow_table = "| DOW | Days covered |\n|---|---|\n" + "\n".join(
        f"| {DOW_NAMES[i]} | {int(dow_counts.get(i, 0))} |" for i in range(7)
    )

    # Hour coverage from the heatmap
    pivot = heat_stats["pivot"]
    hour_coverage = (pivot.values > 0).sum(axis=0)  # how many dows touched each hour
    hour_table = "| Hour | DOWs with data |\n|---|---|\n" + "\n".join(
        f"| {h:02d} | {int(hour_coverage[h])} |" for h in range(24)
    )

    report = f"""# ZES coverage audit — {date.today().isoformat()}

**Lookback:** {lookback_days} days
**Source indices found:** {len(indices)} (calendar span {span_days} days, **{missing_days} missing days**)
**Date range:** {dates_present[0]} → {dates_present[-1]}

## Verdict

**{verdict}** — {rationale}

## Per-station coverage

| Metric | Value |
|---|---|
| Stations seen | {station_stats['n_stations']:,} |
| Median observed hours | {station_stats['median_observed_hours']:.1f} |
| P10 / P90 observed hours | {station_stats['p10_observed_hours']:.1f} / {station_stats['p90_observed_hours']:.1f} |
| Median distinct (dow×hour) cells | {station_stats['median_distinct_cells']:.0f} / 168 |
| Median distinct DOWs | {station_stats['median_distinct_dows']:.1f} / 7 |
| Stations with all 7 DOWs covered | {station_stats['stations_all_7_dows']:,} |
| Stations with ≥4 DOWs | {station_stats['stations_geq_4_dows']:,} |
| Stations with ≥100 observed hours | {station_stats['stations_geq_100h']:,} |

![Per-station distribution]({hist_path.name})

## Global (dow × hour) cell coverage

- **Populated cells:** {heat_stats['populated_cells']} / {heat_stats['total_cells']} ({heat_stats['global_cell_coverage_pct']:.1f}%)

![Coverage heatmap]({heat_path.name})

## Calendar coverage — days per DOW

{dow_table}

## Within-day coverage — DOWs touched per hour

{hour_table}

## Decision rule

- **GREENLIGHT** if median station has ≥{config.CLUSTERING_VIABILITY['min_median_observed_hours']}h observed across ≥{config.CLUSTERING_VIABILITY['min_distinct_dows']} distinct DOWs.
- Otherwise **SKIP** clustering — ship robust features only, defer archetypes until data improves.

## How to act on this

- If GREENLIGHT → proceed with `train.py` (low-k k-means on robust features).
- If SKIP → ship `features.py` + `assign.py` only. Stations still get the 6 robust features in ES; `archetype_v1` field is omitted.
- Either way, surface `observation_quality` to the dashboard so consumers know which rows to trust.
"""

    report_path = out_dir / "coverage.md"
    report_path.write_text(report)
    print(f"[audit] wrote {report_path}")
    print(f"\n=== VERDICT: {verdict} ===\n{rationale}")
    return report_path


if __name__ == "__main__":
    import sys
    lookback = int(sys.argv[1]) if len(sys.argv) > 1 else config.TRAINING_LOOKBACK_DAYS
    run_audit(lookback)
