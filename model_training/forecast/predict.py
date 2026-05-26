"""Inference: produce utilization predictions for a target date.

Input contract: caller specifies `target_date` (the day to forecast).
Source snapshot: the latest available zes_station_performance-{date}
                 strictly BEFORE target_date. Gap may be >1 day if data is
                 sparse — `source_gap_days` is recorded in each output doc.

Output index: zes_station_forecast-{target_date}
Each doc:
  {
    "station_id", "station_name", "city_name",
    "lat", "lng", "location": {lat, lon},
    "forecast_for_date":   "YYYY-MM-DD",   ← target_date
    "source_date":         "YYYY-MM-DD",   ← perf snapshot used as input
    "source_gap_days":     int,            ← target - source in days
    "predicted_utilization_pct": float,
    "source_utilization_pct":    float,    ← perf-{source}.charging_utilization_pct
    "delta_vs_source":     float,          ← predicted - source
    "model_version":       "YYYYMMDD-HHMMSS",
    "computed_at":         ISO ts,
  }

Note: the model was trained on (t, t+1) consecutive pairs. Predicting with
a gap > 1 day reduces accuracy because the autoregressive `util_t` signal
becomes stale. The calendar feature (`dow_target`) does adapt to the actual
target day.
"""
from __future__ import annotations

import json
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Optional

import joblib
import numpy as np
from elasticsearch.helpers import bulk

import config
from extract import make_client
from forecast.data_builder import build_inference_matrix

FORECAST_PREFIX = "zes_station_forecast"

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "station_id":        {"type": "keyword"},
            "station_name":      {"type": "keyword"},
            "city_name":         {"type": "keyword"},
            "lat":               {"type": "float"},
            "lng":               {"type": "float"},
            "location":          {"type": "geo_point"},
            "forecast_for_date": {"type": "date"},
            "source_date":       {"type": "date"},
            "source_gap_days":   {"type": "integer"},
            "predicted_utilization_pct": {"type": "float"},
            "source_utilization_pct":    {"type": "float"},
            "delta_vs_source":           {"type": "float"},
            "model_version":     {"type": "keyword"},
            "computed_at":       {"type": "date"},
        }
    }
}


def _load_artefact() -> dict:
    manifest_path = config.MODELS_DIR / "latest_forecast.json"
    if not manifest_path.exists():
        raise RuntimeError("models/latest_forecast.json not found — run /forecast/train first")
    manifest = json.loads(manifest_path.read_text())
    model_file = config.MODELS_DIR / manifest["model_file"]
    if not model_file.exists():
        raise RuntimeError(f"model file {model_file} not found")
    return joblib.load(model_file)


def _ensure_index(client, index_name: str) -> None:
    if not client.indices.exists(index=index_name):
        client.indices.create(index=index_name, body=INDEX_MAPPING)


def _resolve_source_date(client, target_date: date) -> Optional[date]:
    """Pick the latest perf index strictly before target_date."""
    from forecast.data_builder import _list_perf_dates
    perf_dates = _list_perf_dates(client)
    candidates = [d for d in perf_dates if d < target_date]
    return candidates[-1] if candidates else None


def predict_for_date(target_date: Optional[date] = None) -> dict:
    """Predict utilization for `target_date`.

    Source snapshot is the latest perf index BEFORE target_date (gap may be >1 day).
    If target_date is None, default to (latest_perf_date + 1 day).
    """
    client = make_client()
    artefact = _load_artefact()
    model = artefact["model"]
    scaler = artefact.get("scaler")  # tree models bundle scaler=None; legacy MLP bundles set it
    feature_cols = artefact["feature_cols"]
    features_snapshot_date = artefact.get("features_snapshot_date")
    target_transform = artefact.get("target_transform", "identity")
    clip_max = float(artefact.get("clip_max", 100.0))

    from forecast.data_builder import _list_perf_dates, _scan_to_df, _perf_index
    perf_dates = _list_perf_dates(client)
    if not perf_dates:
        return {"error": "no zes_station_performance-* index available"}

    if target_date is None:
        target_date = perf_dates[-1] + timedelta(days=1)

    source_date = _resolve_source_date(client, target_date)
    if source_date is None:
        return {
            "error": f"no perf index before {target_date}",
            "target_date": target_date.isoformat(),
            "available_perf_dates": [d.isoformat() for d in perf_dates],
        }

    source_gap_days = (target_date - source_date).days

    X, meta = build_inference_matrix(
        client, source_date, feature_cols, features_snapshot_date
    )

    # Override the calendar features to encode the actual target_date,
    # since build_inference_matrix assumed t+1 by default.
    from forecast.data_builder import _calendar_features
    cal = _calendar_features(target_date)
    for k, v in cal.items():
        if k in X.columns:
            X[k] = v

    X_for_model = scaler.transform(X.values) if scaler is not None else X.values
    y_pred = model.predict(X_for_model)
    if target_transform == "log1p":
        y_pred = np.expm1(y_pred)
    y_pred = np.clip(y_pred, 0.0, clip_max)

    source_util = X["charging_utilization_pct"].values

    index_name = f"{FORECAST_PREFIX}-{target_date.isoformat()}"
    _ensure_index(client, index_name)
    computed_at = datetime.utcnow().isoformat() + "Z"

    perf_meta = _scan_to_df(
        client, _perf_index(source_date),
        fields=["station_id", "station_name", "lat", "lng"]
    ).set_index("station_id")

    def _docs():
        for i, row in meta.iterrows():
            sid = row["station_id"]
            extra = perf_meta.loc[sid].to_dict() if sid in perf_meta.index else {}
            lat, lng = extra.get("lat"), extra.get("lng")
            body = {
                "station_id":   sid,
                "station_name": row.get("station_name") or extra.get("station_name"),
                "city_name":    row.get("city_name"),
                "lat":          lat,
                "lng":          lng,
                "forecast_for_date":     target_date.isoformat(),
                "source_date":           source_date.isoformat(),
                "source_gap_days":       int(source_gap_days),
                "predicted_utilization_pct": float(round(y_pred[i], 2)),
                "source_utilization_pct":    float(round(source_util[i], 2)),
                "delta_vs_source":           float(round(y_pred[i] - source_util[i], 2)),
                "model_version": artefact["version"],
                "computed_at":   computed_at,
            }
            if lat is not None and lng is not None:
                body["location"] = {"lat": lat, "lon": lng}
            yield {"_index": index_name, "_id": sid, "_source": body}

    n_ok, errs = bulk(client, _docs(), chunk_size=500, refresh=True)
    return {
        "status": "ok",
        "index": index_name,
        "forecast_for_date": target_date.isoformat(),
        "source_date": source_date.isoformat(),
        "source_gap_days": int(source_gap_days),
        "model_version": artefact["version"],
        "n_predicted": int(n_ok),
        "errors": len(errs) if isinstance(errs, list) else 0,
        "predicted_pct": {
            "mean": float(round(y_pred.mean(), 2)),
            "min":  float(round(y_pred.min(), 2)),
            "max":  float(round(y_pred.max(), 2)),
        },
    }


if __name__ == "__main__":
    import sys
    d = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else None
    result = predict_for_date(target_date=d)
    print(json.dumps(result, indent=2, default=str))
