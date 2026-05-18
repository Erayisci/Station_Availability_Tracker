"""FastAPI service for the ZES station ML pipeline."""
from __future__ import annotations

import json
from datetime import date, datetime
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, Query

import config
from assign import assign_all
from audit import run_audit

app = FastAPI(title="ZES Station ML Pipeline", version=config.FEATURE_VERSION)


def _latest_model_path() -> Optional[str]:
    pointer = config.MODELS_DIR / "latest.json"
    if not pointer.exists():
        return None
    info = json.loads(pointer.read_text())
    p = config.MODELS_DIR / info["model_file"]
    return str(p) if p.exists() else None


@app.get("/health")
def health():
    model = _latest_model_path()
    info = {}
    if model:
        info = json.loads((config.MODELS_DIR / "latest.json").read_text())
    return {
        "status": "ok",
        "feature_version": config.FEATURE_VERSION,
        "latest_model": model,
        "model_info": info,
        "ts": datetime.utcnow().isoformat() + "Z",
    }


@app.post("/audit")
def audit(lookback_days: int = Query(config.TRAINING_LOOKBACK_DAYS, ge=1, le=365)):
    report_path = run_audit(lookback_days)
    return {"report": str(report_path), "report_dir": str(report_path.parent)}


@app.post("/assign")
def assign(
    out_date: Optional[str] = Query(None, description="YYYY-MM-DD, default today"),
    lookback_days: int = Query(config.TRAINING_LOOKBACK_DAYS, ge=1, le=365),
    use_model: bool = Query(True, description="Apply latest trained model if available"),
):
    parsed_date = date.fromisoformat(out_date) if out_date else None
    model_path = _latest_model_path() if use_model else None
    return assign_all(output_date=parsed_date, lookback_days=lookback_days, model_path=model_path)


@app.post("/train")
def train(lookback_days: int = Query(config.TRAINING_LOOKBACK_DAYS, ge=1, le=365)):
    # Imported lazily so app boots even without train.py finalized
    from train import train_and_save
    return train_and_save(lookback_days=lookback_days)


@app.post("/anomaly")
def anomaly(
    out_date: Optional[str] = Query(None, description="Anchor date YYYY-MM-DD"),
    baseline_days: int = Query(90, ge=7, le=365),
    today_window_days: int = Query(1, ge=1, le=14),
):
    from anomaly import detect_anomalies
    from datetime import date as _date
    parsed = _date.fromisoformat(out_date) if out_date else None
    return detect_anomalies(
        output_date=parsed,
        baseline_days=baseline_days,
        today_window_days=today_window_days,
    )


@app.post("/opportunity")
def opportunity(out_date: Optional[str] = Query(None, description="YYYY-MM-DD, default today")):
    from opportunity import score_opportunities
    from datetime import date as _date
    parsed = _date.fromisoformat(out_date) if out_date else None
    return score_opportunities(output_date=parsed)


@app.post("/forecast/train")
def forecast_train():
    """Train (or retrain) the 1-day utilization forecast model.

    Builds X/y from consecutive (t, t+1) perf-index pairs joined with the most
    recent features snapshot. Writes a new pkl + updates latest_forecast.json.
    """
    from forecast.train import train_and_save
    return train_and_save()


@app.post("/forecast/sanity-check")
def forecast_sanity_check():
    """Run multiple model families on the same split and compare to naive baseline.

    Diagnostic only — does not persist any model. Tests whether the data has
    signal that any model can capture above naive.
    """
    from forecast.sanity_check import run_comparison
    return run_comparison()


@app.post("/forecast/predict")
def forecast_predict(
    target_date: Optional[str] = Query(
        None,
        description="Day to forecast (YYYY-MM-DD). Uses latest perf snapshot < target_date as input. Default: latest perf + 1 day."
    ),
):
    """Run inference: write zes_station_forecast-{target_date}."""
    from forecast.predict import predict_for_date
    from datetime import date as _date
    parsed = _date.fromisoformat(target_date) if target_date else None
    return predict_for_date(target_date=parsed)


# --------------------------------------------------------------------------
# Dynamic pricing pipeline (see model_training/pricing/).
# --------------------------------------------------------------------------
@app.post("/pricing/build-hourly")
def pricing_build_hourly(date_str: Optional[str] = Query(None, alias="date", description="YYYY-MM-DD")):
    """Build observed zes_station_hourly_performance-{date} from connector_transform."""
    from pricing.hourly_data_builder import build_hourly_performance
    from datetime import date as _date
    return build_hourly_performance(_date.fromisoformat(date_str) if date_str else _date.today())


@app.post("/pricing/predict-hourly")
def pricing_predict_hourly(date_str: Optional[str] = Query(None, alias="date")):
    """Disaggregate the daily forecast into zes_station_hourly_forecast-{date}."""
    from pricing.hourly_predict import predict_hourly
    from datetime import date as _date
    return predict_hourly(_date.fromisoformat(date_str) if date_str else _date.today())


@app.post("/pricing/predict-city")
def pricing_predict_city(date_str: Optional[str] = Query(None, alias="date")):
    """Aggregate the hourly forecast into zes_city_forecast-{date}."""
    from pricing.city_predict import predict_city
    from datetime import date as _date
    return predict_city(_date.fromisoformat(date_str) if date_str else _date.today())


@app.post("/pricing/optimize")
def pricing_optimize(
    date_str: Optional[str] = Query(None, alias="date"),
    gamma: float = Query(None, description="Revenue↔evenness trade-off weight (default config GAMMA)"),
):
    """Optimize discounts → zes_station_pricing-{date}."""
    from pricing.optimize import optimize
    from pricing import pricing_config as pcfg
    from datetime import date as _date
    g = gamma if gamma is not None else pcfg.GAMMA
    return optimize(_date.fromisoformat(date_str) if date_str else _date.today(), gamma=g)


@app.post("/pricing/run-all")
def pricing_run_all(
    date_str: Optional[str] = Query(None, alias="date"),
    gamma: float = Query(None),
):
    """Convenience pipeline: build-hourly → predict-hourly → predict-city → optimize."""
    from datetime import date as _date
    from pricing.hourly_predict import predict_hourly
    from pricing.city_predict import predict_city
    from pricing.optimize import optimize
    from pricing import pricing_config as pcfg
    d = _date.fromisoformat(date_str) if date_str else _date.today()
    g = gamma if gamma is not None else pcfg.GAMMA
    steps = {
        "predict_hourly": predict_hourly(d),
        "predict_city": predict_city(d),
        "optimize": optimize(d, gamma=g),
    }
    return steps


@app.post("/pricing/backtest")
def pricing_backtest(
    gamma: float = Query(None),
    start_date: Optional[str] = Query(None),
    end_date: Optional[str] = Query(None),
):
    """Simulation backtest of the pricing policy vs the non-discounted baseline."""
    from pricing.backtest import backtest, write_report
    from pricing import pricing_config as pcfg
    from datetime import date as _date
    g = gamma if gamma is not None else pcfg.GAMMA
    s = _date.fromisoformat(start_date) if start_date else None
    e = _date.fromisoformat(end_date) if end_date else None
    res = backtest(start_date=s, end_date=e, gamma=g)
    if res.get("status") == "ok":
        res["report"] = write_report(res)
    return res
