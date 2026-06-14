import { useState, useEffect, useCallback } from 'react';
import {
  fetchZesConnectorTransformTimeSlot,
  fetchZesConnectorTransformTimeline,
  fetchZesByCityDuration,
  fetchZesEnvDistribution,
  fetchZesEnvTimeSlot,
  fetchConnectorTransformAvailableDates,
  fetchEpdkConnectorSnapshotStats,
} from '../api/zesApi';
import { ChartCard, SegmentedControl, Button, EmptyState } from '../components/ui';
import TimeSlotChart        from '../components/TimeSlotChart';
import TimelineChart        from '../components/TimelineChart';
import CityDurationChart    from '../components/CityDurationChart';
import EnvDistributionChart from '../components/EnvDistributionChart';
import EnvTimeSlotChart     from '../components/EnvTimeSlotChart';
import DateCalendar         from '../components/DateCalendar';

const INTERVAL_OPTIONS = [
  { value: 15,  label: '15 dk' },
  { value: 30,  label: '30 dk' },
  { value: 60,  label: '60 dk' },
];

const CONNECTOR_OPTIONS = [
  { value: '',               label: 'Tümü' },
  { value: 'DC_CCS',        label: 'DC CCS' },
  { value: 'DC_CHAdeMO',    label: 'DC CHAdeMO' },
  { value: 'AC_Type_2',     label: 'AC Type 2' },
  { value: 'AC_Type_2_Plug',label: 'AC Type 2 Plug' },
];

export default function ZesStatsPage({ date, onDateChange }) {
  const [dateConfirmed,       setDateConfirmed]       = useState(!!date);
  const [intervalMinutes,     setIntervalMinutes]     = useState(30);
  const [connectorTypeFilter, setConnectorTypeFilter] = useState('');
  const [chartIndex,          setChartIndex]          = useState(0);

  const [timeSlotData,     setTimeSlotData]     = useState(null);
  const [timelineData,     setTimelineData]     = useState(null);
  const [cityDurationData, setCityDurationData] = useState(null);
  const [envDistData,      setEnvDistData]      = useState(null);
  const [envTimeSlotData,  setEnvTimeSlotData]  = useState(null);
  const [epdkSnapshotData, setEpdkSnapshotData] = useState(null);
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState(null);

  const load = useCallback(async () => {
    if (!dateConfirmed) return;
    setLoading(true);
    setError(null);
    const results = await Promise.allSettled([
      fetchZesConnectorTransformTimeSlot({ date, intervalMinutes, connectorType: connectorTypeFilter || undefined }),
      fetchZesConnectorTransformTimeline({ date, connectorType: connectorTypeFilter || undefined }),
      fetchZesByCityDuration({ date }),
      fetchZesEnvDistribution(),
      fetchZesEnvTimeSlot({ date, intervalMinutes, connectorType: connectorTypeFilter || undefined }),
      fetchEpdkConnectorSnapshotStats({ date }),
    ]);
    const [ts, tl, city, envDist, envTs, epdk] = results.map(r => r.status === 'fulfilled' ? r.value : null);
    setTimeSlotData(ts);
    setTimelineData(tl);
    setCityDurationData(city);
    setEnvDistData(envDist);
    setEnvTimeSlotData(envTs);
    setEpdkSnapshotData(epdk && Object.keys(epdk).length > 0 ? epdk : null);
    if (results.slice(0, 5).every(r => r.status === 'rejected'))
      setError(`Bu tarih için veri bulunamadı (${date}).`);
    setLoading(false);
  }, [date, intervalMinutes, connectorTypeFilter, dateConfirmed]);

  useEffect(() => { load(); }, [load]);

  const withFallback = (data, node) =>
    data == null
      ? <EmptyState title="Veri yok" description={`${date} tarihine ait veri bulunamadı`} />
      : node;

  const charts = [
    {
      title: 'Konnektör Durumu — Zaman Dilimleri',
      subtitle: `${intervalMinutes} dk aralık`,
      node:  withFallback(timeSlotData, <TimeSlotChart data={timeSlotData} />),
    },
    {
      title: 'Konnektör Durumu — Sürekli Zaman Akışı',
      node:  withFallback(timelineData, <TimelineChart data={timelineData} />),
    },
    {
      title: 'İle Göre Toplam Şarj Süresi — Top 20',
      subtitle: 'Saat cinsinden',
      node:  withFallback(cityDurationData, <CityDurationChart data={cityDurationData} />),
    },
    {
      title: 'İstasyon Çevre Tipi Dağılımı',
      node:  withFallback(envDistData, <EnvDistributionChart data={envDistData} />),
    },
    {
      title: 'Çevre Tipine Göre Doluluk Oranı',
      subtitle: `${intervalMinutes} dk aralık — %`,
      node:  withFallback(envTimeSlotData, <EnvTimeSlotChart data={envTimeSlotData} percent={true} />),
    },
    {
      title: 'Çevre Tipine Göre Soket Sayısı — Mutlak',
      subtitle: `${intervalMinutes} dk aralık`,
      node:  withFallback(envTimeSlotData, <EnvTimeSlotChart data={envTimeSlotData} percent={false} />),
    },
    {
      title: 'EPDK Konnektör Durumu — Snapshot Zamanları',
      subtitle: 'epdk_zes_connectors — 5 dk aralık',
      node:  withFallback(epdkSnapshotData, <TimeSlotChart data={epdkSnapshotData} />),
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

  function handleDateSelect(d) {
    onDateChange(d);
    setDateConfirmed(true);
  }

  /* Controls for the chart header strip */
  const chartControls = (
    <>
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
        <span className="ctrl-label">Konnektör</span>
        <select
          value={connectorTypeFilter}
          onChange={e => setConnectorTypeFilter(e.target.value)}
          className="ctrl-select"
        >
          {CONNECTOR_OPTIONS.map(o => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>
      <div className="ctrl-spacer" />
      <Button
        onClick={load}
        disabled={loading}
        variant="secondary"
        icon="↻"
        size="sm"
      >
        {loading ? 'Yükleniyor…' : 'Yenile'}
      </Button>
    </>
  );

  return (
    <>
      <DateCalendar
        selectedDate={date}
        onSelect={handleDateSelect}
        fetchDates={fetchConnectorTransformAvailableDates}
        label="Analitik Tarihi Seç"
      />

      {!dateConfirmed ? (
        <EmptyState
          icon="📅"
          title="Tarih seçin"
          description="Yukarıdaki takvimden veri bulunan bir tarih seçin"
        />
      ) : (
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
      )}
    </>
  );
}
