"""The Elasticsearch naming contract — Python side.

This is the mirror of ``com.charging.kernel.es.IndexCatalog`` in the Java
backend, and the two files must agree literally: an index name is the only
thing the two runtimes share.

Why it exists
-------------
The Java backend, this pipeline and the dashboard never call each other. They
exchange data exclusively through date-suffixed indices, so **the index name is
the API**. Before this catalog those names were duplicated as module-level
constants in nine Python modules and fifteen Java services, which meant a
rename could not be carried out safely in either runtime and no single file
described the pipeline.

Naming rule
-----------
Every index in the chain is ``{provider_id}_{dataset}-{YYYY-MM-DD}``, one per
day. Two independent decisions are encoded in that shape. **Date suffixing** is
what makes each stage independently rebuildable: dropping and rebuilding one day
never touches another. **Provider prefixing** is what lets a second network land
beside the first without colliding, which is why the prefixes below are also
reachable as functions. The single exception is ``ZES_STATION_ENVIRONMENT``, a
static enrichment index.

Ownership
---------
Stages are annotated with the runtime that WRITES them. Reading across the
boundary is normal and expected; writing across it is not — if Python starts
writing a Java-owned stage (or vice versa) the pipeline has two sources of
truth for the same day.

Note that ``ZES_STATIONS`` is written by the ZES **adapter** (L1), not by L2: it
holds the provider payload verbatim, so it is a landing zone rather than a
derived dataset. Everything downstream of it is derived.
"""
from __future__ import annotations

from datetime import date as _date

# ── Provider-scoped datasets ────────────────────────────────────────────────
#: Registered provider identifiers. Part of the index name, hence part of the
#: storage contract. Mirrors ``IndexCatalog.PROVIDER_ZES``.
PROVIDER_ZES = "zes"

_DS_STATIONS = "_stations"
_DS_CONNECTOR_TRANSFORM = "_connector_transform"
_DS_STATION_PERFORMANCE = "_station_performance"


def stations(provider_id: str) -> str:
    """Raw daily station snapshot; written by the provider's L1 adapter."""
    return f"{provider_id}{_DS_STATIONS}"


def connector_transform(provider_id: str) -> str:
    """Connector-level explosion of the snapshot; source of all analytics."""
    return f"{provider_id}{_DS_CONNECTOR_TRANSFORM}"


def station_performance(provider_id: str) -> str:
    """Per-station utilization, demand/supply tiers and recommendation codes."""
    return f"{provider_id}{_DS_STATION_PERFORMANCE}"


# ── L1/L2 chain, pre-composed for ZES ───────────────────────────────────────
ZES_STATIONS = PROVIDER_ZES + _DS_STATIONS
ZES_CONNECTOR_TRANSFORM = PROVIDER_ZES + _DS_CONNECTOR_TRANSFORM
ZES_STATION_PERFORMANCE = PROVIDER_ZES + _DS_STATION_PERFORMANCE

# ── L3 analytics — static enrichment, not date-suffixed ─────────────────────
ZES_STATION_ENVIRONMENT = "zes_station_environment"

# ── L4 intelligence — written by this pipeline, read by Java ────────────────
ZES_STATION_FEATURES = "zes_station_features"
ZES_STATION_FORECAST = "zes_station_forecast"
ZES_STATION_OPPORTUNITY = "zes_station_opportunity"
ZES_STATION_HOURLY_PERFORMANCE = "zes_station_hourly_performance"
ZES_STATION_HOURLY_FORECAST = "zes_station_hourly_forecast"

#: Written by ``intelligence/descriptive/anomaly.py``. No Java reader yet — the
#: descriptive layer has no dashboard surface, see that package's README.
ZES_STATION_ANOMALY = "zes_station_anomaly"


def name_for(prefix: str, day: _date | str | None = None) -> str:
    """``prefix-YYYY-MM-DD``; ``None`` means today."""
    if day is None:
        day = _date.today()
    return f"{prefix}-{day}"


def all_dates(prefix: str) -> str:
    """Wildcard matching every day of one stage, for ``cat indices`` sweeps."""
    return f"{prefix}-*"


def date_of(index_name: str, prefix: str) -> str | None:
    """Extract ``YYYY-MM-DD`` from a concrete index name, or ``None``."""
    head = f"{prefix}-"
    return index_name[len(head):] if index_name.startswith(head) else None
