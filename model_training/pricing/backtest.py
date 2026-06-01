"""Pricing backtest — model-based counterfactual simulation.

IMPORTANT: the counterfactual (utilization at a discount) is UNOBSERVABLE — no
discount was ever applied in the data. This backtest is therefore a SIMULATION,
not a measurement. It validates internal consistency, capacity safety, and the
revenue-vs-evenness trade-off; it does NOT validate the assumed elasticity. The
headline is the elasticity SENSITIVITY BAND, never a single number.

For each date D with an observed zes_station_hourly_performance-D index:
  1. aggregate observed hourly utilization into city×segment cells,
  2. run the optimizer to get the discount policy,
  3. simulate served utilization + revenue under η ∈ {low, central, high},
  4. compare to the d=0 baseline (revenue uplift %, PAR reduction, variance).

Reports → model_training/reports/{date}/pricing_backtest.{json,md,png}, in the
same style as forecast/backtest.py.
"""
from __future__ import annotations

import json
from datetime import date, datetime
from pathlib import Path
from typing import Optional

import numpy as np
from elasticsearch import Elasticsearch

import config
from extract import make_client
from . import elasticity as el
from . import pricing_config as pc
from .hourly_data_builder import list_hourly_perf_dates
from .optimize import build_city_cells_from_observed, solve, evaluate


def _uplift(base: float, pol: float) -> float:
    return round((pol - base) / base * 100.0, 2) if base > 0 else 0.0


def _backtest_one(client: Elasticsearch, d: date, gamma: float, sweeps: int) -> Optional[dict]:
    cells = build_city_cells_from_observed(client, d)
    if cells is None:
        return None
    disc = solve(cells, gamma=gamma, sweeps=sweeps)
    ev = evaluate(cells, disc)

    uplift = {s: _uplift(float(ev["rev_base"][s].sum()), float(ev["rev_pol"][s].sum()))
              for s in el.SCENARIOS}
    busy_share = float((cells.u0 >= pc.BUSY_THRESHOLD).mean() * 100.0)
    # Capacity safety: simulated served must never exceed U_cap (by construction).
    headroom_violations = int((ev["served_policy"] > pc.U_CAP + 1e-6).sum())
    return {
        "date": d.isoformat(),
        "n_city_segment_cells": int(cells.n // 24),
        "revenue_uplift_pct": uplift,
        "mean_discount": round(float(disc.mean()), 3),
        "pct_cells_discounted": round(float((disc > 0).mean() * 100.0), 1),
        "par_mean_baseline": round(ev["par_mean_baseline"], 3),
        "par_mean_policy": round(ev["par_mean_policy"], 3),
        "par_reduction": round(ev["par_mean_baseline"] - ev["par_mean_policy"], 3),
        "variance_reduction_by_segment": {
            s: round(ev["var_baseline"][s] - ev["var_policy"][s], 3) for s in ev["var_baseline"]
        },
        "pct_cells_busy_guarded": round(busy_share, 1),
        "capacity_headroom_violations": headroom_violations,
    }


def backtest(start_date: Optional[date] = None, end_date: Optional[date] = None,
             gamma: float = pc.GAMMA, sweeps: int = 3) -> dict:
    client = make_client()
    dates = list_hourly_perf_dates(client)
    if start_date:
        dates = [d for d in dates if d >= start_date]
    if end_date:
        dates = [d for d in dates if d <= end_date]
    if not dates:
        return {"error": "no zes_station_hourly_performance-* indices — run build_hourly_performance"}

    per_date = [r for r in (_backtest_one(client, d, gamma, sweeps) for d in dates) if r]
    if not per_date:
        return {"error": "no backtestable dates"}

    def _mean(key_path):
        vals = []
        for r in per_date:
            v = r
            for k in key_path:
                v = v[k]
            vals.append(v)
        return round(float(np.mean(vals)), 2)

    overall = {
        "revenue_uplift_pct": {s: _mean(["revenue_uplift_pct", s]) for s in el.SCENARIOS},
        "mean_discount": _mean(["mean_discount"]),
        "pct_cells_discounted": _mean(["pct_cells_discounted"]),
        "par_reduction": _mean(["par_reduction"]),
        "capacity_headroom_violations": int(sum(r["capacity_headroom_violations"] for r in per_date)),
    }
    return {
        "status": "ok",
        "is_simulation": True,
        "disclaimer": ("Model-based simulation under ASSUMED elasticity — not a "
                       "measurement. Read the low/high band, not the central point."),
        "n_dates": len(per_date),
        "date_range": [per_date[0]["date"], per_date[-1]["date"]],
        "gamma": gamma,
        "elasticity_assumptions": el.describe(),
        "overall": overall,
        "per_date": per_date,
    }


# --------------------------------------------------------------------------
# Reports (json / markdown / charts) — mirrors forecast/backtest.py style.
# --------------------------------------------------------------------------
def _write_charts(result: dict, out_dir: Path) -> dict:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update({
        "font.family": "DejaVu Sans", "axes.spines.top": False,
        "axes.spines.right": False, "figure.facecolor": "white",
        "axes.titlesize": 11, "axes.titleweight": "600",
    })
    per_date = result["per_date"]
    dates = [r["date"] for r in per_date]
    x = np.arange(len(dates))

    # 1) Revenue uplift with low/high band per date
    fig, ax = plt.subplots(figsize=(11, 4.5))
    cen = [r["revenue_uplift_pct"]["central"] for r in per_date]
    lo = [r["revenue_uplift_pct"]["low"] for r in per_date]
    hi = [r["revenue_uplift_pct"]["high"] for r in per_date]
    ax.fill_between(x, lo, hi, color="#1f77b4", alpha=0.18, label="η low–high band")
    ax.plot(x, cen, "-o", color="#1f77b4", label="central η")
    ax.axhline(0, color="#999", lw=0.8)
    ax.set_xticks(x); ax.set_xticklabels(dates, rotation=45, ha="right", fontsize=8)
    ax.set_ylabel("Revenue uplift %")
    ax.set_title("Simulated revenue uplift vs non-discounted baseline (elasticity band)")
    ax.legend(frameon=False, loc="upper left")
    fig.tight_layout()
    p1 = out_dir / "pricing_uplift.png"
    fig.savefig(p1, dpi=130); plt.close(fig)

    # 2) PAR baseline vs policy (temporal flattening)
    fig, ax = plt.subplots(figsize=(9, 4))
    w = 0.4
    ax.bar(x - w/2, [r["par_mean_baseline"] for r in per_date], w, label="baseline", color="#999")
    ax.bar(x + w/2, [r["par_mean_policy"] for r in per_date], w, label="with discounts", color="#1f77b4")
    ax.set_xticks(x); ax.set_xticklabels(dates, rotation=45, ha="right", fontsize=8)
    ax.set_ylabel("Peak-to-average ratio")
    ax.set_title("Profile flattening — lower PAR = more even across the day")
    ax.legend(frameon=False)
    fig.tight_layout()
    p2 = out_dir / "pricing_par.png"
    fig.savefig(p2, dpi=130); plt.close(fig)
    return {"uplift": p1, "par": p2}


def _write_markdown(result: dict, charts: dict, out_dir: Path) -> Path:
    o = result["overall"]
    up = o["revenue_uplift_pct"]
    md = ["# Pricing Backtest Report (Simulation)", ""]
    md.append(f"*Generated:* `{datetime.utcnow().isoformat(timespec='seconds')}Z`")
    md.append(f"*Dates:* `{result['date_range'][0]}` → `{result['date_range'][1]}` "
              f"({result['n_dates']} dates), γ={result['gamma']}")
    md.append("")
    md.append(f"> ⚠️ {result['disclaimer']}")
    md.append("")
    md.append("## Headline (mean across dates)")
    md.append("")
    md.append("| Revenue uplift (low) | central | (high) | PAR reduction | mean discount | cells discounted |")
    md.append("|---:|---:|---:|---:|---:|---:|")
    md.append(f"| {up['low']:+.2f}% | **{up['central']:+.2f}%** | {up['high']:+.2f}% | "
              f"{o['par_reduction']:+.3f} | {o['mean_discount']*100:.1f}% | {o['pct_cells_discounted']:.0f}% |")
    md.append("")
    md.append(f"*Capacity headroom violations across all dates:* **{o['capacity_headroom_violations']}** "
              f"(should be 0 — served utilization is capped at U_cap={pc.U_CAP}).")
    md.append("")
    if charts.get("uplift"):
        md.append(f"![uplift]({charts['uplift'].name})\n")
    if charts.get("par"):
        md.append(f"![par]({charts['par'].name})\n")
    md.append("## Per-date")
    md.append("")
    md.append("| Date | uplift low | central | high | PAR base→policy | mean disc | % disc |")
    md.append("|---|---:|---:|---:|---:|---:|---:|")
    for r in result["per_date"]:
        u = r["revenue_uplift_pct"]
        md.append(f"| {r['date']} | {u['low']:+.2f}% | {u['central']:+.2f}% | {u['high']:+.2f}% | "
                  f"{r['par_mean_baseline']:.2f}→{r['par_mean_policy']:.2f} | "
                  f"{r['mean_discount']*100:.1f}% | {r['pct_cells_discounted']:.0f}% |")
    md.append("")
    md.append("## Reading the numbers")
    md.append("")
    md.append("- **Revenue uplift** is relative to the non-discounted baseline; insensitive to the "
              "absolute ₺/kWh base price. The low–high band reflects pessimistic→optimistic elasticity.")
    md.append("- **PAR reduction** > 0 means the daily utilization profile got flatter (off-peak filled).")
    md.append("- A negative *low*-scenario uplift means: if demand is less price-sensitive than assumed, "
              "the central-η policy slightly erodes revenue. This is the key risk the band exposes.")
    path = out_dir / "pricing_backtest.md"
    path.write_text("\n".join(md))
    return path


def write_report(result: dict, report_date: Optional[date] = None) -> dict:
    if result.get("status") != "ok":
        return {"error": "backtest failed", "detail": result}
    report_date = report_date or date.today()
    out_dir = config.REPORTS_DIR / report_date.isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "pricing_backtest.json"
    json_path.write_text(json.dumps(result, indent=2, default=str))
    charts = _write_charts(result, out_dir)
    md_path = _write_markdown(result, charts, out_dir)
    return {"out_dir": str(out_dir), "markdown": str(md_path), "json": str(json_path),
            "charts": {k: str(v) for k, v in charts.items()}}


if __name__ == "__main__":
    import sys
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    g = float(args[0]) if args else pc.GAMMA
    res = backtest(gamma=g)
    if "--no-report" in flags or res.get("status") != "ok":
        print(json.dumps(res, indent=2, default=str))
    else:
        written = write_report(res)
        print(json.dumps({"overall": res["overall"], "report": written}, indent=2, default=str))
