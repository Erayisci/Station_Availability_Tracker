"""Build training / inference matrices for 1-day utilization forecast.

Design (committed approach):
  - X comes from 3 sources, joined per training row:
      1. zes_station_features-{latest}  → station character (slow-moving, single snapshot)
      2. zes_station_performance-{t}    → today's daily snapshot + supply context
      3. calendar(t+1)                  → target-day day-of-week / weekend flag
      4. (optional) perf-{t-1, t-7}     → lag utilization, best-effort
  - y is perf-{t+1}.charging_utilization_pct

  Training rows = all consecutive (t, t+1) pairs in zes_station_performance-*.
  With ~25 perf indices (sparse), this yields ~15 valid pairs × ~1900 stations
  ≈ 25-30K training rows.

We deliberately use one features index for ALL training rows. Station character
(charging_ratio, peakiness, etc.) is a 90-day rolling slow-moving signal — using
a single recent snapshot for all rows introduces minor leakage but avoids
backfilling 25 features indices. See model_training/models/README.md for context.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Optional

import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import scan

PERF_PREFIX = "zes_station_performance"
FEAT_PREFIX = "zes_station_features"

# Pulled from zes_station_features-{latest}, station-character signals
FEATURES_NUMERIC = [
    "charging_ratio",
    "peakiness",
    "peak_hour",
    "day_night_ratio",
    "weekend_weekday_ratio",
    "observation_quality",
]

# Pulled from zes_station_performance-{t}, snapshot signals
PERF_NUMERIC = [
    "charging_utilization_pct",      # autoregressive — today's value
    "nearby_avg_utilization",
    "nearby_stations_count",
    "nearby_connectors_count",
    "connector_count",
    "effective_supply",
    "lat",
    "lng",
]

TARGET_COL = "charging_utilization_pct"


def _perf_index(d: date) -> str:
    return f"{PERF_PREFIX}-{d.isoformat()}"


def _feat_index(d: date) -> str:
    return f"{FEAT_PREFIX}-{d.isoformat()}"


def _index_exists(client: Elasticsearch, name: str) -> bool:
    return client.indices.exists(index=name)


def _scan_to_df(client: Elasticsearch, index: str, fields: list[str]) -> pd.DataFrame:
    rows = []
    for hit in scan(
        client,
        index=index,
        query={"query": {"match_all": {}}},
        _source=fields,
        size=1000,
    ):
        rows.append(hit["_source"])
    return pd.DataFrame(rows)


def _list_perf_dates(client: Elasticsearch) -> list[date]:
    resp = client.cat.indices(index=f"{PERF_PREFIX}-*", h="index", format="json")
    out = []
    for row in resp:
        try:
            d = datetime.strptime(row["index"].split("-", 1)[1], "%Y-%m-%d").date()
            out.append(d)
        except (ValueError, IndexError):
            continue
    return sorted(out)


def _list_feat_dates(client: Elasticsearch) -> list[date]:
    resp = client.cat.indices(index=f"{FEAT_PREFIX}-*", h="index", format="json")
    out = []
    for row in resp:
        try:
            d = datetime.strptime(row["index"].split("-", 1)[1], "%Y-%m-%d").date()
            out.append(d)
        except (ValueError, IndexError):
            continue
    return sorted(out)


def _calendar_features(d: date) -> dict:
    dow = d.weekday()
    return {
        "dow_target": dow,
        "is_weekend_target": int(dow >= 5),
        "is_monday_target": int(dow == 0),
        "is_friday_target": int(dow == 4),
    }


def _join_lag(
    base: pd.DataFrame, client: Elasticsearch, t: date, label: str
) -> pd.DataFrame:
    """Best-effort lag utilization join. Falls back to today's value if lag index missing."""
    col = f"util_lag_{label}"
    idx = _perf_index(t)
    if _index_exists(client, idx):
        lag = _scan_to_df(client, idx, ["station_id", TARGET_COL])
        if not lag.empty:
            lag = lag.rename(columns={TARGET_COL: col})
            base = base.merge(lag, on="station_id", how="left")
            base[col] = base[col].fillna(base[TARGET_COL])
            return base
    base[col] = base[TARGET_COL]
    return base


@dataclass
class TrainingSet:
    X: pd.DataFrame
    y: pd.Series
    meta: pd.DataFrame                # station_id, t, t_target, city_name
    feature_cols: list[str]
    features_snapshot_date: str       # which features-{date} was used
    perf_dates_used: list[str]        # consecutive pairs scanned
    stats: dict = field(default_factory=dict)


def build_training_set(client: Elasticsearch) -> Optional[TrainingSet]:
    """Walk all consecutive (t, t+1) perf pairs, build (X, y, meta) matrix."""
    perf_dates = _list_perf_dates(client)
    feat_dates = _list_feat_dates(client)

    if len(perf_dates) < 2 or not feat_dates:
        return None

    # Pick the most recent features index as the single station-character snapshot
    feat_anchor = max(feat_dates)
    features_df = _scan_to_df(
        client, _feat_index(feat_anchor),
        fields=["station_id"] + FEATURES_NUMERIC,
    )
    if features_df.empty:
        return None

    consec_pairs = [
        (a, b) for a, b in zip(perf_dates, perf_dates[1:])
        if (b - a).days == 1
    ]
    if not consec_pairs:
        return None

    all_rows = []
    perf_dates_used = []
    for t, t1 in consec_pairs:
        perf_t = _scan_to_df(
            client, _perf_index(t),
            fields=["station_id", "city_name"] + PERF_NUMERIC,
        )
        perf_t1 = _scan_to_df(
            client, _perf_index(t1),
            fields=["station_id", TARGET_COL],
        )
        if perf_t.empty or perf_t1.empty:
            continue

        # Lag joins
        perf_t = _join_lag(perf_t, client, t - timedelta(days=1), "1d")
        perf_t = _join_lag(perf_t, client, t - timedelta(days=7), "7d")

        # Calendar for t+1
        cal = _calendar_features(t1)
        for k, v in cal.items():
            perf_t[k] = v

        # Join station-character features (single snapshot)
        merged = perf_t.merge(features_df, on="station_id", how="left")

        # Attach target
        target_slim = perf_t1.rename(columns={TARGET_COL: "y"})
        merged = merged.merge(target_slim, on="station_id", how="inner")

        merged["t"] = t.isoformat()
        merged["t_target"] = t1.isoformat()
        all_rows.append(merged)
        perf_dates_used.append(f"{t.isoformat()}->{t1.isoformat()}")

    if not all_rows:
        return None
    full = pd.concat(all_rows, ignore_index=True)
    full = full.dropna(subset=["y"])

    meta = full[["station_id", "city_name", "t", "t_target"]].copy()
    y = full["y"]
    X = full.drop(columns=["station_id", "city_name", "t", "t_target", "y"])

    # Coerce numeric, NaN→0 (typical for missing features on new stations)
    X = X.apply(pd.to_numeric, errors="coerce").fillna(0.0)

    stats = {
        "n_rows": int(len(full)),
        "n_consec_pairs": len(perf_dates_used),
        "n_features": int(len(X.columns)),
        "features_snapshot_date": feat_anchor.isoformat(),
        "y_mean": float(y.mean()),
        "y_std": float(y.std()),
        "y_min": float(y.min()),
        "y_max": float(y.max()),
    }

    return TrainingSet(
        X=X.reset_index(drop=True),
        y=y.reset_index(drop=True),
        meta=meta.reset_index(drop=True),
        feature_cols=X.columns.tolist(),
        features_snapshot_date=feat_anchor.isoformat(),
        perf_dates_used=perf_dates_used,
        stats=stats,
    )


def build_inference_matrix(
    client: Elasticsearch,
    t: date,
    feature_cols: list[str],
    features_snapshot_date: Optional[str] = None,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Build X for predicting utilization at t+1 from perf-{t} snapshot."""
    perf_idx = _perf_index(t)
    if not _index_exists(client, perf_idx):
        raise RuntimeError(f"performance index {perf_idx} not found")

    # Pick features snapshot — use the one from training if specified,
    # otherwise default to the most recent available.
    if features_snapshot_date:
        feat_idx = _feat_index(date.fromisoformat(features_snapshot_date))
        if not _index_exists(client, feat_idx):
            # Fall back to latest
            feat_dates = _list_feat_dates(client)
            if not feat_dates:
                raise RuntimeError("no zes_station_features-* index found")
            feat_idx = _feat_index(max(feat_dates))
    else:
        feat_dates = _list_feat_dates(client)
        if not feat_dates:
            raise RuntimeError("no zes_station_features-* index found")
        feat_idx = _feat_index(max(feat_dates))

    features_df = _scan_to_df(
        client, feat_idx,
        fields=["station_id"] + FEATURES_NUMERIC,
    )
    perf_t = _scan_to_df(
        client, perf_idx,
        fields=["station_id", "station_name", "city_name"] + PERF_NUMERIC,
    )
    if perf_t.empty:
        raise RuntimeError(f"{perf_idx} is empty")

    perf_t = _join_lag(perf_t, client, t - timedelta(days=1), "1d")
    perf_t = _join_lag(perf_t, client, t - timedelta(days=7), "7d")

    target_day = t + timedelta(days=1)
    cal = _calendar_features(target_day)
    for k, v in cal.items():
        perf_t[k] = v

    merged = perf_t.merge(features_df, on="station_id", how="left")

    meta = merged[["station_id", "station_name", "city_name"]].copy()
    meta["t"] = t.isoformat()
    meta["t_target"] = target_day.isoformat()

    X = merged.drop(columns=["station_id", "station_name", "city_name"])
    X = X.apply(pd.to_numeric, errors="coerce").fillna(0.0)

    # Align column order with training
    for c in feature_cols:
        if c not in X.columns:
            X[c] = 0.0
    X = X[feature_cols]

    return X.reset_index(drop=True), meta.reset_index(drop=True)
