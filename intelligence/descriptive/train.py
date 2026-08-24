"""Low-k k-means on robust features, label clusters heuristically, persist model.

Deliberately uses only k in {3, 4} — with limited data, more clusters create
spurious distinctions.
"""
from __future__ import annotations

import json
from datetime import datetime
from typing import Optional

import joblib
import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler

import config
from ingestion.extract import fetch_station_aggregates, make_client
from features.snapshot import compute_features

# Continuous features used for clustering. day_night_ratio / weekend_weekday_ratio
# carry nulls for many stations (sparse DOW coverage), so we exclude them from
# the clustering input and let them remain as descriptive fields only.
CLUSTERING_FEATURES = ["charging_ratio", "peak_hour", "peakiness"]


def _label_cluster(centroid: np.ndarray, feature_cols: list[str]) -> str:
    f = dict(zip(feature_cols, centroid))
    busy = f["charging_ratio"]
    peakiness = f["peakiness"]
    peak_hour_rounded = int(round(f["peak_hour"])) % 24

    if busy < 0.02:
        return "DORMANT"

    if busy >= 0.30:
        usage = "HIGH"
    elif busy >= 0.10:
        usage = "MODERATE"
    else:
        usage = "LOW"

    if peakiness >= 4.0:
        shape = "SPIKY"
    elif peakiness >= 2.0:
        shape = "PEAKY"
    else:
        shape = "STEADY"

    if 6 <= peak_hour_rounded <= 10:
        when = "MORNING"
    elif 11 <= peak_hour_rounded <= 14:
        when = "MIDDAY"
    elif 15 <= peak_hour_rounded <= 18:
        when = "AFTERNOON"
    elif 19 <= peak_hour_rounded <= 22:
        when = "EVENING"
    else:
        when = "NIGHT"

    return f"{usage}_{shape}_{when}"


def select_k(X: np.ndarray, k_range: tuple[int, int]) -> tuple[int, dict]:
    scores = {}
    for k in range(k_range[0], k_range[1] + 1):
        km = KMeans(n_clusters=k, n_init=10, random_state=42)
        labels = km.fit_predict(X)
        if len(np.unique(labels)) < 2:
            scores[k] = float("nan")
            continue
        scores[k] = float(silhouette_score(X, labels))
    best_k = max(scores, key=lambda k: -1 if np.isnan(scores[k]) else scores[k])
    return best_k, scores


def train_and_save(lookback_days: int = config.TRAINING_LOOKBACK_DAYS) -> dict:
    client = make_client()
    long_df, meta_df = fetch_station_aggregates(client, lookback_days=lookback_days)
    feat_df = compute_features(long_df, meta_df)

    # Drop low-confidence + any nulls in the clustering features
    train_df = feat_df[~feat_df["low_confidence"]].dropna(subset=CLUSTERING_FEATURES).copy()
    if train_df.shape[0] < 20:
        return {"error": "not enough usable stations to train", "n_usable": int(train_df.shape[0])}

    scaler = StandardScaler()
    X = scaler.fit_transform(train_df[CLUSTERING_FEATURES].values)

    best_k, scores = select_k(X, config.K_RANGE)
    model = KMeans(n_clusters=best_k, n_init=10, random_state=42)
    cluster_ids = model.fit_predict(X)

    # Label centroids in original (unstandardized) units for interpretability
    centroids_orig = scaler.inverse_transform(model.cluster_centers_)
    labels = {int(i): _label_cluster(centroids_orig[i], CLUSTERING_FEATURES) for i in range(best_k)}

    # Ensure unique labels (suffix with cluster id if collision)
    seen = {}
    for cid, name in list(labels.items()):
        if name in seen:
            labels[cid] = f"{name}_{cid}"
        seen[labels[cid]] = True

    version = datetime.utcnow().strftime("%Y%m%d-%H%M%S")
    model_file = f"kmeans_v{version}.pkl"
    out_path = config.MODELS_DIR / model_file
    joblib.dump({
        "model": model,
        "scaler": scaler,
        "feature_cols": CLUSTERING_FEATURES,
        "labels": labels,
        "version": version,
    }, out_path)

    (config.MODELS_DIR / "latest.json").write_text(json.dumps({
        "model_file": model_file,
        "version": version,
        "k": int(best_k),
        "silhouette_scores": {str(k): v for k, v in scores.items()},
        "n_training_stations": int(train_df.shape[0]),
        "feature_cols": CLUSTERING_FEATURES,
        "labels": labels,
        "trained_at": datetime.utcnow().isoformat() + "Z",
    }, indent=2))

    # Cluster distribution preview
    dist = pd.Series(cluster_ids).value_counts().sort_index().to_dict()
    archetype_dist = {labels[int(c)]: int(n) for c, n in dist.items()}

    return {
        "model_file": model_file,
        "version": version,
        "k": int(best_k),
        "silhouette_scores": scores,
        "n_training_stations": int(train_df.shape[0]),
        "archetype_distribution": archetype_dist,
        "centroids": {labels[i]: dict(zip(CLUSTERING_FEATURES, centroids_orig[i].tolist())) for i in range(best_k)},
    }


if __name__ == "__main__":
    import sys
    lookback = int(sys.argv[1]) if len(sys.argv) > 1 else config.TRAINING_LOOKBACK_DAYS
    result = train_and_save(lookback)
    print(json.dumps(result, indent=2, default=str))
