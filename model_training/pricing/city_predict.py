"""Aggregate the hourly per-station forecast up to city level.

Primary pricing decision unit = (city, segment, hour). For each such cell we
need a baseline utilization, total connectors and representative power. City
utilization is the connector-weighted mean of its stations' predicted hourly
utilization (consistent with ZesCityAnalysisService's station aggregation).

Output index: zes_city_forecast-{date} — one doc per (city, segment).
"""
from __future__ import annotations

import json
from datetime import date, datetime
from typing import Optional

import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk

from extract import make_client
from . import pricing_config as pc
from .hourly_predict import HOURLY_FORECAST_PREFIX
from .hourly_data_builder import fetch_hourly_performance  # generic scan helper shape

CITY_FORECAST_PREFIX = "zes_city_forecast"

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "city_name":         {"type": "keyword"},
            "segment":           {"type": "keyword"},
            "forecast_for_date": {"type": "date"},
            "n_stations":        {"type": "integer"},
            "connector_count":   {"type": "integer"},
            "avg_power_kw":      {"type": "float"},
            "hourly_utilization_pct": {"type": "float"},  # array[24], connector-weighted
            "daily_utilization_pct":  {"type": "float"},
            "pricing_version":   {"type": "keyword"},
            "computed_at":       {"type": "date"},
        }
    }
}


def _ensure_index(client: Elasticsearch, name: str) -> None:
    if not client.indices.exists(index=name):
        client.indices.create(index=name, body=INDEX_MAPPING)


def _fetch_hourly_forecast(client: Elasticsearch, d: date) -> pd.DataFrame:
    from elasticsearch.helpers import scan
    idx = f"{HOURLY_FORECAST_PREFIX}-{d.isoformat()}"
    if not client.indices.exists(index=idx):
        return pd.DataFrame()
    rows = [hit["_source"] for hit in scan(
        client, index=idx, query={"query": {"match_all": {}}}, size=1000)]
    return pd.DataFrame(rows)


def predict_city(target_date: date, client: Optional[Elasticsearch] = None) -> dict:
    client = client or make_client()
    hf = _fetch_hourly_forecast(client, target_date)
    if hf.empty:
        return {"error": f"{HOURLY_FORECAST_PREFIX}-{target_date.isoformat()} not found — "
                         f"run predict_hourly first"}

    hf["connector_count"] = pd.to_numeric(hf["connector_count"], errors="coerce").fillna(0.0)
    hf["avg_power_kw"] = pd.to_numeric(hf["avg_power_kw"], errors="coerce")
    hourly_mat = np.vstack(hf["hourly_predicted_pct"].apply(lambda x: np.asarray(x, dtype=float)))

    index_name = f"{CITY_FORECAST_PREFIX}-{target_date.isoformat()}"
    _ensure_index(client, index_name)
    computed_at = datetime.utcnow().isoformat() + "Z"

    docs = []
    for (city, seg), grp in hf.groupby(["city_name", "segment"]):
        idx = grp.index.to_numpy()
        w = grp["connector_count"].to_numpy()
        if w.sum() <= 0:
            continue
        sub = hourly_mat[[hf.index.get_loc(i) for i in idx]]
        # connector-weighted hourly utilization
        hourly = np.average(sub, axis=0, weights=w)
        # connector-weighted average power
        pw = grp["avg_power_kw"].fillna(pc.FALLBACK_POWER_KW.get(seg, 22.0)).to_numpy()
        avg_power = float(np.average(pw, weights=w))
        body = {
            "city_name": city,
            "segment": seg,
            "forecast_for_date": target_date.isoformat(),
            "n_stations": int(len(grp)),
            "connector_count": int(w.sum()),
            "avg_power_kw": round(avg_power, 2),
            "hourly_utilization_pct": [round(float(x), 2) for x in hourly],
            "daily_utilization_pct": round(float(hourly.mean()), 2),
            "pricing_version": pc.PRICING_VERSION,
            "computed_at": computed_at,
        }
        docs.append({"_index": index_name, "_id": f"{city}_{seg}", "_source": body})

    n_ok, errs = bulk(client, docs, chunk_size=500, refresh=True)
    return {
        "status": "ok",
        "index": index_name,
        "forecast_for_date": target_date.isoformat(),
        "n_city_segments": int(n_ok),
        "errors": len(errs) if isinstance(errs, list) else 0,
    }


if __name__ == "__main__":
    import sys
    d = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else date.today()
    print(json.dumps(predict_city(d), indent=2, default=str))
