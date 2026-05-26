"""Backtest deployed forecasts against observed performance.

For every date D where both `zes_station_forecast-D` and
`zes_station_performance-D` exist in Elasticsearch, join by station_id and
compare:

  - predicted_utilization_pct   (model output, stored at forecast time)
  - source_utilization_pct      (naive baseline = util on source_date)
  - charging_utilization_pct    (ground truth on D)

Reports per-date and overall MAE / RMSE / R² / bias, plus the improvement
of the model over the naive baseline. This is the "did the deployed model
actually predict well?" check — distinct from sanity_check.py which scores
fresh fits on a train/test split.
"""
from __future__ import annotations

import json
from datetime import date, datetime
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import scan
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

import config
from extract import make_client

FORECAST_PREFIX = "zes_station_forecast"
PERF_PREFIX = "zes_station_performance"

FORECAST_FIELDS = [
    "station_id", "station_name", "city_name",
    "predicted_utilization_pct", "source_utilization_pct",
    "source_date", "source_gap_days", "model_version",
]
PERF_FIELDS = ["station_id", "charging_utilization_pct"]


def _scan(client: Elasticsearch, index: str, fields: list[str]) -> pd.DataFrame:
    rows = []
    for hit in scan(
        client,
        index=index,
        query={"query": {"match_all": {}}},
        _source=fields,
        size=1000,
    ):
        rows.append(hit["_source"])
    return pd.DataFrame(rows)


def _list_dates(client: Elasticsearch, prefix: str) -> list[date]:
    resp = client.cat.indices(index=f"{prefix}-*", h="index", format="json")
    out = []
    for rec in resp:
        idx = rec.get("index", "")
        if idx.startswith(prefix + "-"):
            try:
                out.append(date.fromisoformat(idx[len(prefix) + 1:]))
            except ValueError:
                pass
    return sorted(out)


def _metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    if len(y_true) == 0:
        return {"n": 0}
    err = y_pred - y_true
    return {
        "n":    int(len(y_true)),
        "mae":  round(float(mean_absolute_error(y_true, y_pred)), 3),
        "rmse": round(float(np.sqrt(mean_squared_error(y_true, y_pred))), 3),
        "r2":   round(float(r2_score(y_true, y_pred)), 4) if len(y_true) > 1 else None,
        "bias": round(float(err.mean()), 3),
    }


def backtest(
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> dict:
    client = make_client()
    fc_dates = _list_dates(client, FORECAST_PREFIX)
    perf_dates = set(_list_dates(client, PERF_PREFIX))
    overlap = [d for d in fc_dates if d in perf_dates]
    if start_date:
        overlap = [d for d in overlap if d >= start_date]
    if end_date:
        overlap = [d for d in overlap if d <= end_date]

    if not overlap:
        return {"error": "no overlapping forecast/perf dates"}

    per_date = []
    pooled_true, pooled_model, pooled_naive = [], [], []
    pooled_gap = []

    for d in overlap:
        fc = _scan(client, f"{FORECAST_PREFIX}-{d.isoformat()}", FORECAST_FIELDS)
        actual = _scan(client, f"{PERF_PREFIX}-{d.isoformat()}", PERF_FIELDS)
        if fc.empty or actual.empty:
            continue

        actual = actual.rename(columns={"charging_utilization_pct": "actual_pct"})
        merged = fc.merge(actual, on="station_id", how="inner").dropna(
            subset=["predicted_utilization_pct", "source_utilization_pct", "actual_pct"]
        )
        if merged.empty:
            continue

        y_true  = merged["actual_pct"].to_numpy(dtype=float)
        y_model = merged["predicted_utilization_pct"].to_numpy(dtype=float)
        y_naive = merged["source_utilization_pct"].to_numpy(dtype=float)

        m_model = _metrics(y_true, y_model)
        m_naive = _metrics(y_true, y_naive)
        improvement = (
            (m_naive["mae"] - m_model["mae"]) / m_naive["mae"] * 100
            if m_naive.get("mae", 0) > 0 else 0.0
        )

        gap_days = merged["source_gap_days"].dropna()
        per_date.append({
            "date": d.isoformat(),
            "n_stations": int(len(merged)),
            "model_version": merged["model_version"].mode().iat[0] if "model_version" in merged else None,
            "source_gap_days_median": int(gap_days.median()) if not gap_days.empty else None,
            "model":         m_model,
            "naive":         m_naive,
            "improvement_pct_vs_naive": round(improvement, 2),
        })

        pooled_true.append(y_true)
        pooled_model.append(y_model)
        pooled_naive.append(y_naive)
        if not gap_days.empty:
            pooled_gap.append(gap_days.to_numpy())

    if not per_date:
        return {"error": "no overlap rows after join"}

    yt = np.concatenate(pooled_true)
    ym = np.concatenate(pooled_model)
    yn = np.concatenate(pooled_naive)
    overall_model = _metrics(yt, ym)
    overall_naive = _metrics(yt, yn)
    overall_improvement = (
        (overall_naive["mae"] - overall_model["mae"]) / overall_naive["mae"] * 100
        if overall_naive.get("mae", 0) > 0 else 0.0
    )

    # Bucket by source_gap_days to see how accuracy decays with stale input
    gap_buckets = {}
    if pooled_gap:
        gaps = np.concatenate(pooled_gap)
        for lo, hi, label in [(1, 1, "1d"), (2, 3, "2-3d"), (4, 7, "4-7d"), (8, 999, "8d+")]:
            mask = (gaps >= lo) & (gaps <= hi)
            if mask.sum() > 0:
                gap_buckets[label] = {
                    "n":     int(mask.sum()),
                    "model": _metrics(yt[mask], ym[mask]),
                    "naive": _metrics(yt[mask], yn[mask]),
                }

    return {
        "status": "ok",
        "n_dates": len(per_date),
        "date_range": [per_date[0]["date"], per_date[-1]["date"]],
        "overall": {
            "n": overall_model["n"],
            "model": overall_model,
            "naive": overall_naive,
            "improvement_pct_vs_naive": round(overall_improvement, 2),
            "verdict": (
                f"Model MAE={overall_model['mae']} vs naive MAE={overall_naive['mae']} "
                + (
                    f"→ model beats naive by {overall_improvement:.1f}%."
                    if overall_improvement > 0
                    else f"→ naive wins by {-overall_improvement:.1f}% — model is not adding signal."
                )
            ),
        },
        "by_source_gap_days": gap_buckets,
        "per_date": per_date,
    }


def _fmt_metrics(m: dict) -> str:
    if not m or m.get("n", 0) == 0:
        return "—"
    return f"MAE={m['mae']:.2f}, RMSE={m['rmse']:.2f}, R²={m['r2']}, bias={m['bias']:+.2f}"


def _write_charts(result: dict, out_dir: Path) -> dict[str, Path]:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.edgecolor": "#444",
        "axes.labelcolor": "#222",
        "xtick.color": "#444",
        "ytick.color": "#444",
        "axes.titleweight": "600",
        "axes.titlesize": 11,
        "figure.facecolor": "white",
    })
    accent = "#1f77b4"
    naive_c = "#999999"

    per_date = result.get("per_date", [])
    dates = [r["date"] for r in per_date]
    mae_model = [r["model"]["mae"] for r in per_date]
    mae_naive = [r["naive"]["mae"] for r in per_date]
    gaps = [r.get("source_gap_days_median") or 0 for r in per_date]

    # 1) Per-date MAE bars: model vs naive, with gap annotation
    fig, ax = plt.subplots(figsize=(11, 4.5))
    x = np.arange(len(dates))
    w = 0.4
    ax.bar(x - w/2, mae_model, w, label="Model", color=accent)
    ax.bar(x + w/2, mae_naive, w, label="Naive (source util)", color=naive_c)
    ax.set_xticks(x)
    ax.set_xticklabels(dates, rotation=45, ha="right", fontsize=8)
    ax.set_ylabel("MAE (utilization %)")
    ax.set_title("Per-date MAE — deployed model vs naive baseline")
    for i, g in enumerate(gaps):
        if g:
            ax.text(i, max(mae_model[i], mae_naive[i]) + 0.2,
                    f"gap={g}d", ha="center", fontsize=7, color="#666")
    ax.legend(frameon=False, loc="upper left")
    fig.tight_layout()
    p1 = out_dir / "per_date_mae.png"
    fig.savefig(p1, dpi=130)
    plt.close(fig)

    # 2) MAE vs source_gap bucket
    buckets = result.get("by_source_gap_days", {})
    if buckets:
        fig, ax = plt.subplots(figsize=(7, 4))
        labels = list(buckets.keys())
        bx = np.arange(len(labels))
        ax.bar(bx - w/2, [buckets[l]["model"]["mae"] for l in labels], w,
               label="Model", color=accent)
        ax.bar(bx + w/2, [buckets[l]["naive"]["mae"] for l in labels], w,
               label="Naive", color=naive_c)
        ax.set_xticks(bx)
        ax.set_xticklabels(labels)
        ax.set_ylabel("MAE")
        ax.set_xlabel("source_gap_days bucket")
        ax.set_title("Model accuracy decays as input snapshot ages")
        ns = [buckets[l]["n"] for l in labels]
        for i, n in enumerate(ns):
            ax.text(i, max(buckets[labels[i]]["model"]["mae"],
                           buckets[labels[i]]["naive"]["mae"]) + 0.15,
                    f"n={n}", ha="center", fontsize=8, color="#666")
        ax.legend(frameon=False, loc="upper left")
        fig.tight_layout()
        p2 = out_dir / "mae_by_gap.png"
        fig.savefig(p2, dpi=130)
        plt.close(fig)
    else:
        p2 = None

    return {"per_date_mae": p1, "mae_by_gap": p2}


def _write_markdown(result: dict, charts: dict, out_dir: Path) -> Path:
    overall = result["overall"]
    md = []
    md.append("# Forecast Backtest Report")
    md.append("")
    md.append(f"*Generated:* `{datetime.utcnow().isoformat(timespec='seconds')}Z`")
    md.append(f"*Dates covered:* `{result['date_range'][0]}` → `{result['date_range'][1]}` ({result['n_dates']} dates)")
    md.append(f"*Predictions evaluated:* **{overall['n']:,}** station-days")
    md.append("")
    md.append("## Headline")
    md.append("")
    md.append(f"> {overall['verdict']}")
    md.append("")
    md.append("| | n | MAE | RMSE | R² | Bias |")
    md.append("|---|---:|---:|---:|---:|---:|")
    m = overall["model"]; nv = overall["naive"]
    md.append(f"| **Model** | {m['n']:,} | **{m['mae']:.2f}** | {m['rmse']:.2f} | {m['r2']} | {m['bias']:+.2f} |")
    md.append(f"| Naive (source util) | {nv['n']:,} | {nv['mae']:.2f} | {nv['rmse']:.2f} | {nv['r2']} | {nv['bias']:+.2f} |")
    md.append(f"| **Improvement** | — | **{overall['improvement_pct_vs_naive']:+.1f}%** | — | — | — |")
    md.append("")
    md.append("## Accuracy by source-snapshot staleness")
    md.append("")
    md.append("The model is trained on `(t, t+1)` consecutive pairs. When the latest perf snapshot is more than 1 day stale, the autoregressive signal `util_t` is unreliable and accuracy degrades.")
    md.append("")
    if charts.get("mae_by_gap"):
        md.append(f"![MAE by gap]({charts['mae_by_gap'].name})")
        md.append("")
    md.append("| gap_days | n | Model MAE | Naive MAE | Improvement |")
    md.append("|---|---:|---:|---:|---:|")
    for label, b in result.get("by_source_gap_days", {}).items():
        mm = b["model"]; nn = b["naive"]
        imp = (nn["mae"] - mm["mae"]) / nn["mae"] * 100 if nn["mae"] > 0 else 0
        md.append(f"| `{label}` | {b['n']:,} | {mm['mae']:.2f} | {nn['mae']:.2f} | {imp:+.1f}% |")
    md.append("")
    md.append("## Per-date results")
    md.append("")
    if charts.get("per_date_mae"):
        md.append(f"![Per-date MAE]({charts['per_date_mae'].name})")
        md.append("")
    md.append("| Date | n | gap (d) | Model MAE | Naive MAE | Δ vs naive | Model R² |")
    md.append("|---|---:|---:|---:|---:|---:|---:|")
    for r in result["per_date"]:
        md.append(
            f"| {r['date']} | {r['n_stations']:,} | "
            f"{r['source_gap_days_median'] or '—'} | "
            f"{r['model']['mae']:.2f} | {r['naive']['mae']:.2f} | "
            f"{r['improvement_pct_vs_naive']:+.1f}% | {r['model']['r2']} |"
        )
    md.append("")
    md.append("## Reading the numbers")
    md.append("")
    md.append("- **MAE** — mean absolute error in utilization percentage points. Lower is better.")
    md.append("- **Naive baseline** = `predicted = source_utilization_pct` (i.e. \"tomorrow will look like today\"). Beating this is the bar a useful model has to clear.")
    md.append("- **Bias > 0** means the model systematically over-predicts utilization.")
    md.append("- A single per-date row with R² far below 0 usually indicates a data-quality day where actual utilization collapsed to ~0 (outage, ingest gap) — the model and naive both look bad on those dates.")
    md.append("")
    path = out_dir / "forecast_backtest.md"
    path.write_text("\n".join(md))
    return path


def write_report(result: dict, report_date: Optional[date] = None) -> dict:
    if result.get("status") != "ok":
        return {"error": "backtest failed", "detail": result}
    report_date = report_date or date.today()
    out_dir = config.REPORTS_DIR / report_date.isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)

    json_path = out_dir / "forecast_backtest.json"
    json_path.write_text(json.dumps(result, indent=2, default=str))
    charts = _write_charts(result, out_dir)
    md_path = _write_markdown(result, charts, out_dir)

    return {
        "out_dir":  str(out_dir),
        "markdown": str(md_path),
        "json":     str(json_path),
        "charts":   {k: str(v) for k, v in charts.items() if v},
    }


if __name__ == "__main__":
    import sys
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    s = date.fromisoformat(args[0]) if len(args) > 0 else None
    e = date.fromisoformat(args[1]) if len(args) > 1 else None

    result = backtest(start_date=s, end_date=e)
    if "--no-report" in flags or result.get("status") != "ok":
        print(json.dumps(result, indent=2, default=str))
    else:
        written = write_report(result)
        print(json.dumps({"backtest_summary": result["overall"], "report": written},
                         indent=2, default=str))
