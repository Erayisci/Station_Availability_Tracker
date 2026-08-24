"""Train 1-day utilization forecast model and persist artefact.

Model: sklearn.ensemble.HistGradientBoostingRegressor with raw target +
monotonic constraints on autoregressive features.

Earlier experiments:
  - MLPRegressor: only 0.7% over naive, extrapolated wildly to 80%+ on
    low-input stations.
  - HGB + log1p: 18.6% over naive, but log1p induced Jensen bias —
    predictions collapsed toward ~2-3% even when today's util was 80%+.

Current approach drops log1p (clip at 99.5pct of train y still suppresses
out-of-distribution outputs) and adds monotonic_cst=+1 on
`charging_utilization_pct`, `util_lag_1d`, `util_lag_7d`, and
`nearby_avg_utilization`. This forces predictions to be non-decreasing in
the autoregressive signals, so a station at 80% today cannot collapse to
3% tomorrow without strong negative evidence from non-monotonic features.

No feature scaler needed for tree models — bundle stores scaler=None for
backwards-compat with predict.py.

Persisted artefact (joblib):
  {
    "model":  fitted_HistGradientBoostingRegressor,
    "scaler": None,                       ← kept for predict.py contract
    "feature_cols": [...],                ← order matters
    "target_transform": "identity",       ← predict.py: no back-transform
    "clip_max":  float,                   ← inference output ceiling
    "features_snapshot_date": "YYYY-MM-DD",
    "version": "20260609-153012",
    "metrics": {"mae": ..., "rmse": ..., "r2": ..., "naive_mae": ..., "improvement_pct": ...},
    "n_rows_train": int, "n_rows_test": int,
    "stats": training_set.stats,
    "trained_at": ISO ts,
  }

Manifest: models/latest_forecast.json points to active artefact.
"""
from __future__ import annotations

import json
import warnings
from datetime import datetime
from typing import Optional

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

import config
from ingestion.extract import make_client
from forecast.data_builder import build_training_set


def _temporal_split(meta: pd.DataFrame, holdout_ratio: float = 0.20) -> np.ndarray:
    """Mark the latest `holdout_ratio` of distinct t_target dates as test set.

    Returns: boolean mask, True = test row.
    """
    t_targets = sorted(meta["t_target"].unique())
    n_test = max(1, int(round(len(t_targets) * holdout_ratio)))
    test_dates = set(t_targets[-n_test:])
    return meta["t_target"].isin(test_dates).values


def train_and_save() -> dict:
    client = make_client()
    ts = build_training_set(client)
    if ts is None:
        return {"error": "no consecutive (t, t+1) perf pairs available, training skipped"}

    if len(ts.y) < 200:
        return {
            "error": "training set too small",
            "n_rows": int(len(ts.y)),
            "perf_dates_used": ts.perf_dates_used,
        }

    test_mask = _temporal_split(ts.meta, holdout_ratio=0.20)
    train_mask = ~test_mask

    X_train, y_train = ts.X[train_mask], ts.y[train_mask]
    X_test,  y_test  = ts.X[test_mask],  ts.y[test_mask]

    # 99.5th percentile of train y caps inference output. Tree models don't
    # extrapolate above max train y anyway, but this also trims rare
    # near-100% targets caused by data quirks.
    clip_max = float(np.percentile(y_train.values, 99.5))

    # Monotonic constraints: predictions must be non-decreasing in
    # autoregressive util signals (today, lag_1d, lag_7d, nearby_avg).
    # sklearn expects a list aligned to feature column order.
    monotonic_features = {
        "charging_utilization_pct",
        "util_lag_1d",
        "util_lag_7d",
        "nearby_avg_utilization",
    }
    monotonic_cst = [
        1 if c in monotonic_features else 0
        for c in ts.feature_cols
    ]

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        model = HistGradientBoostingRegressor(
            learning_rate=0.05,
            max_iter=500,
            max_leaf_nodes=31,
            min_samples_leaf=30,
            l2_regularization=1.0,
            monotonic_cst=monotonic_cst,
            early_stopping=True,
            validation_fraction=0.15,
            n_iter_no_change=25,
            random_state=42,
        )
        model.fit(X_train.values, y_train.values)

    y_pred_test = np.clip(model.predict(X_test.values), 0.0, clip_max)

    # Naive baseline: tomorrow = today
    naive_pred = X_test["charging_utilization_pct"].values
    naive_mae = float(mean_absolute_error(y_test, naive_pred))

    mae  = float(mean_absolute_error(y_test, y_pred_test))
    rmse = float(np.sqrt(mean_squared_error(y_test, y_pred_test)))
    r2   = float(r2_score(y_test, y_pred_test))
    improvement_pct = (naive_mae - mae) / naive_mae * 100 if naive_mae > 0 else 0.0

    version = datetime.utcnow().strftime("%Y%m%d-%H%M%S")
    artefact = {
        "model":  model,
        "scaler": None,
        "feature_cols": ts.feature_cols,
        "target_transform": "identity",
        "clip_max": clip_max,
        "monotonic_cst": monotonic_cst,
        "features_snapshot_date": ts.features_snapshot_date,
        "version": version,
        "model_kind": "HistGradientBoostingRegressor+monotonic",
        "metrics": {
            "mae": mae,
            "rmse": rmse,
            "r2": r2,
            "naive_mae": naive_mae,
            "improvement_pct": float(improvement_pct),
        },
        "n_rows_train": int(train_mask.sum()),
        "n_rows_test": int(test_mask.sum()),
        "stats": ts.stats,
        "trained_at": datetime.utcnow().isoformat() + "Z",
    }

    model_file = f"forecast_v{version}.pkl"
    out_path = config.MODELS_DIR / model_file
    joblib.dump(artefact, out_path)

    # Update manifest
    manifest = {
        "model_file": model_file,
        "version": version,
        "model_kind": artefact["model_kind"],
        "target_transform": artefact["target_transform"],
        "clip_max": artefact["clip_max"],
        "features_snapshot_date": ts.features_snapshot_date,
        "feature_cols": ts.feature_cols,
        "metrics": artefact["metrics"],
        "stats": ts.stats,
        "trained_at": artefact["trained_at"],
    }
    (config.MODELS_DIR / "latest_forecast.json").write_text(
        json.dumps(manifest, indent=2)
    )

    return {
        "status": "ok",
        "model_file": model_file,
        "version": version,
        "metrics": artefact["metrics"],
        "n_rows_train": artefact["n_rows_train"],
        "n_rows_test": artefact["n_rows_test"],
        "features_snapshot_date": ts.features_snapshot_date,
        "perf_dates_used": ts.perf_dates_used,
    }


if __name__ == "__main__":
    result = train_and_save()
    print(json.dumps(result, indent=2, default=str))
