"""Compare multiple model families on the same train/test split.

Purpose: hypothesis test. "Does our data have signal that ANY model can capture
above the naive baseline?" If even Ridge / MLP fail to beat naive, the issue is
the data (small N, weak signal), not the algorithm choice.

Runs the following on identical temporal split:
  - mean_baseline      → constant prediction (sanity floor)
  - naive_baseline     → y_pred = util_at_source (autoregressive baseline)
  - ridge              → linear regression (Ridge α=1.0, scaled features)
  - mlp_small          → 1 hidden layer × 32 neurons
  - mlp_medium         → 2 hidden layers × (64, 32) neurons
  - hist_gb_current    → HistGradientBoosting (what train.py uses)

Reports MAE / RMSE / R² + improvement_pct over naive for each.
Does NOT persist any model — pure diagnostics.
"""
from __future__ import annotations

import time
import warnings
from typing import Optional

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler

from extract import make_client
from forecast.data_builder import build_training_set


def _temporal_split(meta: pd.DataFrame, holdout_ratio: float = 0.20) -> np.ndarray:
    t_targets = sorted(meta["t_target"].unique())
    n_test = max(1, int(round(len(t_targets) * holdout_ratio)))
    test_dates = set(t_targets[-n_test:])
    return meta["t_target"].isin(test_dates).values


def _score(y_true, y_pred, naive_mae: float, label: str, fit_secs: float) -> dict:
    mae  = float(mean_absolute_error(y_true, y_pred))
    rmse = float(np.sqrt(mean_squared_error(y_true, y_pred)))
    r2   = float(r2_score(y_true, y_pred))
    improvement_pct = (naive_mae - mae) / naive_mae * 100 if naive_mae > 0 else 0.0
    return {
        "model": label,
        "mae":   round(mae, 4),
        "rmse":  round(rmse, 4),
        "r2":    round(r2, 4),
        "improvement_pct": round(improvement_pct, 2),
        "fit_seconds": round(fit_secs, 2),
    }


def run_comparison() -> dict:
    client = make_client()
    ts = build_training_set(client)
    if ts is None:
        return {"error": "no consecutive (t, t+1) perf pairs available"}

    if len(ts.y) < 200:
        return {
            "error": "training set too small",
            "n_rows": int(len(ts.y)),
        }

    test_mask = _temporal_split(ts.meta, holdout_ratio=0.20)
    train_mask = ~test_mask
    X_train, y_train = ts.X[train_mask], ts.y[train_mask]
    X_test,  y_test  = ts.X[test_mask],  ts.y[test_mask]

    # Scaler for linear / MLP (gradient boosting doesn't need it)
    scaler = StandardScaler()
    X_train_s = scaler.fit_transform(X_train.values)
    X_test_s  = scaler.transform(X_test.values)

    # Baselines (no fitting needed)
    naive_pred = X_test["charging_utilization_pct"].values
    naive_mae = float(mean_absolute_error(y_test, naive_pred))
    mean_pred = np.full_like(y_test.values, y_train.mean(), dtype=float)

    results = []

    # 1. Mean baseline (sanity floor — should be the worst)
    results.append(_score(y_test, mean_pred, naive_mae, "mean_baseline", 0.0))

    # 2. Naive baseline
    results.append(_score(y_test, naive_pred, naive_mae, "naive_baseline", 0.0))

    # 3. Ridge regression
    t0 = time.time()
    ridge = Ridge(alpha=1.0, random_state=42)
    ridge.fit(X_train_s, y_train)
    pred = ridge.predict(X_test_s)
    results.append(_score(y_test, pred, naive_mae, "ridge", time.time() - t0))

    # 4. Small MLP — 1 hidden layer
    t0 = time.time()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        mlp_small = MLPRegressor(
            hidden_layer_sizes=(32,),
            activation="relu",
            solver="adam",
            max_iter=300,
            early_stopping=True,
            validation_fraction=0.15,
            n_iter_no_change=20,
            random_state=42,
        )
        mlp_small.fit(X_train_s, y_train)
    pred = mlp_small.predict(X_test_s)
    results.append(_score(y_test, pred, naive_mae, "mlp_small_32", time.time() - t0))

    # 5. Medium MLP — 2 hidden layers
    t0 = time.time()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        mlp_medium = MLPRegressor(
            hidden_layer_sizes=(64, 32),
            activation="relu",
            solver="adam",
            max_iter=300,
            early_stopping=True,
            validation_fraction=0.15,
            n_iter_no_change=20,
            random_state=42,
        )
        mlp_medium.fit(X_train_s, y_train)
    pred = mlp_medium.predict(X_test_s)
    results.append(_score(y_test, pred, naive_mae, "mlp_medium_64_32", time.time() - t0))

    # 6. HistGradientBoosting (current production model)
    t0 = time.time()
    hgb = HistGradientBoostingRegressor(
        max_iter=300,
        max_depth=5,
        learning_rate=0.05,
        min_samples_leaf=50,
        l2_regularization=1.0,
        early_stopping=True,
        validation_fraction=0.15,
        n_iter_no_change=20,
        random_state=42,
    )
    hgb.fit(X_train, y_train)
    pred = hgb.predict(X_test)
    results.append(_score(y_test, pred, naive_mae, "hist_gb_current", time.time() - t0))

    # Pick best by MAE
    best = min(results, key=lambda r: r["mae"])

    return {
        "status": "ok",
        "n_rows_train": int(train_mask.sum()),
        "n_rows_test":  int(test_mask.sum()),
        "n_consec_pairs": ts.stats["n_consec_pairs"],
        "features_snapshot_date": ts.features_snapshot_date,
        "y_test_stats": {
            "mean": round(float(y_test.mean()), 2),
            "std":  round(float(y_test.std()), 2),
            "min":  round(float(y_test.min()), 2),
            "max":  round(float(y_test.max()), 2),
        },
        "naive_mae": round(naive_mae, 4),
        "results": results,
        "best_by_mae": best,
        "verdict": (
            f"Best model: {best['model']} (MAE={best['mae']}, improvement vs naive={best['improvement_pct']}%). "
            + (
                "Naive baseline still wins — data lacks signal at current scale."
                if best['model'] == "naive_baseline" or best['improvement_pct'] <= 0
                else f"{best['model']} beats naive by {best['improvement_pct']}% — worth using."
            )
        ),
    }


if __name__ == "__main__":
    import json
    result = run_comparison()
    print(json.dumps(result, indent=2, default=str))
