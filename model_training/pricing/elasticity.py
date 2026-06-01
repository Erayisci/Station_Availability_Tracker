"""Price elasticity of EV-charging demand — ASSUMED, never fitted.

The dataset has zero price variation (every observation is at the EPDK base
price), so elasticity is not identifiable from the data. We therefore treat it
as an exogenous, segment-specific parameter grounded in EV-charging literature
ranges, and we always evaluate three scenarios {low, central, high} so every
downstream number ships with a sensitivity band.

η is stored as a POSITIVE magnitude and applied to a price *reduction*:
    demand multiplier from a discount d  =  (1 - d) ** (-η)

Segment priors (magnitudes, central with [low, high]):
  - DC fast charging: more elastic (route choice, time-sensitive)  η≈0.9 [0.6,1.2]
  - AC / destination:  less elastic (dwell-bound, captive)         η≈0.5 [0.3,0.7]
Modifiers:
  - peak hour:   less elastic (need-driven)        × 0.7
  - off-peak:    more elastic (discretionary)      × 1.3
  - urban:       more substitutes                  × 1.15
  - rural:       few substitutes                   × 0.85
"""
from __future__ import annotations

from . import pricing_config as pc

# Central magnitude per segment, plus low/high scenario endpoints.
ETA_BASE = {
    "DC": {"low": 0.6, "central": 0.9, "high": 1.2},
    "AC": {"low": 0.3, "central": 0.5, "high": 0.7},
}

PEAK_MULT = 0.7
OFFPEAK_MULT = 1.3
URBAN_MULT = 1.15
RURAL_MULT = 0.85

# Clamp so no scenario produces a degenerate (non-positive / explosive) value.
ETA_MIN = 0.1
ETA_MAX = 2.0

SCENARIOS = ("low", "central", "high")


def is_peak_hour(hour: int, peak_hour: float | int | None) -> bool:
    """A cell is 'peak' if it's within ±1h of the station's learned peak_hour.

    Falls back to a generic 17:00-21:00 evening peak when peak_hour is unknown.
    """
    if peak_hour is None:
        return 17 <= hour <= 21
    try:
        ph = int(round(float(peak_hour)))
    except (TypeError, ValueError):
        return 17 <= hour <= 21
    return abs(hour - ph) <= 1


def is_urban(nearby_stations_count: float | int | None) -> bool:
    if nearby_stations_count is None:
        return False
    try:
        return float(nearby_stations_count) >= pc.URBAN_NEARBY_THRESHOLD
    except (TypeError, ValueError):
        return False


def effective_eta(
    segment: str,
    hour: int,
    peak_hour: float | int | None = None,
    nearby_stations_count: float | int | None = None,
    scenario: str = "central",
) -> float:
    """Effective elasticity magnitude for one (segment, hour, station) cell."""
    base = ETA_BASE.get(segment, ETA_BASE["AC"])[scenario]
    m_peak = PEAK_MULT if is_peak_hour(hour, peak_hour) else OFFPEAK_MULT
    m_geo = URBAN_MULT if is_urban(nearby_stations_count) else RURAL_MULT
    eta = base * m_peak * m_geo
    return float(min(ETA_MAX, max(ETA_MIN, eta)))


def describe() -> dict:
    """Machine-readable summary of the assumptions (for reports / API)."""
    return {
        "eta_base": ETA_BASE,
        "multipliers": {
            "peak": PEAK_MULT, "offpeak": OFFPEAK_MULT,
            "urban": URBAN_MULT, "rural": RURAL_MULT,
        },
        "clamp": [ETA_MIN, ETA_MAX],
        "scenarios": list(SCENARIOS),
        "note": "Elasticity is assumed, not fitted — no price variation exists in the data.",
    }
