"""Build the observed hourly performance index from connector_transform.

Output index: zes_station_hourly_performance-{date}
One document per (station_id, segment) with a 24-element hourly utilization
array, mirroring the slot-splitting convention used by extract.py and
ZesConnectorTransformAnalysisService.java (open slots extend to midnight of the
fetched date).

Hourly utilization for (station, segment, hour):
    charging_minutes / (connector_count_segment · 60) · 100   (capped at 100)

This index is the BASELINE for both the hourly forecast (profile target) and the
pricing backtest. Like opportunity.py / forecast/predict.py, it is written from
Python; the Java side only reads it.
"""
from __future__ import annotations

import json
from collections import defaultdict
from datetime import date, datetime, timedelta
from typing import Optional

import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk, scan

import config
from extract import make_client, _parse_ts, _split_into_hour_buckets
from . import pricing_config as pc

HOURLY_PERF_PREFIX = "zes_station_hourly_performance"
SOURCE_PREFIX = "zes_connector_transform"
# Per-station geographic signal (local substitute density) for elasticity.
STATION_PERF_PREFIX = "zes_station_performance"

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "station_id":        {"type": "keyword"},
            "station_name":      {"type": "keyword"},
            "city_name":         {"type": "keyword"},
            "province_name":     {"type": "keyword"},
            "segment":           {"type": "keyword"},
            "lat":               {"type": "float"},
            "lng":               {"type": "float"},
            "location":          {"type": "geo_point"},
            "analysis_date":     {"type": "date"},
            "connector_count":   {"type": "integer"},
            "nearby_stations_count": {"type": "integer"},  # local substitute density
            "avg_power_kw":      {"type": "float"},
            "hourly_utilization_pct":  {"type": "float"},  # array[24]
            "hourly_charging_minutes": {"type": "float"},  # array[24]
            "daily_utilization_pct":   {"type": "float"},
            "observed_hours":    {"type": "integer"},
            "computed_at":       {"type": "date"},
            "pricing_version":   {"type": "keyword"},
        }
    }
}


def _ensure_index(client: Elasticsearch, name: str) -> None:
    if not client.indices.exists(index=name):
        client.indices.create(index=name, body=INDEX_MAPPING)


def _source_index(d: date) -> str:
    return f"{SOURCE_PREFIX}-{d.isoformat()}"


def _load_nearby_counts(client: Elasticsearch, target_date: date) -> dict[str, int]:
    """Map station_id -> nearby_stations_count from the best station-performance
    snapshot on/before target_date. Returns {} if no snapshot exists; stations
    missing from the result fall back to rural in the elasticity model.
    """
    resp = client.cat.indices(index=f"{STATION_PERF_PREFIX}-*", h="index", format="json")
    dates = []
    for row in resp:
        name = row["index"]
        if not name.startswith(STATION_PERF_PREFIX + "-"):
            continue
        try:
            dates.append(date.fromisoformat(name.split("-", 1)[1]))
        except (ValueError, IndexError):
            continue
    if not dates:
        return {}
    candidates = [d for d in dates if d <= target_date] or dates
    src = f"{STATION_PERF_PREFIX}-{max(candidates).isoformat()}"
    out: dict[str, int] = {}
    for hit in scan(client, index=src, query={"query": {"match_all": {}}},
                    _source=["station_id", "nearby_stations_count"], size=1000):
        s = hit["_source"]
        sid = s.get("station_id")
        nb = s.get("nearby_stations_count")
        if sid is not None and nb is not None:
            out[sid] = int(nb)
    return out


def list_hourly_perf_dates(client: Elasticsearch) -> list[date]:
    resp = client.cat.indices(index=f"{HOURLY_PERF_PREFIX}-*", h="index", format="json")
    out = []
    for row in resp:
        try:
            out.append(date.fromisoformat(row["index"].split("-", 1)[1]))
        except (ValueError, IndexError):
            continue
    return sorted(out)


def build_hourly_performance(target_date: date, client: Optional[Elasticsearch] = None) -> dict:
    """Aggregate connector_transform-{date} into per (station, segment) hourly utilization."""
    client = client or make_client()
    src_idx = _source_index(target_date)
    if not client.indices.exists(index=src_idx):
        return {"error": f"source index {src_idx} not found"}

    day_start = datetime.combine(target_date, datetime.min.time())
    day_end = datetime.combine(target_date + timedelta(days=1), datetime.min.time())

    # (station_id, segment) -> dict with meta, connectors set, power sum, hour arrays
    agg: dict[tuple[str, str], dict] = {}

    for hit in scan(
        client, index=src_idx, query={"query": {"match_all": {}}},
        _source=[
            "station_id", "station_name", "city_name", "province_name",
            "lat", "lng", "connector_id", "connector_type", "max_power_kw",
            "availability",
        ],
        size=1000, preserve_order=False,
    ):
        src = hit["_source"]
        sid = src.get("station_id")
        seg = pc.segment_of(src.get("connector_type"))
        if not sid or seg is None:
            continue
        key = (sid, seg)
        rec = agg.get(key)
        if rec is None:
            rec = {
                "station_name": src.get("station_name"),
                "city_name": src.get("city_name"),
                "province_name": src.get("province_name"),
                "lat": src.get("lat"), "lng": src.get("lng"),
                "connectors": set(),
                "power_sum": 0.0, "power_n": 0,
                "charging_min": [0.0] * 24,
                "observed_min": [0.0] * 24,
            }
            agg[key] = rec

        cid = src.get("connector_id")
        if cid is not None and cid not in rec["connectors"]:
            rec["connectors"].add(cid)
            pw = src.get("max_power_kw")
            if pw:
                rec["power_sum"] += float(pw)
                rec["power_n"] += 1

        for slot in src.get("availability", []) or []:
            start_str = slot.get("start_time")
            if not start_str:
                continue
            try:
                start = _parse_ts(start_str)
            except ValueError:
                continue
            end = _parse_ts(slot["end_time"]) if slot.get("end_time") else day_end
            start = max(start, day_start)
            end = min(end, day_end)
            if end <= start:
                continue
            is_charging = slot.get("status") == config.CHARGING_STATUS
            for hour, minutes in _split_into_hour_buckets(start, end):
                rec["observed_min"][hour] += minutes
                if is_charging:
                    rec["charging_min"][hour] += minutes

    if not agg:
        return {"error": f"no usable connector docs in {src_idx}"}

    nearby_counts = _load_nearby_counts(client, target_date)

    index_name = f"{HOURLY_PERF_PREFIX}-{target_date.isoformat()}"
    _ensure_index(client, index_name)
    computed_at = datetime.utcnow().isoformat() + "Z"

    def _docs():
        for (sid, seg), rec in agg.items():
            n_conn = len(rec["connectors"])
            if n_conn == 0:
                continue
            denom = n_conn * 60.0
            hourly_pct = [min(100.0, round(cm / denom * 100.0, 2)) if denom else 0.0
                          for cm in rec["charging_min"]]
            observed_hours = sum(1 for om in rec["observed_min"] if om > 0)
            daily_pct = round(sum(hourly_pct) / 24.0, 2)
            avg_power = (rec["power_sum"] / rec["power_n"]) if rec["power_n"] else \
                pc.FALLBACK_POWER_KW[seg]
            body = {
                "station_id": sid,
                "station_name": rec["station_name"],
                "city_name": rec["city_name"],
                "province_name": rec["province_name"],
                "segment": seg,
                "lat": rec["lat"], "lng": rec["lng"],
                "analysis_date": target_date.isoformat(),
                "connector_count": n_conn,
                "nearby_stations_count": nearby_counts.get(sid),
                "avg_power_kw": round(float(avg_power), 2),
                "hourly_utilization_pct": hourly_pct,
                "hourly_charging_minutes": [round(cm, 1) for cm in rec["charging_min"]],
                "daily_utilization_pct": daily_pct,
                "observed_hours": observed_hours,
                "computed_at": computed_at,
                "pricing_version": pc.PRICING_VERSION,
            }
            if body["lat"] is not None and body["lng"] is not None:
                body["location"] = {"lat": body["lat"], "lon": body["lng"]}
            yield {"_index": index_name, "_id": f"{sid}_{seg}", "_source": body}

    n_ok, errs = bulk(client, _docs(), chunk_size=500, refresh=True)
    return {
        "status": "ok",
        "index": index_name,
        "analysis_date": target_date.isoformat(),
        "n_station_segments": int(n_ok),
        "errors": len(errs) if isinstance(errs, list) else 0,
    }


def fetch_hourly_performance(client: Elasticsearch, d: date) -> pd.DataFrame:
    """Read zes_station_hourly_performance-{date} into a DataFrame (one row per station-segment)."""
    idx = f"{HOURLY_PERF_PREFIX}-{d.isoformat()}"
    if not client.indices.exists(index=idx):
        return pd.DataFrame()
    rows = [hit["_source"] for hit in scan(
        client, index=idx, query={"query": {"match_all": {}}}, size=1000)]
    return pd.DataFrame(rows)


if __name__ == "__main__":
    import sys
    d = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else None
    if d is None:
        c = make_client()
        dates = sorted([date.fromisoformat(r["index"].split("-", 1)[1])
                        for r in c.cat.indices(index=f"{SOURCE_PREFIX}-*", h="index", format="json")
                        if r["index"].startswith(SOURCE_PREFIX + "-")])
        d = dates[-1] if dates else None
    print(json.dumps(build_hourly_performance(d), indent=2, default=str))
