"""Per-station growth-potential / opportunity scoring.

Combines demand signals (our features index) with supply signals
(existing zes_station_performance index) into one 0-100 score.

Signals & directions (positive = higher opportunity to expand):
  + charging_ratio          — station is busy
  + peakiness               — bursty demand suggests unmet need
  + nearby_avg_utilization  — surrounding area is busy
  - nearby_stations_count   — fewer alternatives nearby
  - city_density            — fewer alternatives in city
  - connector_count         — small footprint, room to grow

Each component is converted to a 0..1 percentile rank across the dataset,
direction-flipped if it's an "inverse" signal, weighted, summed, and
scaled to 0..100. We also publish each component's percentile so the
dashboard can show "why" a station scored high.

Comparison: for each station we include the existing recommendation_code
(EXPAND, MAINTAIN, RELOCATE, etc.) so analysts can see where our score
agrees or disagrees with the rule-based system.
"""
from __future__ import annotations

import json
from datetime import date, datetime
from typing import Optional

import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk, scan

import config
from extract import make_client

OPPORTUNITY_INDEX_PREFIX = "zes_station_opportunity"
FEATURES_INDEX_PREFIX = "zes_station_features"
PERFORMANCE_INDEX_PREFIX = "zes_station_performance"

# Signal name -> (weight, inverse_flag)
SIGNAL_WEIGHTS = {
    "charging_ratio":          (1.00, False),
    "peakiness":               (0.40, False),
    "nearby_avg_utilization":  (0.60, False),
    "nearby_stations_count":   (0.40, True),   # fewer = more opportunity
    "city_density":            (0.30, True),   # fewer in city = more opportunity
    "connector_count":         (0.20, True),   # smaller = easier to expand
}

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "station_id": {"type": "keyword"},
            "station_name": {"type": "keyword"},
            "city_name": {"type": "keyword"},
            "lat": {"type": "float"},
            "lng": {"type": "float"},
            "location": {"type": "geo_point"},

            "opportunity_score": {"type": "float"},
            "growth_potential": {"type": "keyword"},

            # Raw inputs for transparency
            "charging_ratio": {"type": "float"},
            "peakiness": {"type": "float"},
            "nearby_avg_utilization": {"type": "float"},
            "nearby_stations_count": {"type": "integer"},
            "city_density": {"type": "integer"},
            "connector_count": {"type": "integer"},

            # Existing labels for comparison
            "existing_demand_supply_ratio": {"type": "float"},
            "existing_demand_tier": {"type": "keyword"},
            "existing_supply_tier": {"type": "keyword"},
            "existing_recommendation_code": {"type": "keyword"},
            "agreement": {"type": "keyword"},

            "components": {
                "type": "nested",
                "properties": {
                    "signal": {"type": "keyword"},
                    "raw_value": {"type": "float"},
                    "percentile": {"type": "float"},
                    "contribution": {"type": "float"},
                    "weight": {"type": "float"},
                    "inverse": {"type": "boolean"},
                },
            },

            "computed_at": {"type": "date"},
            "feature_version": {"type": "keyword"},
        }
    }
}


def _scan_index(client: Elasticsearch, index_name: str, fields: list[str]) -> pd.DataFrame:
    rows = []
    for hit in scan(
        client,
        index=index_name,
        query={"query": {"match_all": {}}},
        _source=fields,
        size=1000,
    ):
        rows.append(hit["_source"])
    return pd.DataFrame(rows)


def _percentile_rank(s: pd.Series) -> pd.Series:
    """Map values to 0..1 using rank/N. NaNs stay NaN."""
    return s.rank(pct=True, method="average")


def _growth_label(score: float) -> str:
    if score >= 75:
        return "HIGH"
    if score >= 50:
        return "MEDIUM"
    if score >= 25:
        return "LOW"
    return "MINIMAL"


def _ensure_index(client: Elasticsearch, index_name: str) -> None:
    if not client.indices.exists(index=index_name):
        client.indices.create(index=index_name, body=INDEX_MAPPING)


# Rule-based recommendation_code -> implicit opportunity tier (ordinal 0-3).
# Each rule code already encodes a demand×supply quadrant; we map that quadrant
# to the tier the model is expected to produce. Comparison then becomes a
# tier-distance check (see _compare_to_existing).
_REC_TO_EXPECTED_TIER = {
    "EXPAND":             3,  # HIGH    (high demand,   few supply)
    "INCREASE_CAPACITY":  3,  # HIGH    (high demand,   moderate supply)
    "MONITOR_GROWTH":     2,  # MEDIUM  (medium demand, few supply)
    "OPTIMIZE":           2,  # MEDIUM  (high demand,   many supply — saturated)
    "MAINTAIN":           2,  # MEDIUM  (medium/medium — the modal class)
    "IMPROVE_EFFICIENCY": 1,  # LOW     (medium demand, many supply)
    "MONITOR":            1,  # LOW     (low demand,    few supply)
    "REVIEW":             1,  # LOW     (low demand,    moderate supply)
    "RELOCATE":           0,  # MINIMAL (low demand,    many supply)
}
_TIER_ORD = {"HIGH": 3, "MEDIUM": 2, "LOW": 1, "MINIMAL": 0}


def _compare_to_existing(growth: str, rec_code: Optional[str]) -> str:
    """Tier-distance agreement label.

    AGREE    — model tier == expected tier from rule code
    NEAR     — adjacent tier (|Δ| == 1); model and rules roughly agree
    DISAGREE — |Δ| ≥ 2; genuine divergence worth investigating
    UNKNOWN  — INSUFFICIENT_DATA or unmapped code
    """
    if rec_code is None or rec_code == "INSUFFICIENT_DATA":
        return "UNKNOWN"
    expected = _REC_TO_EXPECTED_TIER.get(rec_code)
    if expected is None:
        return "UNKNOWN"
    actual = _TIER_ORD.get(growth)
    if actual is None:
        return "UNKNOWN"
    diff = abs(actual - expected)
    if diff == 0:
        return "AGREE"
    if diff == 1:
        return "NEAR"
    return "DISAGREE"


def score_opportunities(output_date: Optional[date] = None) -> dict:
    target = output_date or date.today()
    feat_idx = f"{FEATURES_INDEX_PREFIX}-{target.isoformat()}"
    perf_idx = f"{PERFORMANCE_INDEX_PREFIX}-{target.isoformat()}"

    client = make_client()
    if not client.indices.exists(index=feat_idx):
        return {"error": f"features index {feat_idx} not found — run /assign first"}
    if not client.indices.exists(index=perf_idx):
        return {"error": f"performance index {perf_idx} not found — Spring scheduler hasn't built it"}

    feat = _scan_index(client, feat_idx, [
        "station_id", "station_name", "city_name", "lat", "lng",
        "charging_ratio", "peakiness", "low_confidence",
    ])
    perf = _scan_index(client, perf_idx, [
        "station_id", "connector_count", "nearby_stations_count",
        "nearby_avg_utilization", "demand_supply_ratio",
        "demand_tier", "supply_tier", "recommendation_code",
    ])

    if feat.empty or perf.empty:
        return {"error": "empty index", "feat_rows": len(feat), "perf_rows": len(perf)}

    df = feat.merge(perf, on="station_id", how="inner", suffixes=("", "_perf"))

    city_density = df["city_name"].map(df["city_name"].value_counts())
    df["city_density"] = city_density.astype(float)

    # Drop low-confidence + any missing signal
    signal_cols = list(SIGNAL_WEIGHTS.keys())
    df_use = df.dropna(subset=signal_cols).copy()
    df_use = df_use[df_use["low_confidence"] != True]  # noqa: E712

    # Compute percentile rank for each signal (across the scoreable set)
    pct = pd.DataFrame(index=df_use.index)
    weight_sum = 0.0
    weighted_pct = pd.Series(0.0, index=df_use.index)
    for sig, (w, inverse) in SIGNAL_WEIGHTS.items():
        p = _percentile_rank(df_use[sig])
        if inverse:
            p = 1.0 - p
        pct[sig] = p
        weighted_pct += w * p
        weight_sum += w

    df_use["opportunity_score"] = (weighted_pct / weight_sum * 100.0).round(2)
    df_use["growth_potential"] = df_use["opportunity_score"].apply(_growth_label)
    df_use["agreement"] = df_use.apply(
        lambda r: _compare_to_existing(r["growth_potential"], r.get("recommendation_code")), axis=1
    )

    # Build component breakdowns
    computed_at = datetime.utcnow().isoformat() + "Z"
    index_name = f"{OPPORTUNITY_INDEX_PREFIX}-{target.isoformat()}"
    _ensure_index(client, index_name)

    def _docs():
        for _, row in df_use.iterrows():
            components = []
            for sig, (w, inverse) in SIGNAL_WEIGHTS.items():
                components.append({
                    "signal": sig,
                    "raw_value": float(row[sig]),
                    "percentile": float(pct.loc[row.name, sig]),
                    "contribution": float(w * pct.loc[row.name, sig] / weight_sum * 100.0),
                    "weight": w,
                    "inverse": inverse,
                })
            body = {
                "station_id": row["station_id"],
                "station_name": row.get("station_name"),
                "city_name": row.get("city_name"),
                "lat": row.get("lat"),
                "lng": row.get("lng"),
                "opportunity_score": float(row["opportunity_score"]),
                "growth_potential": row["growth_potential"],
                "charging_ratio": float(row["charging_ratio"]),
                "peakiness": float(row["peakiness"]),
                "nearby_avg_utilization": float(row["nearby_avg_utilization"]),
                "nearby_stations_count": int(row["nearby_stations_count"]),
                "city_density": int(row["city_density"]),
                "connector_count": int(row["connector_count"]),
                "existing_demand_supply_ratio": float(row.get("demand_supply_ratio") or 0.0),
                "existing_demand_tier": row.get("demand_tier"),
                "existing_supply_tier": row.get("supply_tier"),
                "existing_recommendation_code": row.get("recommendation_code"),
                "agreement": row["agreement"],
                "components": components,
                "computed_at": computed_at,
                "feature_version": config.FEATURE_VERSION,
            }
            if body["lat"] is not None and body["lng"] is not None:
                body["location"] = {"lat": body["lat"], "lon": body["lng"]}
            yield {"_index": index_name, "_id": body["station_id"], "_source": body}

    n_ok, errs = bulk(client, _docs(), chunk_size=500, refresh=True)

    growth_counts = df_use["growth_potential"].value_counts().to_dict()
    agree_counts = df_use["agreement"].value_counts().to_dict()
    # Cross-tab opportunity tier vs recommendation_code
    crosstab = pd.crosstab(df_use["growth_potential"], df_use["recommendation_code"]).to_dict()

    return {
        "count_written": n_ok,
        "errors": len(errs) if isinstance(errs, list) else 0,
        "index": index_name,
        "n_scored": int(len(df_use)),
        "n_dropped_low_confidence_or_missing": int(len(df) - len(df_use)),
        "growth_potential_counts": growth_counts,
        "agreement_counts": agree_counts,
        "crosstab_growth_vs_recommendation": crosstab,
    }


if __name__ == "__main__":
    import sys
    out_date = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else None
    res = score_opportunities(output_date=out_date)
    print(json.dumps(res, indent=2, default=str))
