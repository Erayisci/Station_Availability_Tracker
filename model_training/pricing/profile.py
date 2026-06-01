"""Hour-of-day shape profiles per (station, segment).

A profile is a 24-vector w with mean 1: w[h] is how much busier hour h is than
the station-segment's daily average. Multiplying the daily forecast by w
disaggregates it into an hourly forecast that preserves the daily total.

Profiles are estimated from the recent charging history (connector_transform).
Station-segments with too little signal back off to a SEGMENT archetype (the
average shape across all well-observed station-segments of that segment), so a
data-thin station still gets a plausible AC/DC curve.

Only history STRICTLY BEFORE the target date is used, so the same routine is
safe to call inside the backtest without leakage.
"""
from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta
from typing import Optional

import numpy as np
from elasticsearch import Elasticsearch
from elasticsearch.helpers import scan

import config
from extract import make_client, _parse_ts, _split_into_hour_buckets
from . import pricing_config as pc

SOURCE_PREFIX = "zes_connector_transform"

# Minimum total charging minutes over the window for a station-segment to use
# its OWN profile rather than the segment archetype.
MIN_CHARGING_MINUTES = 120.0
MIN_NONZERO_HOURS = 3

DEFAULT_LOOKBACK_DAYS = 21


def _list_source_dates(client: Elasticsearch) -> list[date]:
    resp = client.cat.indices(index=f"{SOURCE_PREFIX}-*", h="index", format="json")
    out = []
    for row in resp:
        if row["index"].startswith(SOURCE_PREFIX + "-"):
            try:
                out.append(date.fromisoformat(row["index"].split("-", 1)[1]))
            except ValueError:
                continue
    return sorted(out)


def _normalize_mean_one(charging_by_hour: np.ndarray) -> Optional[np.ndarray]:
    """Scale a 24-vector so its mean is 1. Returns None if it carries no signal."""
    total = charging_by_hour.sum()
    if total <= 0:
        return None
    return charging_by_hour / (total / 24.0)


def build_profiles(
    target_date: date,
    lookback_days: int = DEFAULT_LOOKBACK_DAYS,
    client: Optional[Elasticsearch] = None,
) -> dict:
    """Return {'profiles': {(sid,seg): [24]}, 'archetypes': {seg: [24]}, 'stats': {...}}."""
    client = client or make_client()
    all_dates = _list_source_dates(client)
    window = [d for d in all_dates
              if d < target_date and d >= target_date - timedelta(days=lookback_days)]
    if not window:
        # Fall back to any available history before target, else all dates.
        window = [d for d in all_dates if d < target_date] or all_dates
    if not window:
        return {"profiles": {}, "archetypes": {}, "stats": {"error": "no source history"}}

    # (sid, seg) -> 24-vector of charging minutes summed over the window
    charging: dict[tuple[str, str], np.ndarray] = defaultdict(lambda: np.zeros(24))

    for d in window:
        idx = f"{SOURCE_PREFIX}-{d.isoformat()}"
        day_start = datetime.combine(d, datetime.min.time())
        day_end = datetime.combine(d + timedelta(days=1), datetime.min.time())
        for hit in scan(
            client, index=idx, query={"query": {"match_all": {}}},
            _source=["station_id", "connector_type", "availability"],
            size=1000, preserve_order=False,
        ):
            src = hit["_source"]
            sid = src.get("station_id")
            seg = pc.segment_of(src.get("connector_type"))
            if not sid or seg is None:
                continue
            vec = charging[(sid, seg)]
            for slot in src.get("availability", []) or []:
                if slot.get("status") != config.CHARGING_STATUS:
                    continue
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
                for hour, minutes in _split_into_hour_buckets(start, end):
                    vec[hour] += minutes

    # Segment archetypes: average of normalized shapes over well-observed cells.
    arch_acc: dict[str, list[np.ndarray]] = defaultdict(list)
    profiles: dict[tuple[str, str], np.ndarray] = {}
    n_own = 0
    for (sid, seg), vec in charging.items():
        nonzero = int((vec > 0).sum())
        norm = _normalize_mean_one(vec)
        if norm is not None and vec.sum() >= MIN_CHARGING_MINUTES and nonzero >= MIN_NONZERO_HOURS:
            profiles[(sid, seg)] = norm
            arch_acc[seg].append(norm)
            n_own += 1

    archetypes: dict[str, np.ndarray] = {}
    for seg, shapes in arch_acc.items():
        archetypes[seg] = np.mean(np.vstack(shapes), axis=0)
    # Guarantee an archetype for every segment seen, even if none were well-observed.
    for seg in {s for (_, s) in charging.keys()}:
        if seg not in archetypes:
            archetypes[seg] = np.ones(24)

    return {
        "profiles": {k: v for k, v in profiles.items()},
        "archetypes": archetypes,
        "stats": {
            "window": [w.isoformat() for w in window],
            "n_station_segments_seen": len(charging),
            "n_with_own_profile": n_own,
            "n_backed_off_to_archetype": len(charging) - n_own,
        },
    }


def profile_for(profiles_bundle: dict, sid: str, seg: str) -> np.ndarray:
    """Look up a station-segment's profile, backing off to the segment archetype."""
    own = profiles_bundle["profiles"].get((sid, seg))
    if own is not None:
        return own
    arch = profiles_bundle["archetypes"].get(seg)
    return arch if arch is not None else np.ones(24)


if __name__ == "__main__":
    import json
    import sys
    d = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else date.today()
    bundle = build_profiles(d)
    print(json.dumps(bundle["stats"], indent=2, default=str))
    for seg, arch in bundle["archetypes"].items():
        print(f"{seg} archetype (mean-1):", [round(float(x), 2) for x in arch])
