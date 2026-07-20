#!/usr/bin/env bash
# Daily ML refresh — keeps pricing/forecast indices current.
#
# The Java app runs its own daily crons inside the container (23:59 finalize,
# 00:15 performance build). This script triggers the Python ML pipeline AFTER
# that, so pricing/forecast outputs stay fresh. Install as a host cron:
#
#   30 0 * * *  /path/to/repo/deploy/daily-pipeline.sh >> /var/log/zes-pipeline.log 2>&1
#
# Runs on the VM, hitting the locally-published nginx (port 80) which strips the
# /ml/ prefix and proxies to the Python service.
set -euo pipefail

BASE="${ML_BASE_URL:-http://localhost/ml}"
DATE="$(date +%F)"

echo "[$(date -Is)] daily-pipeline start (date=$DATE, base=$BASE)"

# Forecast first — pricing/predict-hourly disaggregates the daily forecast.
echo "--> forecast/predict"
curl -fsS -X POST "$BASE/forecast/predict" && echo

echo "--> pricing/run-all"
curl -fsS -X POST "$BASE/pricing/run-all?date=$DATE" && echo

echo "[$(date -Is)] daily-pipeline done"
