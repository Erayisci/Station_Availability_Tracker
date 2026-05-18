"""Sanity-check archetypes by plotting per-cluster hourly profiles.

For each archetype, plots:
  - the mean charging-by-hour curve across all member stations (bold)
  - 5 random member stations overlaid (thin lines)

If a cluster is real, the members should track the mean curve. If they're
scattered, the cluster is spurious and we should re-think k or features.

Output: reports/{today}/sanity_check_archetypes.png
"""
from __future__ import annotations

import random
from datetime import date

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from elasticsearch import Elasticsearch
from elasticsearch.helpers import scan

import config
from extract import fetch_station_aggregates, make_client

N_SAMPLES_PER_CLUSTER = 5
SKIP_ARCHETYPES = {"UNKNOWN"}


def _station_archetypes(client: Elasticsearch, index_name: str) -> pd.DataFrame:
    rows = []
    for hit in scan(
        client,
        index=index_name,
        query={"query": {"match_all": {}}},
        _source=["station_id", "station_name", "archetype_v1"],
        size=1000,
    ):
        s = hit["_source"]
        rows.append({
            "station_id": s["station_id"],
            "station_name": s.get("station_name") or s["station_id"][:8],
            "archetype": s.get("archetype_v1") or "UNKNOWN",
        })
    return pd.DataFrame(rows)


def _hour_profile(long_df: pd.DataFrame) -> pd.DataFrame:
    """Per-station charging_ratio by hour. Returns wide DF (station_id x 24 cols)."""
    agg = long_df.groupby(["station_id", "hour"], as_index=False)[
        ["minutes_observed", "minutes_charging"]
    ].sum()
    agg["ratio"] = np.where(
        agg["minutes_observed"] > 0,
        agg["minutes_charging"] / agg["minutes_observed"],
        np.nan,
    )
    wide = agg.pivot(index="station_id", columns="hour", values="ratio")
    wide = wide.reindex(columns=range(24))
    return wide


def run_validation(
    output_date: date | None = None,
    lookback_days: int = config.TRAINING_LOOKBACK_DAYS,
    seed: int = 42,
) -> str:
    output_date = output_date or date.today()
    index_name = f"{config.OUTPUT_INDEX_PREFIX}-{output_date.isoformat()}"

    client = make_client()
    print(f"[validate] reading archetypes from {index_name}")
    arch_df = _station_archetypes(client, index_name)
    archetypes = sorted(a for a in arch_df["archetype"].unique() if a not in SKIP_ARCHETYPES)
    print(f"[validate] {len(archetypes)} archetypes: {archetypes}")

    print(f"[validate] pulling hourly profiles ({lookback_days}d lookback)...")
    long_df, _ = fetch_station_aggregates(client, lookback_days=lookback_days)
    profile = _hour_profile(long_df)
    print(f"[validate] profiles for {len(profile)} stations")

    rng = random.Random(seed)
    n_clusters = len(archetypes)
    fig, axes = plt.subplots(
        n_clusters, 1, figsize=(11, 2.8 * n_clusters), sharex=True
    )
    if n_clusters == 1:
        axes = [axes]

    hours = np.arange(24)
    for ax, arch in zip(axes, archetypes):
        members = arch_df[arch_df["archetype"] == arch]["station_id"].tolist()
        members = [s for s in members if s in profile.index]
        if not members:
            ax.set_title(f"{arch} — no members with profile data")
            continue

        # Cluster mean curve (over all members, ignoring NaN cells)
        mean_curve = profile.loc[members].mean(axis=0, skipna=True).values

        # 5 random sample members
        sample = rng.sample(members, min(N_SAMPLES_PER_CLUSTER, len(members)))
        for sid in sample:
            curve = profile.loc[sid].values
            name = arch_df.loc[arch_df["station_id"] == sid, "station_name"].iloc[0]
            ax.plot(hours, curve, color="#9ca3af", linewidth=1, alpha=0.7, label=name[:30])

        ax.plot(
            hours, mean_curve, color="#10b981", linewidth=2.5,
            label=f"MEAN (n={len(members)})",
        )
        ax.set_title(f"{arch}  —  {len(members)} stations")
        ax.set_ylabel("charging fraction")
        ax.set_ylim(0, max(1.0, np.nanmax(mean_curve) * 1.4))
        ax.set_xticks(hours)
        ax.grid(alpha=0.2)
        ax.legend(loc="upper left", fontsize=7, ncol=2)

    axes[-1].set_xlabel("hour of day")
    fig.suptitle(f"Archetype sanity-check  —  {index_name}", fontsize=13, y=1.0)
    fig.tight_layout()

    out_dir = config.REPORTS_DIR / output_date.isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "sanity_check_archetypes.png"
    fig.savefig(out_path, dpi=110, bbox_inches="tight")
    plt.close(fig)

    # Also print a quick numeric summary
    print("\n=== centroid vs members consistency ===")
    print(f"{'archetype':32s} {'n':>5} {'within_corr':>12} {'within_std':>12}")
    for arch in archetypes:
        members = [s for s in arch_df[arch_df["archetype"] == arch]["station_id"] if s in profile.index]
        if len(members) < 2:
            continue
        sub = profile.loc[members].fillna(0.0).values
        mean_curve = sub.mean(axis=0)
        # Correlation of each member with the mean curve, averaged
        corrs = []
        for row in sub:
            if row.std() > 0 and mean_curve.std() > 0:
                corrs.append(float(np.corrcoef(row, mean_curve)[0, 1]))
        avg_corr = float(np.mean(corrs)) if corrs else float("nan")
        within_std = float(sub.std(axis=0).mean())
        print(f"{arch:32s} {len(members):>5} {avg_corr:>12.3f} {within_std:>12.4f}")

    print(f"\n[validate] wrote {out_path}")
    return str(out_path)


if __name__ == "__main__":
    import sys
    out_date = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else None
    run_validation(output_date=out_date)
