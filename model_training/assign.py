"""Compute robust features + (optionally) cluster archetype and bulk-index to ES."""
from __future__ import annotations

from datetime import datetime, date
from typing import Optional

import joblib
import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk

import config
from extract import fetch_station_aggregates, make_client
from features import compute_features

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "station_id": {"type": "keyword"},
            "station_name": {"type": "keyword"},
            "city_name": {"type": "keyword"},
            "province_name": {"type": "keyword"},
            "lat": {"type": "float"},
            "lng": {"type": "float"},
            "location": {"type": "geo_point"},
            "charging_ratio": {"type": "float"},
            "peak_hour": {"type": "integer"},
            "peakiness": {"type": "float"},
            "day_night_ratio": {"type": "float"},
            "weekend_weekday_ratio": {"type": "float"},
            "observation_quality": {"type": "float"},
            "n_observed_hours": {"type": "float"},
            "n_observed_dows": {"type": "integer"},
            "n_observed_hour_slots": {"type": "integer"},
            "low_confidence": {"type": "boolean"},
            "archetype_v1": {"type": "keyword"},
            "cluster_id": {"type": "integer"},
            "cluster_distance": {"type": "float"},
            "feature_version": {"type": "keyword"},
            "computed_at": {"type": "date"},
        }
    }
}


def ensure_index(client: Elasticsearch, index_name: str) -> None:
    if not client.indices.exists(index=index_name):
        client.indices.create(index=index_name, body=INDEX_MAPPING)


def _attach_archetype(feat_df: pd.DataFrame, model_path: Optional[str]) -> pd.DataFrame:
    if not model_path:
        feat_df["archetype_v1"] = None
        feat_df["cluster_id"] = None
        feat_df["cluster_distance"] = None
        return feat_df
    bundle = joblib.load(model_path)
    model = bundle["model"]
    scaler = bundle["scaler"]
    feature_cols = bundle["feature_cols"]
    labels = bundle["labels"]

    # Stations with any null in feature_cols -> UNKNOWN, no cluster
    usable = feat_df.dropna(subset=feature_cols)
    out = feat_df.copy()
    out["archetype_v1"] = "UNKNOWN"
    out["cluster_id"] = None
    out["cluster_distance"] = None

    if not usable.empty:
        X = scaler.transform(usable[feature_cols].values)
        cluster_ids = model.predict(X)
        # distance to assigned centroid
        centroids = model.cluster_centers_
        distances = np.linalg.norm(X - centroids[cluster_ids], axis=1)
        out.loc[usable.index, "cluster_id"] = cluster_ids.astype(int)
        out.loc[usable.index, "archetype_v1"] = [labels[int(c)] for c in cluster_ids]
        out.loc[usable.index, "cluster_distance"] = distances.astype(float)

    # low_confidence stations -> always UNKNOWN regardless
    mask_low = out["low_confidence"] == True  # noqa: E712
    out.loc[mask_low, "archetype_v1"] = "UNKNOWN"
    out.loc[mask_low, "cluster_id"] = None
    out.loc[mask_low, "cluster_distance"] = None
    return out


def _docs(feat_df: pd.DataFrame, index_name: str, computed_at: str):
    for _, row in feat_df.iterrows():
        body = {k: (None if (isinstance(v, float) and np.isnan(v)) else v)
                for k, v in row.to_dict().items()}
        if body.get("lat") is not None and body.get("lng") is not None:
            body["location"] = {"lat": body["lat"], "lon": body["lng"]}
        body["computed_at"] = computed_at
        body["feature_version"] = config.FEATURE_VERSION
        yield {"_index": index_name, "_id": body["station_id"], "_source": body}


def assign_all(
    output_date: Optional[date] = None,
    lookback_days: int = config.TRAINING_LOOKBACK_DAYS,
    model_path: Optional[str] = None,
) -> dict:
    output_date = output_date or date.today()
    index_name = f"{config.OUTPUT_INDEX_PREFIX}-{output_date.isoformat()}"
    computed_at = datetime.utcnow().isoformat() + "Z"

    client = make_client()
    long_df, meta_df = fetch_station_aggregates(client, lookback_days=lookback_days)
    if long_df.empty:
        return {"count_written": 0, "index": index_name, "reason": "no source data"}

    feat_df = compute_features(long_df, meta_df)
    feat_df = _attach_archetype(feat_df, model_path)

    ensure_index(client, index_name)
    n_ok, errors = bulk(client, _docs(feat_df, index_name, computed_at), chunk_size=500, refresh=True)
    return {
        "count_written": n_ok,
        "errors": len(errors) if isinstance(errors, list) else 0,
        "index": index_name,
        "n_low_confidence": int(feat_df["low_confidence"].sum()),
        "n_with_archetype": int(((feat_df.get("archetype_v1").notna()) & (feat_df.get("archetype_v1") != "UNKNOWN")).sum()) if "archetype_v1" in feat_df.columns else 0,
        "model_used": model_path or None,
    }


if __name__ == "__main__":
    import sys
    model = sys.argv[1] if len(sys.argv) > 1 else None
    result = assign_all(model_path=model)
    print(result)
