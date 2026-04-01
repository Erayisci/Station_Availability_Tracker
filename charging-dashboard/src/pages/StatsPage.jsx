import React, { useState, useEffect, useCallback } from 'react';
import { fetchTimeSlot, fetchByDistrict, fetchByBrand, fetchBySocketType } from '../api/availabilityApi';
import TimeSlotChart from '../components/TimeSlotChart';
import DistrictChart from '../components/DistrictChart';
import BrandChart from '../components/BrandChart';
import SocketTypeChart from '../components/SocketTypeChart';

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

export default function StatsPage({ date, onDateChange }) {
  const [intervalMinutes, setIntervalMinutes] = useState(30);
  const [socketTypeFilter, setSocketTypeFilter] = useState('');

  const [timeSlotData, setTimeSlotData]     = useState(null);
  const [districtData, setDistrictData]     = useState(null);
  const [brandData, setBrandData]           = useState(null);
  const [socketTypeData, setSocketTypeData] = useState(null);
  const [loading, setLoading]               = useState(false);
  const [error, setError]                   = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ts, dist, brand, sock] = await Promise.all([
        fetchTimeSlot({ date, intervalMinutes, socketType: socketTypeFilter || undefined }),
        fetchByDistrict({ date }),
        fetchByBrand({ date }),
        fetchBySocketType({ date }),
      ]);
      setTimeSlotData(ts);
      setDistrictData(dist);
      setBrandData(brand);
      setSocketTypeData(sock);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, intervalMinutes, socketTypeFilter]);

  useEffect(() => { load(); }, [load]);

  const totals = sumCounts(districtData);
  const grandTotal = Object.values(totals).reduce((a, b) => a + b, 0);
  const availableKey = Object.keys(totals).find(k => k === 'AVAILABLE' || k === 'FREE') ?? 'AVAILABLE';
  const occupiedKey  = Object.keys(totals).find(k => k === 'IN_USE'    || k === 'OCCUPIED') ?? 'IN_USE';

  return (
    <>
      <div className="page-controls">
        <div className="controls">
          <label>Tarih</label>
          <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />
          <label>Zaman aralığı</label>
          <select value={intervalMinutes} onChange={e => setIntervalMinutes(Number(e.target.value))}>
            <option value={15}>15 dk</option>
            <option value={30}>30 dk</option>
            <option value={60}>60 dk</option>
          </select>
          <label>Konnektör</label>
          <select value={socketTypeFilter} onChange={e => setSocketTypeFilter(e.target.value)}>
            <option value="">Tümü</option>
            <option value="CCS">CCS</option>
            <option value="CHAdeMO">CHAdeMO</option>
            <option value="AC">AC</option>
            <option value="Type2">Type2</option>
          </select>
          <button onClick={load} disabled={loading}>{loading ? 'Yükleniyor…' : 'Yenile'}</button>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="summary-bar">
        <div className="stat-card total">
          <div className="label">Toplam Kayıt</div>
          <div className="value">{grandTotal.toLocaleString()}</div>
        </div>
        <div className="stat-card available">
          <div className="label">{availableKey}</div>
          <div className="value">{(totals[availableKey] ?? 0).toLocaleString()}</div>
        </div>
        <div className="stat-card occupied">
          <div className="label">{occupiedKey}</div>
          <div className="value">{(totals[occupiedKey] ?? 0).toLocaleString()}</div>
        </div>
        {totals['RESERVED'] != null && (
          <div className="stat-card reserved">
            <div className="label">RESERVED</div>
            <div className="value">{totals['RESERVED'].toLocaleString()}</div>
          </div>
        )}
      </div>

      <div className="grid">
        <div className="card grid-full">
          <h2>Zaman Dilimlerine Göre Durum ({intervalMinutes} dk aralık)</h2>
          {loading ? <div className="loading">Yükleniyor…</div> : <TimeSlotChart data={timeSlotData} />}
        </div>

        <div className="card grid-full">
          <h2>İlçelere Göre Soket Durumu (Top 20)</h2>
          {loading ? <div className="loading">Yükleniyor…</div> : <DistrictChart data={districtData} />}
        </div>

        <div className="card">
          <h2>Markalara Göre Dağılım</h2>
          {loading ? <div className="loading">Yükleniyor…</div> : <BrandChart data={brandData} />}
        </div>

        <div className="card">
          <h2>Konnektör Tipine Göre Dağılım</h2>
          {loading ? <div className="loading">Yükleniyor…</div> : <SocketTypeChart data={socketTypeData} />}
        </div>
      </div>
    </>
  );
}
