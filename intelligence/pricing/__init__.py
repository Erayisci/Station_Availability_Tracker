"""Dynamic pricing phase — STATION-level, human-in-the-loop.

The pricing DECISION is not optimized server-side. The dashboard lets the user
inspect a station's history, set per-hour-band discounts, and simulate revenue
in the browser (console/src/lib/pricingModel.js — a JS port of
demand_model.py + elasticity.py). This package only produces the per-station
DATA the page reads, plus the economic assumptions it mirrors:

  1. observed hourly utilization   — hourly_data_builder.py
                                      → zes_station_hourly_performance-{date}
  2. hourly per-segment forecast   — profile.py + hourly_predict.py
                                      → zes_station_hourly_forecast-{date}
  3. demand/revenue + elasticity   — demand_model.py + elasticity.py
                                      (canonical spec; mirrored by Java
                                       /pricing/config and the JS port)

Design note: like opportunity.py and forecast/predict.py, every computed index
here is WRITTEN from Python; the Java side (ZesPricingService) is a pure reader
that also serves the economic assumptions via /pricing/config.

CRITICAL ASSUMPTION: there is no historical price variation in the data, so
price elasticity cannot be fitted. It is an exogenous, segment-specific
parameter (see elasticity.py). Every revenue figure is a SIMULATION and ships
with a low/central/high sensitivity band — never a single number.

Removed (2026-06-25): the city-level optimizer (optimize.py), city aggregation
(city_predict.py) and policy backtest (backtest.py). Decision moved to the user.
"""
