"""Demand response + revenue accounting for discounts.

All functions are pure and numpy-vectorizable (scalars or arrays), so they are
trivially unit-testable and fast inside the optimizer.

The observed baseline u0 is ALREADY a served (capacity-limited) utilization, so
the model must satisfy u_served(d=0) = u0 exactly. We achieve this by inverting
the saturating link to recover the latent demand that produced u0, scaling THAT
latent demand by the constant-elasticity discount response, and re-saturating:

  saturating link (latent → served):
      saturate(x) = U_cap · (1 - exp(-x / U_cap))
  its inverse (served → latent):
      inv_saturate(u) = -U_cap · ln(1 - u / U_cap)

  latent baseline implied by the observed u0:
      x0 = inv_saturate(u0)
  latent demand under discount d (constant elasticity η):
      x(d) = x0 · (1 - d) ** (-η)
  served demand under discount d:
      u_served(d) = saturate(x(d))            ⇒  u_served(0) = u0  ✓

As u0 → U_cap, x0 → ∞ and du_served/dd → 0  ⇒  discounting a near-capacity cell
adds almost no volume. This is what makes idle cells responsive and busy cells
unresponsive, and yields an INTERIOR revenue optimum instead of the degenerate
corner solution of the pure constant-elasticity model.

Revenue per (station, segment) for `hours` of operation:
      charging_connector_hours = (u_served/100) · connectors · hours
      energy_kWh               = charging_connector_hours · power_kW
      revenue                  = energy_kWh · base_price · (1 - d)

The (1 - d) factor is the price erosion; the volume gain is in u_served. Their
product is non-monotone in d once saturation bites ⇒ interior optimum.
"""
from __future__ import annotations

import numpy as np

from . import pricing_config as pc


def saturate(x, u_cap=pc.U_CAP):
    """Saturating link latent → served: maps [0, ∞) into [0, u_cap)."""
    x = np.asarray(x, dtype=float)
    return u_cap * (1.0 - np.exp(-x / u_cap))


def inv_saturate(u, u_cap=pc.U_CAP):
    """Inverse link served → latent. Clamps u into [0, u_cap) first."""
    u = np.clip(np.asarray(u, dtype=float), 0.0, u_cap - 1e-6)
    return -u_cap * np.log(1.0 - u / u_cap)


def latent_baseline(u0, u_cap=pc.U_CAP):
    """Latent demand implied by the observed served utilization u0."""
    return inv_saturate(u0, u_cap)


def u_latent(u0, d, eta, u_cap=pc.U_CAP):
    """Latent demand under discount d, anchored so u_served(0) == u0."""
    x0 = latent_baseline(u0, u_cap)
    d = np.asarray(d, dtype=float)
    eta = np.asarray(eta, dtype=float)
    return x0 * np.power(np.clip(1.0 - d, 1e-9, 1.0), -eta)


def u_served(u0, d, eta, u_cap=pc.U_CAP):
    """Served (capacity-limited) utilization under discount d. u_served(0)==u0."""
    return saturate(u_latent(u0, d, eta, u_cap), u_cap)


def revenue(u0, d, eta, connectors, power_kw, base_price,
            hours=1.0, u_cap=pc.U_CAP):
    """Revenue (₺) for a cell under discount d.

    Scalar or array inputs. base_price is ₺/kWh for the segment.
    """
    served = u_served(u0, d, eta, u_cap)
    connectors = np.asarray(connectors, dtype=float)
    power_kw = np.asarray(power_kw, dtype=float)
    energy_kwh = (served / 100.0) * connectors * hours * power_kw
    return energy_kwh * base_price * (1.0 - np.asarray(d, dtype=float))


def baseline_served(u0, u_cap=pc.U_CAP):
    """Served utilization at d=0 — equals the observed baseline by construction."""
    return np.clip(np.asarray(u0, dtype=float), 0.0, u_cap)


def best_revenue_discount(u0, eta, connectors, power_kw, base_price,
                          tiers=None, u_cap=pc.U_CAP, hours=1.0):
    """Pure-revenue-optimal discount tier for a single cell (no distribution term).

    Returns (best_d, revenue_at_best, revenue_at_zero). Used for unit tests and
    as the greedy seed in the optimizer.
    """
    tiers = tiers if tiers is not None else pc.DISCOUNT_TIERS
    revs = [float(revenue(u0, d, eta, connectors, power_kw, base_price, hours, u_cap))
            for d in tiers]
    i = int(np.argmax(revs))
    r0 = revs[tiers.index(0.0)] if 0.0 in tiers else revs[0]
    return tiers[i], revs[i], r0
