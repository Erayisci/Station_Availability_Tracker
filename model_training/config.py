"""Single source of truth for the ML pipeline."""
import os
from pathlib import Path

ES_HOST = os.environ.get("ES_HOST", "http://localhost:9200")

SOURCE_INDEX_PATTERN = "zes_connector_transform-*"
OUTPUT_INDEX_PREFIX = "zes_station_features"

TRAINING_LOOKBACK_DAYS = 90

CHARGING_STATUS = "Charging"

MIN_OBSERVED_HOURS_FOR_FEATURES = 4
LOW_CONFIDENCE_THRESHOLD = 0.20

DAY_HOURS = range(6, 22)
NIGHT_HOURS = list(range(22, 24)) + list(range(0, 6))
WEEKEND_DOWS = {5, 6}
WEEKDAY_DOWS = {0, 1, 2, 3, 4}

K_RANGE = (3, 4)

CLUSTERING_VIABILITY = {
    "min_median_observed_hours": 100,
    "min_distinct_dows": 4,
}

ROOT = Path(__file__).resolve().parent
MODELS_DIR = ROOT / "models"
REPORTS_DIR = ROOT / "reports"
MODELS_DIR.mkdir(exist_ok=True)
REPORTS_DIR.mkdir(exist_ok=True)

FEATURE_VERSION = "1"
