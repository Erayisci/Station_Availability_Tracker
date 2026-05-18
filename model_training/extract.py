"""Pull zes_connector_transform-* into a long DataFrame aggregated by (station, dow, hour).

Each availability slot is split across hour buckets it overlaps. Open slots
(no end_time) extend to midnight of fetched_date — matching the convention in
ZesConnectorTransformAnalysisService.java.
"""
from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta
from typing import Iterable

import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import scan

import config


def make_client() -> Elasticsearch:
    return Elasticsearch(config.ES_HOST, request_timeout=60)


def list_source_indices(
    client: Elasticsearch,
    lookback_days: int | None = None,
    start_date: date | None = None,
    end_date: date | None = None,
) -> list[str]:
    """Return source indices in date range, sorted oldest-first.

    If start_date/end_date given, uses that explicit window (inclusive).
    Otherwise falls back to lookback_days from today.
    """
    if start_date is None and end_date is None:
        if lookback_days is None:
            lookback_days = config.TRAINING_LOOKBACK_DAYS
        end_date = date.today()
        start_date = end_date - timedelta(days=lookback_days)
    resp = client.cat.indices(index=config.SOURCE_INDEX_PATTERN, h="index", format="json")
    out = []
    for row in resp:
        name = row["index"]
        try:
            d = datetime.strptime(name.split("-", 1)[1], "%Y-%m-%d").date()
        except (ValueError, IndexError):
            continue
        if start_date is not None and d < start_date:
            continue
        if end_date is not None and d > end_date:
            continue
        out.append((d, name))
    out.sort()
    return [name for _, name in out]


def list_available_dates(client: Elasticsearch) -> list[date]:
    """All dates with a zes_connector_transform-* index."""
    resp = client.cat.indices(index=config.SOURCE_INDEX_PATTERN, h="index", format="json")
    out = []
    for row in resp:
        try:
            out.append(datetime.strptime(row["index"].split("-", 1)[1], "%Y-%m-%d").date())
        except (ValueError, IndexError):
            continue
    return sorted(out)


def _parse_ts(s: str) -> datetime:
    return datetime.fromisoformat(s)


def _split_into_hour_buckets(start: datetime, end: datetime) -> Iterable[tuple[int, float]]:
    """Yield (hour, minutes_in_that_hour) for the slot [start, end).

    Caller has already constrained both to the same calendar day.
    """
    cur = start
    while cur < end:
        hour = cur.hour
        next_hour = cur.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
        chunk_end = min(next_hour, end)
        minutes = (chunk_end - cur).total_seconds() / 60.0
        if minutes > 0:
            yield hour, minutes
        cur = chunk_end


def fetch_station_aggregates(
    client: Elasticsearch,
    lookback_days: int = config.TRAINING_LOOKBACK_DAYS,
    indices: list[str] | None = None,
    start_date: date | None = None,
    end_date: date | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Walk every doc in the window, return two DataFrames:

    long_df: [station_id, dow, hour, minutes_observed, minutes_charging]
    meta_df: [station_id, station_name, city_name, province_name, lat, lng, dates_seen]

    Window resolution: explicit `indices` > (start_date, end_date) > lookback_days.
    """
    if indices is None:
        if start_date is not None or end_date is not None:
            indices = list_source_indices(client, start_date=start_date, end_date=end_date)
        else:
            indices = list_source_indices(client, lookback_days=lookback_days)
    if not indices:
        raise RuntimeError("No source indices found for requested window")

    # (station_id, dow, hour) -> [observed_min, charging_min]
    cell_agg: dict[tuple[str, int, int], list[float]] = defaultdict(lambda: [0.0, 0.0])
    # station_id -> metadata + set of dates seen
    meta: dict[str, dict] = {}

    for index_name in indices:
        try:
            fetched_date = datetime.strptime(index_name.split("-", 1)[1], "%Y-%m-%d").date()
        except ValueError:
            continue
        day_end = datetime.combine(fetched_date + timedelta(days=1), datetime.min.time())
        dow = fetched_date.weekday()

        for hit in scan(
            client,
            index=index_name,
            query={"query": {"match_all": {}}},
            _source=[
                "station_id", "station_name", "city_name", "province_name",
                "lat", "lng", "connector_id", "availability",
            ],
            size=1000,
            preserve_order=False,
        ):
            src = hit["_source"]
            sid = src.get("station_id")
            if not sid:
                continue

            m = meta.setdefault(sid, {
                "station_name": src.get("station_name"),
                "city_name": src.get("city_name"),
                "province_name": src.get("province_name"),
                "lat": src.get("lat"),
                "lng": src.get("lng"),
                "dates_seen": set(),
            })
            m["dates_seen"].add(fetched_date)

            for slot in src.get("availability", []) or []:
                start_str = slot.get("start_time")
                if not start_str:
                    continue
                try:
                    start = _parse_ts(start_str)
                except ValueError:
                    continue
                end_str = slot.get("end_time")
                end = _parse_ts(end_str) if end_str else day_end
                if end <= start:
                    continue
                # Constrain to the fetched_date window
                day_start = datetime.combine(fetched_date, datetime.min.time())
                start = max(start, day_start)
                end = min(end, day_end)
                if end <= start:
                    continue

                is_charging = slot.get("status") == config.CHARGING_STATUS
                for hour, minutes in _split_into_hour_buckets(start, end):
                    key = (sid, dow, hour)
                    cell_agg[key][0] += minutes
                    if is_charging:
                        cell_agg[key][1] += minutes

    if not cell_agg:
        return pd.DataFrame(columns=["station_id", "dow", "hour", "minutes_observed", "minutes_charging"]), pd.DataFrame()

    long_rows = [
        {"station_id": sid, "dow": dow, "hour": hour,
         "minutes_observed": vals[0], "minutes_charging": vals[1]}
        for (sid, dow, hour), vals in cell_agg.items()
    ]
    long_df = pd.DataFrame(long_rows)

    meta_rows = [
        {"station_id": sid,
         "station_name": m["station_name"],
         "city_name": m["city_name"],
         "province_name": m["province_name"],
         "lat": m["lat"],
         "lng": m["lng"],
         "n_dates_seen": len(m["dates_seen"])}
        for sid, m in meta.items()
    ]
    meta_df = pd.DataFrame(meta_rows)

    return long_df, meta_df


if __name__ == "__main__":
    import sys
    lookback = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    client = make_client()
    indices = list_source_indices(client, lookback)
    print(f"Sources in last {lookback} days: {len(indices)}")
    for i in indices:
        print(f"  - {i}")

    long_df, meta_df = fetch_station_aggregates(client, lookback_days=lookback)
    print(f"\nlong_df: {len(long_df)} cells, {long_df['station_id'].nunique()} stations")
    print(long_df.head())
    print(f"\nmeta_df: {len(meta_df)} stations")
    print(meta_df.head())
    print(f"\nTotal observed minutes: {long_df['minutes_observed'].sum():,.0f}")
    print(f"Total charging minutes: {long_df['minutes_charging'].sum():,.0f}")
