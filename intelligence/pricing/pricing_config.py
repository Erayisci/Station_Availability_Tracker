"""Single source of truth for dynamic-pricing assumptions.

This is the RISKIEST file in the package conceptually: every economic
assumption lives here so it is auditable and version-stamped. None of these
are fitted from data (the data has no price variation) — they are policy
inputs and literature-grounded priors.
"""
from __future__ import annotations

# ---------------------------------------------------------------------------
# Pricing model version — bump when any assumption below changes.
# ---------------------------------------------------------------------------
PRICING_VERSION = "1"

# ---------------------------------------------------------------------------
# Base prices (₺/kWh). EPDK sets a *free base price*; stations may only
# DISCOUNT from it. There is one base for AC and one for DC.
#
# PLACEHOLDER VALUES — replace with the real EPDK base tariffs. The HEADLINE
# KPI (relative revenue uplift %) is insensitive to these absolute levels;
# they only scale the illustrative ₺ figures.
# ---------------------------------------------------------------------------
BASE_PRICE = {
    "AC": 9.99,   # ₺/kWh — PLACEHOLDER
    "DC": 12.99,   # ₺/kWh — PLACEHOLDER
}

# Fallback nominal power per segment (kW) when a connector lacks max_power_kw.
# Real per-connector power comes from the `max_power_kw` field in the data;
# these are only used as a backstop.
FALLBACK_POWER_KW = {
    "AC": 22.0,
    "DC": 120.0,
}

# ---------------------------------------------------------------------------
# Discount policy (EPDK: discount-only, so d ∈ [0, D_MAX]).
# Discrete tiers chosen over continuous: operationally deployable, exact
# per-cell enumeration, no false precision given assumed elasticity.
# ---------------------------------------------------------------------------
DISCOUNT_TIERS = [0.0, 0.05, 0.10, 0.15, 0.20]
D_MAX = 0.20

# ---------------------------------------------------------------------------
# Capacity / saturation. Utilization is physically capped; served demand
# passes through a saturating link so revenue gains vanish near capacity
# (this is what produces interior optima — see demand_model.py).
# ---------------------------------------------------------------------------
U_CAP = 95.0            # effective utilization ceiling (%), leaves headroom
BUSY_THRESHOLD = 80.0   # advisory: stations at/above this util shouldn't be discounted

# ---------------------------------------------------------------------------
# Urban / rural split (drives elasticity geo multiplier). Based on
# nearby_stations_count from the performance index.
# ---------------------------------------------------------------------------
URBAN_NEARBY_THRESHOLD = 3   # >= this many nearby stations ⇒ "urban"


def segment_of(connector_type: str | None) -> str | None:
    """Map a connector_type (e.g. 'DC_CCS', 'AC_Type_2') to 'AC' / 'DC'."""
    if not connector_type:
        return None
    ct = connector_type.upper()
    if ct.startswith("DC"):
        return "DC"
    if ct.startswith("AC"):
        return "AC"
    return None
