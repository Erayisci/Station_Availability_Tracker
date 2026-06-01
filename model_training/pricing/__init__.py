"""Dynamic pricing phase.

Builds on the daily utilization forecast to:
  1. disaggregate predictions to hourly + city granularity (profile.py,
     hourly_data_builder.py, hourly_predict.py, city_predict.py),
  2. model demand response to discounts under EPDK "discount-only" rules
     (elasticity.py, demand_model.py),
  3. optimize discounts for revenue + even utilization (optimize.py),
  4. backtest the policy against the non-discounted baseline (backtest.py).

Design note: like opportunity.py and forecast/predict.py, every computed
index in this package is WRITTEN from Python. The Java side (ZesPricingService)
is a pure reader. The observed hourly index is built here too, reusing the
slot-splitting convention from extract.py / ZesConnectorTransformAnalysisService.

CRITICAL ASSUMPTION: there is no historical price variation in the data, so
price elasticity cannot be fitted. It is an exogenous, segment-specific
parameter (see elasticity.py). Every pricing output ships with a low/central/
high sensitivity band — never a single number.
"""
