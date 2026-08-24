const BASE = '/zes/availability';
const ADMIN_BASE = '/zes/admin';

function toQuery(params) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') q.set(k, v);
  }
  return q.toString();
}

async function fetchJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`);
  return res.json();
}

export function fetchPerfIndicesHealth() {
  return fetchJson(`${ADMIN_BASE}/perf-indices/health`);
}

export async function deletePerfIndex(date) {
  const res = await fetch(
    `${ADMIN_BASE}/perf-indices/${date}?confirm=true`,
    { method: 'DELETE' }
  );
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

// ── Backtest (forecast vs realised performance) ──
const BACKTEST_BASE = '/zes/backtest';

export function fetchBacktestDates() {
  return fetchJson(`${BACKTEST_BASE}/dates`);
}
export function fetchBacktestSummary(date) {
  return fetchJson(`${BACKTEST_BASE}/${date}/summary`);
}
export function fetchBacktestStations(date) {
  return fetchJson(`${BACKTEST_BASE}/${date}/stations`);
}
export function fetchBacktestTrend() {
  return fetchJson(`${BACKTEST_BASE}/trend`);
}

// Removed: fetchZesTimeSlot (duplicate - use fetchZesConnectorTransformTimeSlot instead)

export function fetchZesConnectorTransformTimeSlot({ date, intervalMinutes = 30, province, connectorType } = {}) {
  const q = toQuery({ date, intervalMinutes, province, connectorType });
  return fetchJson(`${BASE}/connector-transform/stats/by-time-slot${q ? '?' + q : ''}`);
}

export function fetchZesConnectorTransformTimeline({ date, province, connectorType } = {}) {
  const q = toQuery({ date, province, connectorType });
  return fetchJson(`${BASE}/connector-transform/stats/timeline${q ? '?' + q : ''}`);
}


export function fetchZesByCityDuration({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/connector-transform/stats/by-city${q ? '?' + q : ''}`);
}


export function fetchZesConnectorSearch({ date, province, connectorType, status, time, address, envType, from = 0, size = 20 } = {}) {
  const q = toQuery({ date, province, connectorType, status, time, address, envType, from, size });
  return fetchJson(`${BASE}/connector-transform/search${q ? '?' + q : ''}`);
}

export function fetchZesEnvDistribution() {
  return fetchJson(`${BASE}/stats/env-distribution`);
}

export function fetchZesEnvTimeSlot({ date, intervalMinutes = 30, province, connectorType } = {}) {
  const q = toQuery({ date, intervalMinutes, province, connectorType });
  return fetchJson(`${BASE}/connector-transform/stats/by-env-timeslot${q ? '?' + q : ''}`);
}

export function fetchStationPerformanceRecommendationDistribution({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/station-performance/stats/recommendation-distribution${q ? '?' + q : ''}`);
}

export function fetchStationPerformanceDemandSupplyMatrix({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/station-performance/stats/demand-supply-matrix${q ? '?' + q : ''}`);
}

export function fetchStationPerformanceUnderutilized({ date, size = 20 } = {}) {
  const q = toQuery({ date, size });
  return fetchJson(`${BASE}/station-performance/underutilized${q ? '?' + q : ''}`);
}

export function fetchStationPerformanceOverutilized({ date, size = 20 } = {}) {
  const q = toQuery({ date, size });
  return fetchJson(`${BASE}/station-performance/overutilized${q ? '?' + q : ''}`);
}

export function fetchConnectorTransformAvailableDates() {
  return fetchJson(`${BASE}/connector-transform/available-dates`);
}

export function fetchStationPerformanceAvailableDates() {
  return fetchJson(`${BASE}/station-performance/available-dates`);
}

export function fetchCitySummary({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/city/summary${q ? '?' + q : ''}`);
}

export function fetchCityDetail({ date, city } = {}) {
  const q = toQuery({ date, city });
  return fetchJson(`${BASE}/city/detail${q ? '?' + q : ''}`);
}

// ── Opportunity (zes_station_opportunity-{date}) ───────────────────────────

export function fetchOpportunitySummary({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/opportunity/summary${q ? '?' + q : ''}`);
}

export function fetchOpportunityTop({ date, size = 20, city, growth, order = 'desc' } = {}) {
  const q = toQuery({ date, size, city, growth, order });
  return fetchJson(`${BASE}/opportunity/top${q ? '?' + q : ''}`);
}

export function fetchOpportunityStation({ date, stationId } = {}) {
  const q = toQuery({ date, stationId });
  return fetchJson(`${BASE}/opportunity/station${q ? '?' + q : ''}`);
}

export function fetchOpportunityScatter({ date, size = 2000 } = {}) {
  const q = toQuery({ date, size });
  return fetchJson(`${BASE}/opportunity/scatter${q ? '?' + q : ''}`);
}

export function fetchOpportunityAvailableDates() {
  return fetchJson(`${BASE}/opportunity/available-dates`);
}

// ── Forecast (zes_station_forecast-{date}) ─────────────────────────────────

export function fetchForecastSummary({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/forecast/summary${q ? '?' + q : ''}`);
}

export function fetchForecastTop({ date, from = 0, size = 20, direction = 'up', city, stationName, sortBy = 'delta' } = {}) {
  const q = toQuery({ date, from, size, direction, city, stationName, sortBy });
  return fetchJson(`${BASE}/forecast/top${q ? '?' + q : ''}`);
}

export function fetchForecastScatter({ date, size = 2000 } = {}) {
  const q = toQuery({ date, size });
  return fetchJson(`${BASE}/forecast/scatter${q ? '?' + q : ''}`);
}

export function fetchForecastStation({ date, stationId } = {}) {
  const q = toQuery({ date, stationId });
  return fetchJson(`${BASE}/forecast/station${q ? '?' + q : ''}`);
}

export function fetchForecastAvailableDates() {
  return fetchJson(`${BASE}/forecast/available-dates`);
}

// ── İstasyon-bazlı dinamik fiyatlandırma ───────────────────────────────────

export function fetchPricingConfig() {
  return fetchJson(`${BASE}/pricing/config`);
}

export function fetchPricingStationForecast({ date, stationId } = {}) {
  const q = toQuery({ date, stationId });
  return fetchJson(`${BASE}/pricing/station-forecast${q ? '?' + q : ''}`);
}

export function fetchPricingStationHistory({ stationId, days = 14 } = {}) {
  const q = toQuery({ stationId, days });
  return fetchJson(`${BASE}/pricing/station-history${q ? '?' + q : ''}`);
}

export function fetchPricingAvailableDates() {
  return fetchJson(`${BASE}/pricing/available-dates`);
}

export function fetchStationPerformanceSearch({
  date, demandTier, supplyTier, recommendationCode, cityName, stationName,
  minUtilizationPct, maxUtilizationPct, sort, order = 'desc', from = 0, size = 20,
} = {}) {
  const q = toQuery({ date, demandTier, supplyTier, recommendationCode, cityName, stationName,
    minUtilizationPct, maxUtilizationPct, sort, order, from, size });
  return fetchJson(`${BASE}/station-performance/search${q ? '?' + q : ''}`);
}
