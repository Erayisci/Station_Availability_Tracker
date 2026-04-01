const BASE = '/sarjet/availability';

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

export function fetchTimeSlot({ date, intervalMinutes = 30, district, socketType } = {}) {
  const q = toQuery({ date, intervalMinutes, district, socketType });
  return fetchJson(`${BASE}/stats/by-time-slot${q ? '?' + q : ''}`);
}

export function fetchByDistrict({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/stats/by-district${q ? '?' + q : ''}`);
}

export function fetchByCity({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/stats/by-city${q ? '?' + q : ''}`);
}

export function fetchByBrand({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/stats/by-brand${q ? '?' + q : ''}`);
}

export function fetchBySocketType({ date } = {}) {
  const q = toQuery({ date });
  return fetchJson(`${BASE}/stats/by-socket-type${q ? '?' + q : ''}`);
}

export function fetchSearch({ date, district, city, brand, socketType, status, from = 0, size = 20, sort = 'id', order = 'asc' } = {}) {
  const q = toQuery({ date, district, city, brand, socketType, status, from, size, sort, order });
  return fetchJson(`${BASE}/search${q ? '?' + q : ''}`);
}
