import { useState, useEffect, useCallback } from 'react';
import {
  fetchCitySummary,
  fetchCityDetail,
  fetchStationPerformanceAvailableDates,
} from '../../api/zesApi';
import { ChartCard, SegmentedControl, Button, EmptyState } from '../../components/ui';
import TurkeyMap, { TURKEY_MAP_METRICS } from '../../components/TurkeyMap';
import CityDetailPanel from '../../components/CityDetailPanel';
import DateCalendar from '../../components/DateCalendar';

const METRIC_OPTIONS = TURKEY_MAP_METRICS.map(m => ({ value: m.value, label: m.label }));

export default function ZesCityPage({ date, onDateChange }) {
  const [dateConfirmed, setDateConfirmed] = useState(!!date);
  const [metric,        setMetric]        = useState('avg_demand_supply_ratio');
  const [selectedCity,  setSelectedCity]  = useState(null);

  const [summary,       setSummary]       = useState(null);
  const [detail,        setDetail]        = useState(null);
  const [summaryLoad,   setSummaryLoad]   = useState(false);
  const [detailLoad,    setDetailLoad]    = useState(false);
  const [error,         setError]         = useState(null);

  /* ── Summary yükle (tarih değişince) ────────────────────────────── */
  const loadSummary = useCallback(async () => {
    if (!dateConfirmed) return;
    setSummaryLoad(true);
    setError(null);
    try {
      const data = await fetchCitySummary({ date });
      setSummary(data);
      if (!data || Object.keys(data).length === 0) {
        setError(`Bu tarih için şehir verisi bulunamadı (${date}).`);
      }
    } catch (e) {
      setError(`Yükleme hatası: ${e.message}`);
      setSummary(null);
    }
    setSummaryLoad(false);
  }, [date, dateConfirmed]);

  useEffect(() => { loadSummary(); }, [loadSummary]);

  /* ── Detail yükle (şehir seçilince) ─────────────────────────────── */
  useEffect(() => {
    if (!selectedCity || !dateConfirmed) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    setDetailLoad(true);
    fetchCityDetail({ date, city: selectedCity })
      .then(d => { if (!cancelled) setDetail(d); })
      .catch(()  => { if (!cancelled) setDetail(null); })
      .finally(() => { if (!cancelled) setDetailLoad(false); });
    return () => { cancelled = true; };
  }, [selectedCity, date, dateConfirmed]);

  function handleDateSelect(d) {
    onDateChange(d);
    setDateConfirmed(true);
    setSelectedCity(null);
    setDetail(null);
  }

  const controls = (
    <>
      <div className="ctrl-field">
        <span className="ctrl-label">Metrik</span>
        <SegmentedControl
          options={METRIC_OPTIONS}
          value={metric}
          onChange={setMetric}
        />
      </div>
      <div className="ctrl-spacer" />
      <Button
        onClick={loadSummary}
        disabled={summaryLoad}
        variant="secondary"
        icon="↻"
        size="sm"
      >
        {summaryLoad ? 'Yükleniyor…' : 'Yenile'}
      </Button>
    </>
  );

  return (
    <>
      <DateCalendar
        selectedDate={date}
        onSelect={handleDateSelect}
        fetchDates={fetchStationPerformanceAvailableDates}
        label="Analiz Tarihi Seç"
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
            title="Türkiye Şehir Performans Haritası"
            subtitle="İl tıklayarak detayları görebilirsiniz"
            controls={controls}
            minHeight={420}
          >
            <div style={{
              display: 'grid',
              gridTemplateColumns: 'minmax(0, 1.6fr) minmax(320px, 1fr)',
              gap: 24,
              alignItems: 'start',
            }}>
              <div style={{ minWidth: 0 }}>
                {summaryLoad
                  ? <div className="loading">Yükleniyor…</div>
                  : (
                    <TurkeyMap
                      citySummary={summary}
                      metric={metric}
                      selectedCity={selectedCity}
                      onSelect={setSelectedCity}
                    />
                  )}
              </div>

              <div style={{
                padding: 16,
                background: 'var(--bg-surface)',
                border: '1px solid var(--bd-subtle)',
                borderRadius: 'var(--rx-md)',
                minHeight: 360,
              }}>
                <CityDetailPanel
                  city={selectedCity}
                  detail={detail}
                  loading={detailLoad}
                  onClose={() => setSelectedCity(null)}
                />
              </div>
            </div>
          </ChartCard>
        </>
      )}
    </>
  );
}
