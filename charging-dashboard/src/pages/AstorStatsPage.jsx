import { useState, useEffect, useCallback } from 'react';
import { fetchAstorTimeSlot, fetchAstorByLocation } from '../api/astorApi';
import TimeSlotChart     from '../components/TimeSlotChart';
import CityDurationChart from '../components/CityDurationChart';

const ASTOR_COLORS = {
  available:   '#4ade80',  // yeşil
  inUse:       '#f97316',  // turuncu
  offline:     '#64748b',  // koyu gri
  faulted:     '#ef4444',  // kırmızı
};

export default function AstorStatsPage({ date, onDateChange }) {
  const [intervalMinutes,      setIntervalMinutes]      = useState(30);
  const [tenantFilter,         setTenantFilter]         = useState('');
  const [connectorTypeFilter,  setConnectorTypeFilter]  = useState('');
  const [chartIndex,           setChartIndex]           = useState(0);

  const [timeSlotData,   setTimeSlotData]   = useState(null);
  const [locationData,   setLocationData]   = useState(null);
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ts, loc] = await Promise.all([
        fetchAstorTimeSlot({
          date,
          intervalMinutes,
          tenantCode:           tenantFilter          || undefined,
          connectorCurrentType: connectorTypeFilter   || undefined,
        }),
        fetchAstorByLocation({ date }),
      ]);
      setTimeSlotData(ts);
      setLocationData(loc);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, intervalMinutes, tenantFilter, connectorTypeFilter]);

  useEffect(() => { load(); }, [load]);

  const charts = [
    {
      title: `Zaman Dilimlerine Göre İstasyon Durumu (${intervalMinutes} dk aralık)`,
      node:  <TimeSlotChart data={timeSlotData} colors={ASTOR_COLORS} />,
    },
    {
      title: 'İl Koduna Göre Toplam Süre Dağılımı (saat) — Top 20',
      node:  <CityDurationChart data={locationData} />,
    },
  ];

  const total = charts.length;
  const prev  = () => setChartIndex(i => (i - 1 + total) % total);
  const next  = () => setChartIndex(i => (i + 1) % total);

  useEffect(() => {
    function onKey(e) {
      if (e.key === 'ArrowLeft')  prev();
      if (e.key === 'ArrowRight') next();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const current = charts[chartIndex];

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

          <label>Marka</label>
          <select value={tenantFilter} onChange={e => setTenantFilter(e.target.value)}>
            <option value="">Tümü</option>
            <option value="astor">ASTOR</option>
            <option value="beefull">Beefull</option>
          </select>

          <label>Şarj tipi</label>
          <select value={connectorTypeFilter} onChange={e => setConnectorTypeFilter(e.target.value)}>
            <option value="">Tümü</option>
            <option value="ac">AC</option>
            <option value="dc">DC</option>
          </select>

          <button onClick={load} disabled={loading}>{loading ? 'Yükleniyor…' : 'Yenile'}</button>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="card" style={{ position: 'relative', padding: '24px 48px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <button onClick={prev} style={arrowStyle}>&#8592;</button>
          <h2 style={{ margin: 0, color: '#e2e8f0', fontSize: 15, textAlign: 'center', flex: 1 }}>
            {current.title}
          </h2>
          <button onClick={next} style={arrowStyle}>&#8594;</button>
        </div>

        <div style={{ minHeight: 360 }}>
          {loading ? <div className="loading">Yükleniyor…</div> : current.node}
        </div>

        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
          {charts.map((_, i) => (
            <button
              key={i}
              onClick={() => setChartIndex(i)}
              style={{
                width: 8, height: 8,
                borderRadius: '50%',
                border: 'none',
                background: i === chartIndex ? '#f97316' : '#334155',
                cursor: 'pointer',
                padding: 0,
                transition: 'background 0.2s',
              }}
            />
          ))}
        </div>
      </div>
    </>
  );
}

const arrowStyle = {
  background: 'transparent',
  border: '1px solid #334155',
  borderRadius: 8,
  color: '#94a3b8',
  fontSize: 18,
  width: 36,
  height: 36,
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
};
