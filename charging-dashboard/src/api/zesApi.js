const BASE = '/zes/availability';

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

// Removed: fetchZesTimeSlot (duplicate - use fetchZesConnectorTransformTimeSlot instead)

export function fetchZesConnectorTransformTimeSlot({ date, intervalMinutes = 30, province, connectorType } = {}) {
  const q = toQuery({ date, intervalMinutes, province, connectorType });
  return fetchJson(`${BASE}/connector-transform/stats/by-time-slot${q ? '?' + q : ''}`);
}

export function fetchZesConnectorTransformTimeline({ date, province, connectorType } = {}) {
  const q = toQuery({ date, province, connectorType });
  return fetchJson(`${BASE}/connector-transform/stats/timeline${q ? '?' + q : ''}`);
}

// Removed: fetchZesByConnectorType (unused by UI)
// Removed: fetchZesByProvince (unused by UI)

export function fetchZesByCityDuration({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/connector-transform/stats/by-city${q ? '?' + q : ''}`);
}

// Removed: fetchZesSearch (unused by UI - use fetchZesConnectorSearch instead)

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

export function fetchStationPerformanceSearch({
  date, demandTier, supplyTier, recommendationCode, cityName, stationName,
  minUtilizationPct, maxUtilizationPct, sort, order = 'desc', from = 0, size = 20,
} = {}) {
  const q = toQuery({ date, demandTier, supplyTier, recommendationCode, cityName, stationName,
    minUtilizationPct, maxUtilizationPct, sort, order, from, size });
  return fetchJson(`${BASE}/station-performance/search${q ? '?' + q : ''}`);
}
