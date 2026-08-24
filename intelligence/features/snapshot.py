"""Compute robust per-station features that survive coverage gaps.

For each station we emit 6 numeric features plus an observation_quality score
and a low_confidence flag. Nulls are returned (not imputed) when a feature
cannot be computed honestly.
"""
from __future__ import annotations

import math
from typing import Optional

import numpy as np
import pandas as pd

import config


def _safe_div(num: float, den: float) -> Optional[float]:
    if den is None or den <= 0 or num is None:
        return None
    return float(num) / float(den)


def _peak_hour(hour_df: pd.DataFrame) -> tuple[Optional[int], Optional[float]]:
    """Return (peak_hour, peak_ratio) using only hours with enough observation.

    hour_df columns: hour, minutes_observed, minutes_charging
    """
    eligible = hour_df[hour_df["minutes_observed"] >= 60 * config.MIN_OBSERVED_HOURS_FOR_FEATURES]
    if eligible.empty:
        return None, None
    ratios = eligible["minutes_charging"] / eligible["minutes_observed"]
    idx = ratios.idxmax()
    return int(eligible.loc[idx, "hour"]), float(ratios.loc[idx])


def _peakiness(hour_df: pd.DataFrame, peak_ratio: Optional[float]) -> Optional[float]:
    if peak_ratio is None:
        return None
    eligible = hour_df[hour_df["minutes_observed"] >= 60 * config.MIN_OBSERVED_HOURS_FOR_FEATURES]
    if eligible.empty:
        return None
    ratios = eligible["minutes_charging"] / eligible["minutes_observed"]
    mean_r = float(ratios.mean())
    if mean_r <= 1e-9:
        return None
    return float(peak_ratio / mean_r)


def _bucket_ratio(hour_df: pd.DataFrame, hours: set[int]) -> tuple[float, float]:
    sub = hour_df[hour_df["hour"].isin(hours)]
    obs = float(sub["minutes_observed"].sum())
    chg = float(sub["minutes_charging"].sum())
    return obs, chg


def _compute_one(station_df: pd.DataFrame) -> dict:
    """Compute the 6 robust features for one station's long-format slice."""
    total_obs = float(station_df["minutes_observed"].sum())
    total_chg = float(station_df["minutes_charging"].sum())
    n_distinct_hours = int(station_df.loc[station_df["minutes_observed"] > 0, "hour"].nunique())
    n_distinct_dows = int(station_df.loc[station_df["minutes_observed"] > 0, "dow"].nunique())

    hour_df = (
        station_df.groupby("hour", as_index=False)[["minutes_observed", "minutes_charging"]].sum()
    )
    peak_hour, peak_ratio = _peak_hour(hour_df)
    peakiness = _peakiness(hour_df, peak_ratio)

    day_obs, day_chg = _bucket_ratio(hour_df, set(config.DAY_HOURS))
    night_obs, night_chg = _bucket_ratio(hour_df, set(config.NIGHT_HOURS))
    day_r = _safe_div(day_chg, day_obs)
    night_r = _safe_div(night_chg, night_obs)
    day_night_ratio = None
    if day_r is not None and night_r is not None and night_r > 0:
        day_night_ratio = float(day_r / night_r)

    dow_df = (
        station_df.groupby("dow", as_index=False)[["minutes_observed", "minutes_charging"]].sum()
    )
    we = dow_df[dow_df["dow"].isin(config.WEEKEND_DOWS)]
    wd = dow_df[dow_df["dow"].isin(config.WEEKDAY_DOWS)]
    we_r = _safe_div(float(we["minutes_charging"].sum()), float(we["minutes_observed"].sum()))
    wd_r = _safe_div(float(wd["minutes_charging"].sum()), float(wd["minutes_observed"].sum()))
    weekend_weekday_ratio = None
    if we_r is not None and wd_r is not None and wd_r > 0:
        weekend_weekday_ratio = float(we_r / wd_r)

    obs_quality = (n_distinct_hours / 24.0) * (n_distinct_dows / 7.0)

    return {
        "charging_ratio": _safe_div(total_chg, total_obs),
        "peak_hour": peak_hour,
        "peakiness": peakiness,
        "day_night_ratio": day_night_ratio,
        "weekend_weekday_ratio": weekend_weekday_ratio,
        "observation_quality": float(obs_quality),
        "n_observed_hours": float(total_obs / 60.0),
        "n_observed_dows": int(n_distinct_dows),
        "n_observed_hour_slots": int(n_distinct_hours),
        "low_confidence": bool(obs_quality < config.LOW_CONFIDENCE_THRESHOLD),
    }


def compute_features(long_df: pd.DataFrame, meta_df: pd.DataFrame) -> pd.DataFrame:
    """Compute robust features for all stations in long_df."""
    if long_df.empty:
        return pd.DataFrame()

    rows = []
    for sid, grp in long_df.groupby("station_id", sort=False):
        feats = _compute_one(grp)
        feats["station_id"] = sid
        rows.append(feats)

    feat_df = pd.DataFrame(rows)
    out = meta_df.merge(feat_df, on="station_id", how="right")
    return out


if __name__ == "__main__":
    import sys
    from ingestion.extract import fetch_station_aggregates, make_client
    lookback = int(sys.argv[1]) if len(sys.argv) > 1 else config.TRAINING_LOOKBACK_DAYS
    client = make_client()
    long_df, meta_df = fetch_station_aggregates(client, lookback_days=lookback)
    feat_df = compute_features(long_df, meta_df)
    print(f"Features computed for {len(feat_df)} stations")
    print(feat_df.describe(include="all").T)
    print("\nNull counts per feature:")
    print(feat_df.isna().sum())
    print("\nSample rows:")
    print(feat_df.head(10).to_string())
