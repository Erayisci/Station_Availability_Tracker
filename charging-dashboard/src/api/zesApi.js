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

export function fetchZesTimeSlot({ date, intervalMinutes = 30, province, connectorType } = {}) {
  const q = toQuery({ date, intervalMinutes, province, connectorType });
  return fetchJson(`${BASE}/stats/by-time-slot${q ? '?' + q : ''}`);
}

export function fetchZesConnectorTransformTimeSlot({ date, intervalMinutes = 30, province, connectorType } = {}) {
  const q = toQuery({ date, intervalMinutes, province, connectorType });
  return fetchJson(`${BASE}/connector-transform/stats/by-time-slot${q ? '?' + q : ''}`);
}

export function fetchZesConnectorTransformTimeline({ date, province, connectorType } = {}) {
  const q = toQuery({ date, province, connectorType });
  return fetchJson(`${BASE}/connector-transform/stats/timeline${q ? '?' + q : ''}`);
}

export function fetchZesByConnectorType({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/stats/by-connector-type${q ? '?' + q : ''}`);
}

export function fetchZesByProvince({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/stats/by-province${q ? '?' + q : ''}`);
}

export function fetchZesByCityDuration({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/connector-transform/stats/by-city${q ? '?' + q : ''}`);
}

export function fetchZesSearch({ date, province, connectorType, status, from = 0, size = 20, sort = 'name', order = 'asc' } = {}) {
  const q = toQuery({ date, province, connectorType, status, from, size, sort, order });
  return fetchJson(`${BASE}/search${q ? '?' + q : ''}`);
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

export function fetchStationPerformanceSearch({
  date, demandTier, supplyTier, recommendationCode, cityName, stationName,
  minUtilizationPct, maxUtilizationPct, sort, order = 'desc', from = 0, size = 20,
} = {}) {
  const q = toQuery({ date, demandTier, supplyTier, recommendationCode, cityName, stationName,
    minUtilizationPct, maxUtilizationPct, sort, order, from, size });
  return fetchJson(`${BASE}/station-performance/search${q ? '?' + q : ''}`);
}
