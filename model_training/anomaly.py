"""Per-station anomaly detection: today's feature profile vs the station's own baseline.

For each station we compute the 5 robust features twice:
  - over the BASELINE window (historical days, excluding today)
  - over the TODAY window (most recent N days)

Then for each feature we score deviation using:
  z = (today_value - baseline_mean) / max(baseline_std, eps)

Each station's composite anomaly_score is the L2 norm of its per-feature z-scores
(robust to a single noisy feature pushing the alert).
"""
from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import Optional

import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk, scan

import config
from extract import (
    fetch_station_aggregates,
    list_available_dates,
    list_source_indices,
    make_client,
)
from features import compute_features

ANOMALY_FEATURES = [
    "charging_ratio",
    "peak_hour",
    "peakiness",
    "day_night_ratio",
    "weekend_weekday_ratio",
]

Z_HIGH = 3.0
Z_MEDIUM = 2.0
MIN_BASELINE_DAYS = 5  # below this, scoring is too noisy

# Per-feature minimum std floor — keeps tiny per-station variability from
# inflating z-scores. Tuned to each feature's natural scale.
STD_FLOOR = {
    "charging_ratio": 0.05,           # 5 pp on a 0-1 ratio
    "peak_hour": 1.5,                 # ±1.5 hours
    "peakiness": 0.50,                # ±0.5 in a ratio that ranges 1-10
    "day_night_ratio": 0.50,
    "weekend_weekday_ratio": 0.50,
}

ANOMALY_INDEX_PREFIX = "zes_station_anomaly"

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "station_id": {"type": "keyword"},
            "station_name": {"type": "keyword"},
            "city_name": {"type": "keyword"},
            "anomaly_score": {"type": "float"},
            "severity": {"type": "keyword"},
            "n_features_anomalous": {"type": "integer"},
            "today_window_days": {"type": "integer"},
            "baseline_window_days": {"type": "integer"},
            "today_date": {"type": "date"},
            "computed_at": {"type": "date"},
            "feature_version": {"type": "keyword"},
            "per_feature": {
                "type": "nested",
                "properties": {
                    "feature": {"type": "keyword"},
                    "baseline_value": {"type": "float"},
                    "today_value": {"type": "float"},
                    "deviation": {"type": "float"},
                    "z_score": {"type": "float"},
                    "direction": {"type": "keyword"},
                    "level": {"type": "keyword"},
                },
            },
        }
    }
}


def _compute_baseline_stats(
    client: Elasticsearch,
    baseline_dates: list[date],
) -> dict:
    """For each station, compute mean & std of each feature across daily snapshots.

    We compute features ONCE per individual day in the baseline window, then take
    mean/std of those per-day values. This gives us a real distribution to
    z-score against, instead of a single aggregate point.
    """
    daily: dict[date, pd.DataFrame] = {}
    for d in baseline_dates:
        indices = list_source_indices(client, start_date=d, end_date=d)
        if not indices:
            continue
        long_df, meta_df = fetch_station_aggregates(client, indices=indices)
        if long_df.empty:
            continue
        feat_df = compute_features(long_df, meta_df)
        daily[d] = feat_df.set_index("station_id")[ANOMALY_FEATURES]
        print(f"  baseline {d}: {len(feat_df)} stations")

    if not daily:
        return {"mean": pd.DataFrame(), "std": pd.DataFrame(), "n_days": 0}

    stacked = pd.concat(daily.values(), keys=daily.keys(), names=["day", "station_id"])
    means = stacked.groupby(level="station_id").mean()
    stds = stacked.groupby(level="station_id").std(ddof=0).fillna(0.0)
    return {"mean": means, "std": stds, "n_days": len(daily)}


def _compute_today_features(
    client: Elasticsearch,
    today_dates: list[date],
) -> pd.DataFrame:
    indices = []
    for d in today_dates:
        indices.extend(list_source_indices(client, start_date=d, end_date=d))
    if not indices:
        raise RuntimeError(f"No source indices for today window {today_dates}")
    long_df, meta_df = fetch_station_aggregates(client, indices=indices)
    feat_df = compute_features(long_df, meta_df)
    return feat_df.set_index("station_id")


def _level(z: float) -> str:
    abs_z = abs(z)
    if abs_z >= Z_HIGH:
        return "HIGH"
    if abs_z >= Z_MEDIUM:
        return "MEDIUM"
    return "OK"


def _ensure_index(client: Elasticsearch, index_name: str) -> None:
    if not client.indices.exists(index=index_name):
        client.indices.create(index=index_name, body=INDEX_MAPPING)


def detect_anomalies(
    output_date: Optional[date] = None,
    baseline_days: int = 90,
    today_window_days: int = 1,
) -> dict:
    """Main entry point. Returns a summary dict."""
    client = make_client()
    available = list_available_dates(client)
    if not available:
        return {"error": "no source indices"}

    today_anchor = output_date or available[-1]
    today_dates = [d for d in available if (today_anchor - d).days < today_window_days]
    if not today_dates:
        return {"error": f"no source data in today window ending {today_anchor}"}

    earliest_baseline = today_dates[0] - timedelta(days=baseline_days)
    baseline_dates = [d for d in available if earliest_baseline <= d < today_dates[0]]
    if len(baseline_dates) < MIN_BASELINE_DAYS:
        return {
            "error": f"insufficient baseline (need ≥{MIN_BASELINE_DAYS} historical days)",
            "baseline_dates_found": [d.isoformat() for d in baseline_dates],
        }

    print(f"[anomaly] today window: {[d.isoformat() for d in today_dates]}")
    print(f"[anomaly] baseline window ({len(baseline_dates)} days): {baseline_dates[0]} → {baseline_dates[-1]}")

    baseline = _compute_baseline_stats(client, baseline_dates)
    today_df = _compute_today_features(client, today_dates)

    # Inner join — only score stations present in both windows
    common = baseline["mean"].index.intersection(today_df.index)
    print(f"[anomaly] {len(common)} stations scoreable")

    out_rows = []
    for sid in common:
        per_feature = []
        z_scores = []
        n_anom = 0
        for feat in ANOMALY_FEATURES:
            b_mean = baseline["mean"].loc[sid, feat]
            b_std = baseline["std"].loc[sid, feat]
            t_val = today_df.loc[sid, feat]
            if pd.isna(b_mean) or pd.isna(t_val):
                continue
            deviation = float(t_val - b_mean)
            std_floor = STD_FLOOR.get(feat, 0.1)
            z = deviation / max(float(b_std), std_floor)
            lvl = _level(z)
            if lvl != "OK":
                n_anom += 1
            direction = "SPIKE" if z > 0 else "DROP"
            z_scores.append(z)
            per_feature.append({
                "feature": feat,
                "baseline_value": float(b_mean),
                "today_value": float(t_val),
                "deviation": deviation,
                "z_score": float(z),
                "direction": direction,
                "level": lvl,
            })
        if not z_scores:
            continue
        composite = float(np.linalg.norm(z_scores))
        severity = "HIGH" if composite >= Z_HIGH else "MEDIUM" if composite >= Z_MEDIUM else "OK"

        meta_row = today_df.loc[sid]
        out_rows.append({
            "station_id": sid,
            "station_name": meta_row.get("station_name"),
            "city_name": meta_row.get("city_name"),
            "anomaly_score": composite,
            "severity": severity,
            "n_features_anomalous": int(n_anom),
            "today_window_days": today_window_days,
            "baseline_window_days": len(baseline_dates),
            "today_date": today_anchor.isoformat(),
            "computed_at": datetime.utcnow().isoformat() + "Z",
            "feature_version": config.FEATURE_VERSION,
            "per_feature": per_feature,
        })

    index_name = f"{ANOMALY_INDEX_PREFIX}-{today_anchor.isoformat()}"
    _ensure_index(client, index_name)

    def _docs():
        for r in out_rows:
            yield {"_index": index_name, "_id": r["station_id"], "_source": r}

    n_ok, errs = bulk(client, _docs(), chunk_size=500, refresh=True)

    sev_counts = pd.Series([r["severity"] for r in out_rows]).value_counts().to_dict()
    return {
        "count_written": n_ok,
        "errors": len(errs) if isinstance(errs, list) else 0,
        "index": index_name,
        "today_anchor": today_anchor.isoformat(),
        "baseline_days_used": baseline["n_days"],
        "severity_counts": sev_counts,
    }


if __name__ == "__main__":
    import json
    import sys
    out_date = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else None
    res = detect_anomalies(output_date=out_date)
    print(json.dumps(res, indent=2, default=str))
