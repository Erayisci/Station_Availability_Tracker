const BASE = '/astor/availability';

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

export function fetchAstorTimeSlot({ date, intervalMinutes = 30, tenantCode, connectorCurrentType } = {}) {
  const q = toQuery({ date, intervalMinutes, tenantCode, connectorCurrentType });
  return fetchJson(`${BASE}/stats/by-time-slot${q ? '?' + q : ''}`);
}

export function fetchAstorByLocation({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/stats/by-location${q ? '?' + q : ''}`);
}

export function fetchAstorSearch({
  date, status, connectorCurrentType, tenantCode,
  locationCode, address, time, from = 0, size = 20,
} = {}) {
  const q = toQuery({ date, status, connectorCurrentType, tenantCode, locationCode, address, time, from, size });
  return fetchJson(`${BASE}/search${q ? '?' + q : ''}`);
}
