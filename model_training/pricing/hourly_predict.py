"""Disaggregate the daily utilization forecast into an hourly, per-segment forecast.

  predicted_hourly[station, segment, h] = daily_pred[station] · profile_seg[h]

The daily forecast (zes_station_forecast-{date}) is a single per-station number.
Each segment (AC/DC) is assigned that same daily LEVEL but its own hour-of-day
SHAPE (profile mean = 1, so the daily average is preserved). Connector counts
and power come from the most recent observed hourly-performance snapshot, which
also defines which (station, segment) cells exist.

Output index: zes_station_hourly_forecast-{date} — one doc per (station, segment),
the baseline that the pricing optimizer consumes.
"""
from __future__ import annotations

import json
from datetime import date, datetime
from typing import Optional

import numpy as np
from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk, scan

from extract import make_client
from . import pricing_config as pc
from . import profile as prof
from .hourly_data_builder import (
    HOURLY_PERF_PREFIX, build_hourly_performance, fetch_hourly_performance,
    list_hourly_perf_dates,
)

HOURLY_FORECAST_PREFIX = "zes_station_hourly_forecast"
DAILY_FORECAST_PREFIX = "zes_station_forecast"

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "station_id":        {"type": "keyword"},
            "station_name":      {"type": "keyword"},
            "city_name":         {"type": "keyword"},
            "segment":           {"type": "keyword"},
            "lat":               {"type": "float"},
            "lng":               {"type": "float"},
            "location":          {"type": "geo_point"},
            "forecast_for_date": {"type": "date"},
            "source_date":       {"type": "date"},
            "connector_count":   {"type": "integer"},
            "avg_power_kw":      {"type": "float"},
            "daily_predicted_pct": {"type": "float"},
            "hourly_predicted_pct": {"type": "float"},  # array[24]
            "profile_source":    {"type": "keyword"},   # "own" | "archetype"
            "model_version":     {"type": "keyword"},
            "pricing_version":   {"type": "keyword"},
            "computed_at":       {"type": "date"},
        }
    }
}


def _ensure_index(client: Elasticsearch, name: str) -> None:
    if not client.indices.exists(index=name):
        client.indices.create(index=name, body=INDEX_MAPPING)


def _load_daily_forecast(client: Elasticsearch, d: date):
    idx = f"{DAILY_FORECAST_PREFIX}-{d.isoformat()}"
    if not client.indices.exists(index=idx):
        return None
    rows = {}
    version = None
    source_date = None
    for hit in scan(client, index=idx, query={"query": {"match_all": {}}},
                    _source=["station_id", "predicted_utilization_pct",
                             "city_name", "lat", "lng", "model_version", "source_date"],
                    size=1000):
        s = hit["_source"]
        rows[s["station_id"]] = s
        version = version or s.get("model_version")
        source_date = source_date or s.get("source_date")
    return {"rows": rows, "model_version": version, "source_date": source_date}


def _resolve_perf_snapshot(client: Elasticsearch, target_date: date,
                           source_date: Optional[str]) -> Optional[date]:
    """Pick the hourly-performance snapshot to read connector metadata from."""
    perf_dates = list_hourly_perf_dates(client)
    # Prefer the daily forecast's own source_date if we have hourly perf for it.
    if source_date:
        sd = date.fromisoformat(source_date)
        if sd in perf_dates:
            return sd
    candidates = [d for d in perf_dates if d < target_date]
    if candidates:
        return candidates[-1]
    return perf_dates[-1] if perf_dates else None


def predict_hourly(target_date: date, client: Optional[Elasticsearch] = None,
                   lookback_days: int = prof.DEFAULT_LOOKBACK_DAYS) -> dict:
    client = client or make_client()

    daily = _load_daily_forecast(client, target_date)
    if daily is None:
        return {"error": f"daily forecast {DAILY_FORECAST_PREFIX}-{target_date.isoformat()} "
                         f"not found — run /forecast/predict first"}

    snap = _resolve_perf_snapshot(client, target_date, daily["source_date"])
    if snap is None:
        # No hourly perf anywhere — build it for the daily forecast's source date.
        if daily["source_date"]:
            build_hourly_performance(date.fromisoformat(daily["source_date"]), client)
            snap = date.fromisoformat(daily["source_date"])
        else:
            return {"error": "no hourly-performance snapshot available for connector metadata"}

    perf = fetch_hourly_performance(client, snap)
    if perf.empty:
        return {"error": f"hourly performance snapshot {snap} is empty"}

    profiles = prof.build_profiles(target_date, lookback_days=lookback_days, client=client)

    index_name = f"{HOURLY_FORECAST_PREFIX}-{target_date.isoformat()}"
    _ensure_index(client, index_name)
    computed_at = datetime.utcnow().isoformat() + "Z"
    daily_rows = daily["rows"]

    def _docs():
        for _, r in perf.iterrows():
            sid = r["station_id"]
            seg = r["segment"]
            d_row = daily_rows.get(sid)
            if d_row is None:
                continue  # no daily forecast for this station
            daily_pred = float(d_row.get("predicted_utilization_pct") or 0.0)
            own = profiles["profiles"].get((sid, seg))
            shape = own if own is not None else profiles["archetypes"].get(seg, np.ones(24))
            hourly = np.clip(daily_pred * np.asarray(shape, dtype=float), 0.0, 100.0)
            body = {
                "station_id": sid,
                "station_name": r.get("station_name") or d_row.get("station_name"),
                "city_name": r.get("city_name") or d_row.get("city_name"),
                "segment": seg,
                "lat": r.get("lat"), "lng": r.get("lng"),
                "forecast_for_date": target_date.isoformat(),
                "source_date": snap.isoformat(),
                "connector_count": int(r.get("connector_count") or 0),
                "avg_power_kw": float(r.get("avg_power_kw") or pc.FALLBACK_POWER_KW[seg]),
                "daily_predicted_pct": round(daily_pred, 2),
                "hourly_predicted_pct": [round(float(x), 2) for x in hourly],
                "profile_source": "own" if own is not None else "archetype",
                "model_version": daily["model_version"],
                "pricing_version": pc.PRICING_VERSION,
                "computed_at": computed_at,
            }
            if body["lat"] is not None and body["lng"] is not None:
                body["location"] = {"lat": body["lat"], "lon": body["lng"]}
            yield {"_index": index_name, "_id": f"{sid}_{seg}", "_source": body}

    n_ok, errs = bulk(client, _docs(), chunk_size=500, refresh=True)
    return {
        "status": "ok",
        "index": index_name,
        "forecast_for_date": target_date.isoformat(),
        "metadata_snapshot": snap.isoformat(),
        "n_station_segments": int(n_ok),
        "errors": len(errs) if isinstance(errs, list) else 0,
        "profile_stats": profiles["stats"],
    }


if __name__ == "__main__":
    import sys
    d = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else None
    if d is None:
        c = make_client()
        fc = sorted([date.fromisoformat(r["index"].split("-", 1)[1])
                     for r in c.cat.indices(index=f"{DAILY_FORECAST_PREFIX}-*", h="index", format="json")
                     if r["index"].startswith(DAILY_FORECAST_PREFIX + "-")])
        d = fc[-1] if fc else None
    print(json.dumps(predict_hourly(d), indent=2, default=str))
