import React, { useState, useEffect, useCallback } from 'react';
import { fetchByCity } from '../api/availabilityApi';
import CityChart from '../components/CityChart';

function sumCounts(data) {
  const totals = {};
  if (!data) return totals;
  for (const counts of Object.values(data)) {
    for (const [status, val] of Object.entries(counts)) {
      totals[status] = (totals[status] ?? 0) + val;
    }
  }
  return totals;
}

function CityTable({ data }) {
  if (!data) return null;
  const rows = Object.entries(data)
    .map(([city, counts]) => ({
      city,
      ...counts,
      total: Object.values(counts).reduce((a, b) => a + b, 0),
    }))
    .sort((a, b) => b.total - a.total);

  const statuses = Array.from(
    new Set(rows.flatMap(r => Object.keys(r).filter(k => k !== 'city' && k !== 'total')))
  );

  return (
    <div className="table-wrapper">
      <table className="data-table">
        <thead>
          <tr>
            <th>Şehir</th>
            {statuses.map(s => <th key={s}>{s}</th>)}
            <th>Toplam</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr key={row.city}>
              <td>{row.city}</td>
              {statuses.map(s => <td key={s}>{row[s] ?? 0}</td>)}
              <td><strong>{row.total}</strong></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function CityPage({ date, onDateChange }) {
  const [data, setData]       = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchByCity({ date }));
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => { load(); }, [load]);

  const totals   = sumCounts(data);
  const grandTotal = Object.values(totals).reduce((a, b) => a + b, 0);

  return (
    <>
      <div className="page-controls">
        <div className="controls">
          <label>Tarih</label>
          <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />
          <button onClick={load} disabled={loading}>{loading ? 'Yükleniyor…' : 'Yenile'}</button>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="summary-bar">
        <div className="stat-card total">
          <div className="label">Toplam Kayıt</div>
          <div className="value">{grandTotal.toLocaleString()}</div>
        </div>
        {Object.entries(totals).map(([status, val]) => {
          const cls = status === 'AVAILABLE' || status === 'FREE'     ? 'available'
                    : status === 'IN_USE'    || status === 'OCCUPIED' ? 'occupied'
                    : status === 'RESERVED'                           ? 'reserved'
                    : 'total';
          return (
            <div key={status} className={`stat-card ${cls}`}>
              <div className="label">{status}</div>
              <div className="value">{val.toLocaleString()}</div>
            </div>
          );
        })}
      </div>

      <div className="grid">
        <div className="card grid-full">
          <h2>Şehirlere Göre Soket Durumu (Top 25)</h2>
          {loading ? <div className="loading">Yükleniyor…</div> : <CityChart data={data} />}
        </div>

        <div className="card grid-full">
          <h2>Şehir Detay Tablosu</h2>
          {loading ? <div className="loading">Yükleniyor…</div> : <CityTable data={data} />}
        </div>
      </div>
    </>
  );
}
