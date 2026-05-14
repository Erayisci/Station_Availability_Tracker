import { useState, useEffect, useCallback } from 'react';
import { fetchAstorTimeSlot, fetchAstorByLocation } from '../api/astorApi';
import { ChartCard, SegmentedControl, Button, EmptyState } from '../components/ui';
import TimeSlotChart     from '../components/TimeSlotChart';
import CityDurationChart from '../components/CityDurationChart';

const INTERVAL_OPTIONS = [
  { value: 15, label: '15 dk' },
  { value: 30, label: '30 dk' },
  { value: 60, label: '60 dk' },
];

export default function AstorStatsPage({ date, onDateChange }) {
  const [intervalMinutes,     setIntervalMinutes]     = useState(30);
  const [tenantFilter,        setTenantFilter]        = useState('');
  const [connectorTypeFilter, setConnectorTypeFilter] = useState('');
  const [chartIndex,          setChartIndex]          = useState(0);

  const [timeSlotData, setTimeSlotData] = useState(null);
  const [locationData, setLocationData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const results = await Promise.allSettled([
      fetchAstorTimeSlot({
        date, intervalMinutes,
        tenantCode:           tenantFilter        || undefined,
        connectorCurrentType: connectorTypeFilter || undefined,
      }),
      fetchAstorByLocation({ date }),
    ]);
    const [ts, loc] = results.map(r => r.status === 'fulfilled' ? r.value : null);
    setTimeSlotData(ts);
    setLocationData(loc);
    if (results.every(r => r.status === 'rejected'))
      setError(`Bu tarih için veri bulunamadı (${date}).`);
    setLoading(false);
  }, [date, intervalMinutes, tenantFilter, connectorTypeFilter]);

  useEffect(() => { load(); }, [load]);

  const withFallback = (data, node) =>
    data == null
      ? <EmptyState title="Veri yok" description={`${date} tarihine ait veri bulunamadı`} />
      : node;

  const charts = [
    {
      title:    'İstasyon Durumu — Zaman Dilimleri',
      subtitle: `${intervalMinutes} dk aralık`,
      node:     withFallback(timeSlotData, <TimeSlotChart data={timeSlotData} />),
    },
    {
      title:    'İl Koduna Göre Toplam Şarj Süresi — Top 20',
      subtitle: 'Saat cinsinden',
      node:     withFallback(locationData, <CityDurationChart data={locationData} />),
    },
  ];

  const total = charts.length;
  const prev  = () => setChartIndex(i => (i - 1 + total) % total);
  const next  = () => setChartIndex(i => (i + 1) % total);

  useEffect(() => {
    const onKey = e => {
      if (e.key === 'ArrowLeft')  prev();
      if (e.key === 'ArrowRight') next();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const current = charts[chartIndex];

  const chartControls = (
    <>
      <div className="ctrl-field">
        <span className="ctrl-label">Tarih</span>
        <input
          type="date"
          value={date}
          onChange={e => onDateChange(e.target.value)}
          className="ctrl-input"
        />
      </div>
      <div className="ctrl-divider" />
      <div className="ctrl-field">
        <span className="ctrl-label">Aralık</span>
        <SegmentedControl
          options={INTERVAL_OPTIONS}
          value={intervalMinutes}
          onChange={setIntervalMinutes}
        />
      </div>
      <div className="ctrl-divider" />
      <div className="ctrl-field">
        <span className="ctrl-label">Marka</span>
        <select value={tenantFilter} onChange={e => setTenantFilter(e.target.value)} className="ctrl-select">
          <option value="">Tümü</option>
          <option value="astor">ASTOR</option>
          <option value="beefull">Beefull</option>
        </select>
      </div>
      <div className="ctrl-divider" />
      <div className="ctrl-field">
        <span className="ctrl-label">Şarj Tipi</span>
        <select value={connectorTypeFilter} onChange={e => setConnectorTypeFilter(e.target.value)} className="ctrl-select">
          <option value="">Tümü</option>
          <option value="ac">AC</option>
          <option value="dc">DC</option>
        </select>
      </div>
      <div className="ctrl-spacer" />
      <Button onClick={load} disabled={loading} variant="secondary" icon="↻" size="sm">
        {loading ? 'Yükleniyor…' : 'Yenile'}
      </Button>
    </>
  );

  return (
    <>
      {error && <div className="error">{error}</div>}

      <ChartCard
        title={current.title}
        subtitle={current.subtitle}
        counter={chartIndex}
        total={total}
        onPrev={prev}
        onNext={next}
        controls={chartControls}
        dots={total}
        activeIndex={chartIndex}
        onDotClick={setChartIndex}
      >
        {loading
          ? <div className="loading">Yükleniyor…</div>
          : current.node}
      </ChartCard>
    </>
  );
}
