"""Sanity tests for the demand-response math. Run: python -m pricing.test_demand_model"""
from __future__ import annotations

import numpy as np

from . import demand_model as dm
from . import elasticity as el
from . import pricing_config as pc


def _check(name, cond):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
    assert cond, name


def test_demand_increases_with_discount():
    print("demand rises with discount (elastic):")
    eta = 0.9
    u = [float(dm.u_served(20.0, d, eta)) for d in pc.DISCOUNT_TIERS]
    _check("monotone non-decreasing in d", all(b >= a - 1e-9 for a, b in zip(u, u[1:])))
    _check("d=0 served < d=max served", u[0] < u[-1])


def test_baseline_identity():
    print("u_served(d=0) == observed u0 (anchoring):")
    for u0 in (5.0, 20.0, 50.0, 90.0):
        _check(f"u0={u0}", abs(float(dm.u_served(u0, 0.0, 0.9)) - u0) < 1e-6)


def test_saturation_near_capacity():
    print("near-capacity cell barely responds (saturation):")
    eta = 0.9
    low = dm.u_served(20.0, 0.20, eta) - dm.u_served(20.0, 0.0, eta)
    high = dm.u_served(90.0, 0.20, eta) - dm.u_served(90.0, 0.0, eta)
    _check("idle cell gains more served util than busy cell", low > high)
    _check("served never exceeds U_cap", float(dm.u_served(90.0, 0.20, 1.5)) <= pc.U_CAP + 1e-6)


def test_interior_optimum_exists():
    print("interior revenue optimum for a mid-utilization elastic cell:")
    # An idle, elastic cell should prefer a positive discount; a busy cell d=0.
    d_idle, _, _ = dm.best_revenue_discount(15.0, 1.1, connectors=4, power_kw=120, base_price=9.5)
    d_busy, _, _ = dm.best_revenue_discount(88.0, 1.1, connectors=4, power_kw=120, base_price=9.5)
    _check("idle elastic cell wants a discount", d_idle > 0.0)
    _check("busy cell wants no discount", d_busy == 0.0)


def test_inelastic_no_discount():
    print("inelastic cell never discounts (revenue only):")
    d, _, _ = dm.best_revenue_discount(30.0, 0.3, connectors=2, power_kw=22, base_price=7.5)
    _check("inelastic ⇒ d=0", d == 0.0)


def test_epdk_bound():
    print("EPDK: discounts only, bounded:")
    _check("all tiers in [0, D_MAX]", all(0.0 <= d <= pc.D_MAX for d in pc.DISCOUNT_TIERS))


def test_elasticity_scenarios_ordered():
    print("elasticity scenarios ordered low<central<high per segment:")
    for seg in ("AC", "DC"):
        lo = el.effective_eta(seg, 3, scenario="low")
        ce = el.effective_eta(seg, 3, scenario="central")
        hi = el.effective_eta(seg, 3, scenario="high")
        _check(f"{seg}: low<central<high", lo < ce < hi)
    print("peak less elastic than off-peak:")
    peak = el.effective_eta("DC", 19, peak_hour=19)
    off = el.effective_eta("DC", 4, peak_hour=19)
    _check("peak η < off-peak η", peak < off)


def test_vectorized():
    print("vectorized over arrays:")
    u0 = np.array([10.0, 50.0, 90.0])
    served = dm.u_served(u0, 0.10, np.array([0.5, 0.9, 1.2]))
    _check("returns array of same shape", served.shape == u0.shape)
    _check("all <= U_cap", bool(np.all(served <= pc.U_CAP + 1e-6)))


if __name__ == "__main__":
    for fn in [
        test_demand_increases_with_discount,
        test_baseline_identity,
        test_saturation_near_capacity,
        test_interior_optimum_exists,
        test_inelastic_no_discount,
        test_epdk_bound,
        test_elasticity_scenarios_ordered,
        test_vectorized,
    ]:
        fn()
    print("\nAll demand-model tests passed.")
