"""Discount optimizer — set discounts per (city, segment, hour) to maximize
revenue while flattening utilization, under EPDK discount-only rules.

Objective (maximized):
    Σ_cell revenue(d) + γ · Σ_(city,segment) (PAR_baseline - PAR_policy)

where PAR = max_h(u_h) / mean_h(u_h) is the peak-to-average ratio of a
city-segment's 24-hour served-utilization profile.

Why PAR and not cross-cell variance: a discount-only lever can only ADD demand
(induced), never shave a peak, so cross-cell variance cannot truly improve and
would be a dishonest "evenness" KPI. But because peak hours are less elastic
(PEAK_MULT < 1) and busy cells are pinned to d=0, revenue-optimal discounting
naturally lands on elastic OFF-PEAK hours — raising the trough, lifting the mean
while the peak holds, so the daily profile FLATTENS (PAR falls). That is the
honest, achievable form of "distribute demand more evenly across time", and it
is local per city-segment (cheap inside coordinate ascent). γ is in ₺ per unit
of PAR reduction; γ=0 ⇒ pure revenue. Cross-cell variance is still REPORTED.

Constraints:
  - d ∈ DISCOUNT_TIERS ⊆ [0, D_MAX]            (EPDK: discount-only, bounded)
  - d = 0 if baseline u0 ≥ BUSY_THRESHOLD       (never discount a busy cell)
  - soft temporal smoothness across adjacent hours within a (city, segment)

Method: per-cell tier enumeration (greedy seed) → coordinate-ascent sweeps that
recompute each segment's variance as cells change. Fast at this scale
(~150 city-segments × 24h). The policy is decided under the CENTRAL elasticity;
revenue is then re-evaluated under low/central/high for the sensitivity band.

Output index: zes_station_pricing-{date}, with level="city" decision rows and
level="station" drill-down rows that inherit their city's per-hour discounts.
"""
from __future__ import annotations

import json
from datetime import date, datetime
from typing import Optional

import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import bulk, scan

from extract import make_client
from . import demand_model as dm
from . import elasticity as el
from . import pricing_config as pc
from .city_predict import CITY_FORECAST_PREFIX
from .hourly_predict import HOURLY_FORECAST_PREFIX

PRICING_PREFIX = "zes_station_pricing"
SMOOTHNESS_PENALTY = 50.0  # ₺-equivalent per unit (Δd)² between adjacent hours

INDEX_MAPPING = {
    "mappings": {
        "properties": {
            "level":             {"type": "keyword"},   # "city" | "station"
            "city_name":         {"type": "keyword"},
            "station_id":        {"type": "keyword"},
            "station_name":      {"type": "keyword"},
            "segment":           {"type": "keyword"},
            "lat":               {"type": "float"},
            "lng":               {"type": "float"},
            "location":          {"type": "geo_point"},
            "forecast_for_date": {"type": "date"},
            "connector_count":   {"type": "integer"},
            "avg_power_kw":      {"type": "float"},
            "n_stations":        {"type": "integer"},
            "baseline_utilization_pct":  {"type": "float"},   # array[24]
            "recommended_discount":      {"type": "float"},   # array[24] (fractions)
            "predicted_utilization_pct": {"type": "float"},   # array[24] served under discount
            "baseline_revenue":  {"type": "float"},
            "predicted_revenue": {"type": "float"},
            "revenue_uplift_pct":      {"type": "float"},
            "revenue_uplift_pct_low":  {"type": "float"},
            "revenue_uplift_pct_high": {"type": "float"},
            "mean_discount":           {"type": "float"},
            "pct_hours_discounted":    {"type": "float"},
            "var_baseline":      {"type": "float"},
            "var_policy":        {"type": "float"},
            "gamma":             {"type": "float"},
            "eta_scenario_used": {"type": "keyword"},
            "pricing_version":   {"type": "keyword"},
            "computed_at":       {"type": "date"},
        }
    }
}


def _ensure_index(client: Elasticsearch, name: str) -> None:
    if not client.indices.exists(index=name):
        client.indices.create(index=name, body=INDEX_MAPPING)


def _scan_df(client: Elasticsearch, idx: str) -> pd.DataFrame:
    if not client.indices.exists(index=idx):
        return pd.DataFrame()
    return pd.DataFrame([h["_source"] for h in scan(
        client, index=idx, query={"query": {"match_all": {}}}, size=1000)])


class _Cells:
    """Flattened (cell-major) arrays for the optimizer. One cell = (city, segment, hour)."""

    def __init__(self, city_df: pd.DataFrame):
        u0, eta_c, eta_lo, eta_hi, conn, power, price = [], [], [], [], [], [], []
        seg_of, city_of, hour_of, base_idx = [], [], [], []
        # neighbor (same city-seg, adjacent hour) indices for smoothness
        self.row_hour_base = {}  # (city,seg) -> starting flat index (hour 0)
        flat = 0
        for _, r in city_df.iterrows():
            seg = r["segment"]
            hourly = np.asarray(r["hourly_utilization_pct"], dtype=float)
            self.row_hour_base[(r["city_name"], seg)] = flat
            for h in range(24):
                u = float(hourly[h])
                u0.append(u)
                eta_c.append(el.effective_eta(seg, h, None, r.get("n_stations"), "central"))
                eta_lo.append(el.effective_eta(seg, h, None, r.get("n_stations"), "low"))
                eta_hi.append(el.effective_eta(seg, h, None, r.get("n_stations"), "high"))
                conn.append(float(r["connector_count"]))
                power.append(float(r["avg_power_kw"]))
                price.append(pc.BASE_PRICE.get(seg, pc.BASE_PRICE["AC"]))
                seg_of.append(seg)
                city_of.append(r["city_name"])
                hour_of.append(h)
                base_idx.append(flat)  # unused placeholder
                flat += 1
        self.u0 = np.array(u0)
        self.eta = {"central": np.array(eta_c), "low": np.array(eta_lo), "high": np.array(eta_hi)}
        self.conn = np.array(conn)
        self.power = np.array(power)
        self.price = np.array(price)
        self.seg = np.array(seg_of)
        self.city = np.array(city_of)
        self.hour = np.array(hour_of)
        self.n = flat
        # segment membership masks for variance grouping
        self.seg_masks = {s: (self.seg == s) for s in set(seg_of)}

    def revenue(self, d, scenario="central"):
        return dm.revenue(self.u0, d, self.eta[scenario], self.conn, self.power, self.price)

    def served(self, d, scenario="central"):
        return dm.u_served(self.u0, d, self.eta[scenario])


def _segment_variance(served: np.ndarray, cells: _Cells) -> dict:
    return {s: float(np.var(served[mask])) for s, mask in cells.seg_masks.items()}


def _par(block: np.ndarray) -> float:
    """Peak-to-average ratio of a 24-hour profile. 1.0 = perfectly flat."""
    m = float(block.mean())
    return float(block.max() / m) if m > 1e-9 else 1.0


def _block_pars(served: np.ndarray, cells: "_Cells") -> dict:
    return {key: _par(served[base:base + 24])
            for key, base in cells.row_hour_base.items()}


def solve(cells: "_Cells", gamma: float = pc.GAMMA, sweeps: int = 3) -> np.ndarray:
    """Run the discount optimization and return the per-cell discount vector d.

    Pure (no I/O) so both the forward optimizer and the backtest can call it.
    """
    tiers = np.array(pc.DISCOUNT_TIERS)

    # Busy guard: cells at/above the threshold are pinned to d=0.
    busy = cells.u0 >= pc.BUSY_THRESHOLD

    # --- Greedy seed: per-cell best-revenue tier (distribution ignored) ---
    rev_by_tier = np.vstack([cells.revenue(np.full(cells.n, t)) for t in tiers])  # (T, n)
    d = tiers[np.argmax(rev_by_tier, axis=0)]
    d[busy] = 0.0

    base_served = cells.served(np.zeros(cells.n))
    par_baseline = _block_pars(base_served, cells)

    # --- Coordinate-ascent sweeps (per-block PAR coupling + smoothness) ---
    for _ in range(sweeps):
        changed = False
        served = cells.served(d)
        for i in range(cells.n):
            if busy[i]:
                continue
            seg = cells.seg[i]
            ckey = (cells.city[i], seg)
            base = cells.row_hour_base[ckey]
            block = served[base:base + 24].copy()
            h = cells.hour[i]
            # adjacent-hour neighbors within the same (city, seg) for smoothness
            neigh = []
            if h > 0:
                neigh.append(base + (h - 1))
            if h < 23:
                neigh.append(base + (h + 1))

            best_score, best_d, best_served_i = -np.inf, d[i], served[i]
            for ti, t in enumerate(tiers):
                rev = rev_by_tier[ti, i]
                served_i = dm.u_served(cells.u0[i], t, cells.eta["central"][i])
                block[h] = served_i
                dist_benefit = gamma * (par_baseline[ckey] - _par(block))
                smooth_pen = SMOOTHNESS_PENALTY * sum((t - d[j]) ** 2 for j in neigh)
                score = rev + dist_benefit - smooth_pen
                if score > best_score:
                    best_score, best_d, best_served_i = score, t, served_i
            block[h] = best_served_i
            if best_d != d[i]:
                d[i] = best_d
                served[i] = best_served_i
                changed = True
        if not changed:
            break

    return d


def evaluate(cells: "_Cells", d: np.ndarray) -> dict:
    """Metrics for a policy d over the given cells, with eta sensitivity bands."""
    base_served = cells.served(np.zeros(cells.n))
    served_policy = cells.served(d)
    rev_base = {s: cells.revenue(np.zeros(cells.n), s) for s in el.SCENARIOS}
    rev_pol = {s: cells.revenue(d, s) for s in el.SCENARIOS}
    par_base = _block_pars(base_served, cells)
    par_pol = _block_pars(served_policy, cells)
    return {
        "base_served": base_served, "served_policy": served_policy,
        "rev_base": rev_base, "rev_pol": rev_pol,
        "var_baseline": _segment_variance(base_served, cells),
        "var_policy": _segment_variance(served_policy, cells),
        "par_mean_baseline": float(np.mean(list(par_base.values()))),
        "par_mean_policy": float(np.mean(list(par_pol.values()))),
    }


def optimize(target_date: date, gamma: float = pc.GAMMA, sweeps: int = 3,
             client: Optional[Elasticsearch] = None,
             write: bool = True) -> dict:
    client = client or make_client()
    city_df = _scan_df(client, f"{CITY_FORECAST_PREFIX}-{target_date.isoformat()}")
    if city_df.empty:
        return {"error": f"{CITY_FORECAST_PREFIX}-{target_date.isoformat()} not found — "
                         f"run predict_city first"}

    cells = _Cells(city_df)
    d = solve(cells, gamma=gamma, sweeps=sweeps)
    ev = evaluate(cells, d)

    result = _write_outputs(
        client, target_date, city_df, cells, d, ev["served_policy"],
        ev["rev_base"], ev["rev_pol"], ev["var_baseline"], ev["var_policy"], gamma, write,
    )
    result["par_mean_baseline"] = round(ev["par_mean_baseline"], 3)
    result["par_mean_policy"] = round(ev["par_mean_policy"], 3)
    result["par_reduction"] = round(ev["par_mean_baseline"] - ev["par_mean_policy"], 3)
    return result


def _uplift(base_sum: float, pol_sum: float) -> float:
    return round((pol_sum - base_sum) / base_sum * 100.0, 2) if base_sum > 0 else 0.0


def build_city_cells_from_observed(client: Elasticsearch, d: date) -> Optional["_Cells"]:
    """Aggregate observed zes_station_hourly_performance-{d} into city cells.

    Mirrors city_predict's connector-weighted aggregation but on OBSERVED data,
    so the backtest can optimize against ground-truth utilization.
    """
    from .hourly_data_builder import fetch_hourly_performance
    perf = fetch_hourly_performance(client, d)
    if perf.empty:
        return None
    perf["connector_count"] = pd.to_numeric(perf["connector_count"], errors="coerce").fillna(0.0)
    perf["avg_power_kw"] = pd.to_numeric(perf["avg_power_kw"], errors="coerce")
    mat = np.vstack(perf["hourly_utilization_pct"].apply(lambda x: np.asarray(x, dtype=float)))
    rows = []
    for (city, seg), grp in perf.groupby(["city_name", "segment"]):
        w = grp["connector_count"].to_numpy()
        if w.sum() <= 0:
            continue
        sub = mat[[perf.index.get_loc(i) for i in grp.index]]
        hourly = np.average(sub, axis=0, weights=w)
        pw = grp["avg_power_kw"].fillna(pc.FALLBACK_POWER_KW.get(seg, 22.0)).to_numpy()
        rows.append({
            "city_name": city, "segment": seg,
            "hourly_utilization_pct": [float(x) for x in hourly],
            "connector_count": int(w.sum()),
            "avg_power_kw": float(np.average(pw, weights=w)),
            "n_stations": int(len(grp)),
        })
    if not rows:
        return None
    return _Cells(pd.DataFrame(rows))


def _write_outputs(client, target_date, city_df, cells, d, served_policy,
                   rev_base, rev_pol, var_baseline, var_policy, gamma, write):
    index_name = f"{PRICING_PREFIX}-{target_date.isoformat()}"
    computed_at = datetime.utcnow().isoformat() + "Z"

    # Per (city, segment) slices back into 24-hour arrays.
    docs = []
    city_discounts = {}  # (city, seg) -> np.array[24] for station drill-down
    for (city, seg), base in cells.row_hour_base.items():
        sl = slice(base, base + 24)
        disc = d[sl]
        city_discounts[(city, seg)] = disc
        u0 = cells.u0[sl]
        served = served_policy[sl]
        rb = float(rev_base["central"][sl].sum())
        rp = float(rev_pol["central"][sl].sum())
        row = city_df[(city_df["city_name"] == city) & (city_df["segment"] == seg)].iloc[0]
        docs.append({"_index": index_name, "_id": f"city_{city}_{seg}", "_source": {
            "level": "city", "city_name": city, "segment": seg,
            "forecast_for_date": target_date.isoformat(),
            "connector_count": int(row["connector_count"]),
            "avg_power_kw": float(row["avg_power_kw"]),
            "n_stations": int(row["n_stations"]),
            "baseline_utilization_pct": [round(float(x), 2) for x in u0],
            "recommended_discount": [round(float(x), 3) for x in disc],
            "predicted_utilization_pct": [round(float(x), 2) for x in served],
            "baseline_revenue": round(rb, 2),
            "predicted_revenue": round(rp, 2),
            "revenue_uplift_pct": _uplift(rb, rp),
            "revenue_uplift_pct_low": _uplift(
                float(rev_base["low"][sl].sum()), float(rev_pol["low"][sl].sum())),
            "revenue_uplift_pct_high": _uplift(
                float(rev_base["high"][sl].sum()), float(rev_pol["high"][sl].sum())),
            "mean_discount": round(float(disc.mean()), 3),
            "pct_hours_discounted": round(float((disc > 0).mean() * 100.0), 1),
            "var_baseline": round(var_baseline[seg], 3),
            "var_policy": round(var_policy[seg], 3),
            "gamma": gamma,
            "eta_scenario_used": "central",
            "pricing_version": pc.PRICING_VERSION,
            "computed_at": computed_at,
        }})

    # --- Station drill-down: inherit the city's per-hour discount ---
    station_count = 0
    if write:
        hf = _scan_df(client, f"{HOURLY_FORECAST_PREFIX}-{target_date.isoformat()}")
        if not hf.empty:
            for _, r in hf.iterrows():
                seg = r["segment"]
                disc = city_discounts.get((r["city_name"], seg))
                if disc is None:
                    continue
                u0 = np.asarray(r["hourly_predicted_pct"], dtype=float)
                conn = float(r.get("connector_count") or 0)
                power = float(r.get("avg_power_kw") or pc.FALLBACK_POWER_KW[seg])
                price = pc.BASE_PRICE.get(seg, pc.BASE_PRICE["AC"])
                eta_c = np.array([el.effective_eta(seg, h, None, None, "central") for h in range(24)])
                served = dm.u_served(u0, disc, eta_c)
                rb = float(dm.revenue(u0, np.zeros(24), eta_c, conn, power, price).sum())
                rp = float(dm.revenue(u0, disc, eta_c, conn, power, price).sum())
                body = {
                    "level": "station", "city_name": r["city_name"],
                    "station_id": r["station_id"], "station_name": r.get("station_name"),
                    "segment": seg, "lat": r.get("lat"), "lng": r.get("lng"),
                    "forecast_for_date": target_date.isoformat(),
                    "connector_count": int(conn), "avg_power_kw": round(power, 2),
                    "baseline_utilization_pct": [round(float(x), 2) for x in u0],
                    "recommended_discount": [round(float(x), 3) for x in disc],
                    "predicted_utilization_pct": [round(float(x), 2) for x in served],
                    "baseline_revenue": round(rb, 2),
                    "predicted_revenue": round(rp, 2),
                    "revenue_uplift_pct": _uplift(rb, rp),
                    "mean_discount": round(float(disc.mean()), 3),
                    "pct_hours_discounted": round(float((disc > 0).mean() * 100.0), 1),
                    "eta_scenario_used": "central",
                    "pricing_version": pc.PRICING_VERSION,
                    "computed_at": computed_at,
                }
                if body["lat"] is not None and body["lng"] is not None:
                    body["location"] = {"lat": body["lat"], "lon": body["lng"]}
                docs.append({"_index": index_name, "_id": f"{r['station_id']}_{seg}",
                             "_source": body})
                station_count += 1

    n_ok = 0
    if write:
        _ensure_index(client, index_name)
        n_ok, errs = bulk(client, docs, chunk_size=500, refresh=True)

    # Headline summary
    tot_base = float(rev_base["central"].sum())
    tot_pol = float(rev_pol["central"].sum())
    return {
        "status": "ok",
        "index": index_name,
        "forecast_for_date": target_date.isoformat(),
        "n_city_segment_cells": int(cells.n // 24),
        "n_station_rows": station_count,
        "gamma": gamma,
        "revenue_uplift_pct": {
            "central": _uplift(tot_base, tot_pol),
            "low": _uplift(float(rev_base["low"].sum()), float(rev_pol["low"].sum())),
            "high": _uplift(float(rev_base["high"].sum()), float(rev_pol["high"].sum())),
        },
        "mean_discount": round(float(d.mean()), 3),
        "pct_cells_discounted": round(float((d > 0).mean() * 100.0), 1),
        "variance_reduction_by_segment": {
            s: round(var_baseline[s] - var_policy[s], 3) for s in var_baseline
        },
        "n_written": int(n_ok),
    }


if __name__ == "__main__":
    import sys
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    d = date.fromisoformat(args[0]) if args else date.today()
    g = float(args[1]) if len(args) > 1 else pc.GAMMA
    print(json.dumps(optimize(d, gamma=g), indent=2, default=str))
