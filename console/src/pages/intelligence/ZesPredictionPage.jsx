import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  ScatterChart, Scatter, ZAxis, ReferenceLine,
} from 'recharts';
import {
  fetchForecastSummary,
  fetchForecastTop,
  fetchForecastScatter,
  fetchForecastAvailableDates,
} from '../../api/zesApi';
import {
  Button, ChartCard, EmptyState, MetricCard,
  TOOLTIP_STYLE, SectionLabel, FilterChip,
} from '../../components/ui';
import DateCalendar from '../../components/DateCalendar';

/* ── Delta color: kırmızı = düşüş, yeşil = artış ──────────────────── */
const deltaColor = (d) => {
  if (d == null) return 'var(--tx-disabled)';
  if (d >= 5)  return 'var(--clr-success-text)';
  if (d <= -5) return 'var(--clr-danger-text)';
  return 'var(--tx-tertiary)';
};

/* ── Error color: |error| ≤ 5 yeşil, ≤ 12 sarı, > 12 kırmızı ─────────── */
const errorColor = (e) => {
  if (e == null) return 'var(--tx-disabled)';
  const a = Math.abs(e);
  if (a <= 5)  return 'var(--clr-success-text)';
  if (a <= 12) return '#d97706';
  return 'var(--clr-danger-text)';
};

const DIRECTION_OPTS = [
  { value: 'up',   label: '↓ Azalan',  color: 'var(--tx-primary)' },
  { value: 'down', label: '↑ Artan',   color: 'var(--tx-primary)' },
];

const SORT_OPTS = [
  { value: 'delta',     label: 'Δ (pp)' },
  { value: 'predicted', label: 'Tahmin' },
  { value: 'actual',    label: 'Gerçek' },
  { value: 'source',    label: 'Kaynak' },
  { value: 'error',     label: 'Hata' },
];

/* ── Forecast list (paginated, all results) ──────────────────────────── */
function ForecastList({ result, page, pageSize, onPageChange, sortBy, direction, onSortChange }) {
  if (!result) return <div className="loading">Yükleniyor…</div>;
  const { total = 0, rows = [] } = result;
  if (!rows.length) return <EmptyState title="Sonuç yok" description="Filtreyi gevşetmeyi dene" />;

  const startIndex = page * pageSize;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const endIndex = Math.min(startIndex + rows.length, total);

  const arrow = (col) => sortBy === col ? (direction === 'down' ? ' ↑' : ' ↓') : '';
  const sortableHeader = (col, label, extra = {}) => (
    <th
      style={{
        textAlign: 'right',
        cursor: 'pointer',
        userSelect: 'none',
        color: sortBy === col ? 'var(--accent)' : undefined,
        ...extra,
      }}
      onClick={() => onSortChange?.(col)}
      title="Sıralamak için tıkla"
    >
      {label}{arrow(col)}
    </th>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="table-wrapper" style={{ maxHeight: 480, overflowY: 'auto' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th style={{ width: 48, textAlign: 'right' }}>#</th>
              <th>İstasyon</th>
              <th>Şehir</th>
              {sortableHeader('source',    'Kaynak', { width: 90 })}
              {sortableHeader('predicted', 'Tahmin', { width: 90 })}
              {sortableHeader('actual',    'Gerçek', { width: 90 })}
              {sortableHeader('delta',     'Δ (pp)', { width: 90 })}
              {sortableHeader('error',     'Hata',   { width: 90 })}
              <th>Görsel</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => {
              const today  = Number(r.source_utilization_pct ?? 0);
              const pred   = Number(r.predicted_utilization_pct ?? 0);
              const delta  = Number(r.delta_vs_source ?? 0);
              const hasAct = r.actual_utilization_pct != null;
              const actual = hasAct ? Number(r.actual_utilization_pct) : null;
              const error  = hasAct ? Number(r.error_vs_actual ?? (pred - actual)) : null;
              const max = Math.max(today, pred, actual ?? 0, 1);
              return (
                <tr key={r.station_id}>
                  <td style={{ textAlign: 'right', color: 'var(--tx-disabled)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                    {startIndex + i + 1}
                  </td>
                  <td style={{ fontWeight: 600, color: 'var(--tx-primary)', maxWidth: 220 }}>
                    {r.station_name ?? '—'}
                  </td>
                  <td style={{ color: 'var(--tx-secondary)' }}>{r.city_name ?? '—'}</td>
                  <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', color: 'var(--tx-secondary)' }}>
                    {today.toFixed(1)}%
                  </td>
                  <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', color: 'var(--tx-primary)', fontWeight: 600 }}>
                    {pred.toFixed(1)}%
                  </td>
                  <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', color: hasAct ? 'var(--clr-success-text)' : 'var(--tx-disabled)', fontWeight: hasAct ? 600 : 400 }}>
                    {hasAct ? `${actual.toFixed(1)}%` : '—'}
                  </td>
                  <td style={{
                    textAlign: 'right', fontFamily: 'var(--font-mono)', fontWeight: 700,
                    color: deltaColor(delta),
                  }}>
                    {delta > 0 ? '+' : ''}{delta.toFixed(1)}
                  </td>
                  <td style={{
                    textAlign: 'right', fontFamily: 'var(--font-mono)', fontWeight: 700,
                    color: errorColor(error),
                  }}>
                    {hasAct ? `${error > 0 ? '+' : ''}${error.toFixed(1)}` : '—'}
                  </td>
                  <td style={{ minWidth: 200 }}>
                    <TripleBar today={today} pred={pred} actual={actual} max={max} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '6px 12px', borderTop: '1px solid var(--bd-subtle)',
      }}>
        <span style={{ fontSize: 11, color: 'var(--tx-tertiary)', fontFamily: 'var(--font-mono)' }}>
          {(startIndex + 1).toLocaleString()}–{endIndex.toLocaleString()} / {total.toLocaleString()}
        </span>
        <div className="pagination" style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          <button onClick={() => onPageChange(0)} disabled={page === 0}>« İlk</button>
          <button onClick={() => onPageChange(page - 1)} disabled={page === 0}>← Önceki</button>
          <span style={{ fontFamily: 'var(--font-mono)', padding: '0 10px', fontSize: 12, color: 'var(--tx-secondary)' }}>
            {page + 1} / {totalPages}
          </span>
          <button onClick={() => onPageChange(page + 1)} disabled={page + 1 >= totalPages}>Sonraki →</button>
          <button onClick={() => onPageChange(totalPages - 1)} disabled={page + 1 >= totalPages}>Son »</button>
        </div>
      </div>
    </div>
  );
}

function TripleBar({ today, pred, actual, max }) {
  const todayPct = (today / max) * 100;
  const predPct  = (pred  / max) * 100;
  const actPct   = actual != null ? (actual / max) * 100 : null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 9, color: 'var(--tx-tertiary)' }}>
        <span style={{ width: 36 }}>Kaynak</span>
        <div style={{ flex: 1, height: 6, background: 'var(--bg-elevated)', borderRadius: 3, overflow: 'hidden' }}>
          <div style={{ width: `${todayPct}%`, height: '100%', background: 'var(--tx-tertiary)' }} />
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 9, color: 'var(--tx-tertiary)' }}>
        <span style={{ width: 36 }}>Tahmin</span>
        <div style={{ flex: 1, height: 6, background: 'var(--bg-elevated)', borderRadius: 3, overflow: 'hidden' }}>
          <div style={{ width: `${predPct}%`, height: '100%', background: 'var(--accent)' }} />
        </div>
      </div>
      {actPct != null && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 9, color: 'var(--tx-tertiary)' }}>
          <span style={{ width: 36 }}>Gerçek</span>
          <div style={{ flex: 1, height: 6, background: 'var(--bg-elevated)', borderRadius: 3, overflow: 'hidden' }}>
            <div style={{ width: `${actPct}%`, height: '100%', background: 'var(--clr-success)' }} />
          </div>
        </div>
      )}
    </div>
  );
}

/* ── Summary view: stats + histogram + delta histogram ───────────────── */
function SummaryView({ summary }) {
  if (!summary) return <div className="loading">Yükleniyor…</div>;
  const pStats = summary.predicted_stats ?? {};
  const tStats = summary.source_stats ?? {};
  const dStats = summary.delta_stats ?? {};
  const predHist = summary.predicted_histogram ?? [];
  const deltaHist = summary.delta_histogram ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <MetricCard label="Tahmin Yapılan" value={(summary.count ?? 0).toLocaleString()} />
        <MetricCard label="Tahmin Ort." value={`${pStats.avg?.toFixed(1) ?? '—'}%`} />
        <MetricCard label="Kaynak Ort." value={`${tStats.avg?.toFixed(1) ?? '—'}%`} />
        <MetricCard
          label="Ort. Δ (pp)"
          value={`${(dStats.avg ?? 0) > 0 ? '+' : ''}${dStats.avg?.toFixed(2) ?? '—'}`}
          color={deltaColor(dStats.avg ?? 0)}
        />
        <MetricCard label="Δ Std" value={dStats.stddev?.toFixed(2) ?? '—'} />
      </div>

      <div>
        <SectionLabel>Tahmin Edilen Utilization Dağılımı (yarın için)</SectionLabel>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={predHist} margin={{ top: 8, right: 24, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" vertical={false} />
            <XAxis dataKey="bucket" tick={{ fill: 'var(--tx-tertiary)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
              axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
              axisLine={false} tickLine={false} allowDecimals={false} />
            <Tooltip
              formatter={(v) => [`${v} istasyon`, 'sayı']}
              labelFormatter={l => `${l}-${l + 10}% tahmin aralığı`}
              {...TOOLTIP_STYLE}
            />
            <Bar dataKey="count" fill="var(--accent)" radius={[3, 3, 0, 0]} maxBarSize={40} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div>
        <SectionLabel>Δ (Tahmin − Kaynak) Dağılımı — pozitif yükseliş, negatif düşüş</SectionLabel>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={deltaHist} margin={{ top: 8, right: 24, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" vertical={false} />
            <XAxis dataKey="bucket" tick={{ fill: 'var(--tx-tertiary)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
              axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
              axisLine={false} tickLine={false} allowDecimals={false} />
            <Tooltip
              formatter={(v) => [`${v} istasyon`, 'sayı']}
              labelFormatter={l => `Δ ${l > 0 ? '+' : ''}${l} ile ${l + 5}`}
              {...TOOLTIP_STYLE}
            />
            <ReferenceLine x={0} stroke="var(--tx-disabled)" strokeDasharray="3 3" />
            <Bar dataKey="count" fill="var(--accent)" radius={[3, 3, 0, 0]} maxBarSize={32} />
          </BarChart>
        </ResponsiveContainer>
        <div style={{ fontSize: 10, color: 'var(--tx-disabled)', marginTop: 6, lineHeight: 1.5 }}>
          0 etrafında yığılma = model çoğunlukla "hedef gün kaynakla aynı" diyor (autoregressive davranış).
          Asimetri sağa kaysa "filo genel olarak yükselişte" sinyali; sola kaysa "düşüşte".
        </div>
      </div>
    </div>
  );
}

/* ── Today vs Predicted scatter (with y=x diagonal) ──────────────────── */
function PredictionScatter({ points }) {
  const data = useMemo(() => {
    return (points ?? []).map(p => ({
      x: p.source_utilization_pct ?? 0,
      y: p.predicted_utilization_pct ?? 0,
      delta: p.delta_vs_source ?? 0,
      name: p.station_name,
      city: p.city_name,
    }));
  }, [points]);

  if (!points) return <div className="loading">Yükleniyor…</div>;
  if (!points.length) return <EmptyState title="Veri yok" />;

  return (
    <div>
      <ResponsiveContainer width="100%" height={440}>
        <ScatterChart margin={{ top: 16, right: 24, left: 8, bottom: 24 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" />
          <XAxis
            type="number" dataKey="x" name="Kaynak"
            domain={[0, 100]}
            tick={{ fill: 'var(--tx-tertiary)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
            axisLine={{ stroke: 'var(--bd-subtle)' }} tickLine={false}
            label={{ value: 'Kaynak Utilization % →', position: 'insideBottom',
              offset: -10, fill: 'var(--tx-disabled)', fontSize: 11 }}
          />
          <YAxis
            type="number" dataKey="y" name="Tahmin"
            domain={[0, 100]}
            tick={{ fill: 'var(--tx-tertiary)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
            axisLine={{ stroke: 'var(--bd-subtle)' }} tickLine={false}
            label={{ value: 'Tahmin Utilization % ↑', angle: -90, position: 'insideLeft',
              offset: 12, fill: 'var(--tx-disabled)', fontSize: 11 }}
          />
          <ZAxis range={[18, 18]} />
          <ReferenceLine
            segment={[{ x: 0, y: 0 }, { x: 100, y: 100 }]}
            stroke="var(--tx-disabled)" strokeDasharray="5 5"
            label={{ value: 'y=x', position: 'insideTopRight', fill: 'var(--tx-disabled)', fontSize: 10 }}
          />
          <Tooltip
            cursor={{ strokeDasharray: '3 3' }}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null;
              const p = payload[0].payload;
              return (
                <div style={{
                  background: 'var(--bg-elevated)', border: '1px solid var(--bd-subtle)',
                  borderRadius: 'var(--rx-md)', padding: '8px 12px', fontSize: 11,
                }}>
                  <div style={{ fontWeight: 600, color: 'var(--tx-primary)', marginBottom: 4 }}>
                    {p.name ?? '—'}
                  </div>
                  <div style={{ color: 'var(--tx-tertiary)' }}>{p.city}</div>
                  <div style={{ fontFamily: 'var(--font-mono)', color: 'var(--tx-secondary)', marginTop: 4 }}>
                    Kaynak: {p.x?.toFixed(1)}% · Tahmin: {p.y?.toFixed(1)}%
                  </div>
                  <div style={{ fontFamily: 'var(--font-mono)', color: deltaColor(p.delta), fontWeight: 600 }}>
                    Δ {p.delta > 0 ? '+' : ''}{p.delta?.toFixed(1)} pp
                  </div>
                </div>
              );
            }}
          />
          <Scatter
            data={data}
            fill="var(--accent)"
            fillOpacity={0.5}
          />
        </ScatterChart>
      </ResponsiveContainer>
      <div style={{ fontSize: 10, color: 'var(--tx-disabled)', marginTop: 10, lineHeight: 1.5, textAlign: 'center' }}>
        y=x doğrusunun üstündeki noktalar "hedef gün kaynaktan yüksek olacak" tahminidir; altındakiler "düşecek".
        Doğru üzerinde yoğunlaşma = model "değişiklik yok" diyor. Saçılma genişliği = modelin güveni.
      </div>
    </div>
  );
}

/* ══ Main page ════════════════════════════════════════════════════════ */
const PAGE_SIZE = 20;

export default function ZesPredictionPage({ date, onDateChange }) {
  const [dateConfirmed, setDateConfirmed] = useState(!!date);
  const [chartIndex,    setChartIndex]    = useState(0);

  const [topResult, setTopResult] = useState(null);   // {total, from, size, rows}
  const [summary,   setSummary]   = useState(null);
  const [scatter,   setScatter]   = useState(null);

  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);

  const [direction,         setDirection]         = useState('up');
  const [sortBy,            setSortBy]            = useState('delta');
  const [cityFilter,        setCityFilter]        = useState('');
  const [stationNameFilter, setStationNameFilter] = useState('');
  const [page,              setPage]              = useState(0);

  // Reset page when filters change
  useEffect(() => { setPage(0); }, [direction, sortBy, cityFilter, stationNameFilter]);

  // Click on a column header: same field → toggle direction; new field → set sortBy (keep direction)
  const handleSortChange = useCallback((col) => {
    if (col === sortBy) {
      setDirection(d => d === 'up' ? 'down' : 'up');
    } else {
      setSortBy(col);
    }
  }, [sortBy]);

  const loadData = useCallback(async () => {
    if (!dateConfirmed) return;
    setLoading(true);
    setError(null);
    try {
      const [topData, summaryData, scatterData] = await Promise.all([
        fetchForecastTop({
          date,
          from: page * PAGE_SIZE,
          size: PAGE_SIZE,
          direction,
          sortBy,
          city:        cityFilter        || undefined,
          stationName: stationNameFilter || undefined,
        }),
        fetchForecastSummary({ date }),
        fetchForecastScatter({ date, size: 2000 }),
      ]);
      setTopResult(topData);
      setSummary(summaryData);
      setScatter(scatterData);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, dateConfirmed, direction, sortBy, cityFilter, stationNameFilter, page]);

  useEffect(() => { loadData(); }, [loadData]);

  function handleDateSelect(d) {
    onDateChange(d);
    setDateConfirmed(true);
  }

  const sortLabel = SORT_OPTS.find(o => o.value === sortBy)?.label ?? sortBy;
  const dirLabel  = direction === 'up' ? 'azalan' : 'artan';
  const charts = [
    {
      title: sortBy === 'delta'
        ? (direction === 'up' ? 'Yükseliş Beklenenler' : 'Düşüş Beklenenler')
        : `${sortLabel} · ${dirLabel}`,
      subtitle: `${sortLabel} ${dirLabel} · sayfa başına ${PAGE_SIZE}`,
      node: (
        <ForecastList
          result={topResult}
          page={page}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
          sortBy={sortBy}
          direction={direction}
          onSortChange={handleSortChange}
        />
      ),
    },
    {
      title: 'Tahmin Sağlığı / Özet',
      subtitle: 'tahmin/kaynak/Δ dağılımları',
      node: <SummaryView summary={summary} />,
    },
    {
      title: 'Kaynak vs Tahmin Scatter',
      subtitle: 'y=x doğrusundan sapma = autoregressive davranış sapması',
      node: <PredictionScatter points={scatter} />,
    },
  ];
  const total = charts.length;
  const prev = () => setChartIndex(i => (i - 1 + total) % total);
  const next = () => setChartIndex(i => (i + 1) % total);

  useEffect(() => {
    const onKey = e => {
      if (e.target?.tagName === 'INPUT' || e.target?.tagName === 'TEXTAREA') return;
      if (e.key === 'ArrowLeft')  prev();
      if (e.key === 'ArrowRight') next();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const current = charts[chartIndex];

  const chartControls = (
    <>
      {chartIndex === 0 && (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 10, color: 'var(--tx-disabled)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
              Sırala:
            </span>
            {SORT_OPTS.map(o => (
              <FilterChip
                key={o.value}
                label={o.label}
                active={sortBy === o.value}
                onClick={() => setSortBy(o.value)}
              />
            ))}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 10, color: 'var(--tx-disabled)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
              Yön:
            </span>
            {DIRECTION_OPTS.map(o => (
              <FilterChip
                key={o.value}
                label={o.label}
                color={o.color}
                active={direction === o.value}
                onClick={() => setDirection(o.value)}
              />
            ))}
          </div>
          <input
            type="text" placeholder="İstasyon adı…"
            value={stationNameFilter}
            onChange={e => setStationNameFilter(e.target.value)}
            style={{
              padding: '4px 10px', fontSize: 12,
              background: 'var(--bg-elevated)',
              border: '1px solid var(--bd-subtle)',
              borderRadius: 'var(--rx-md)',
              color: 'var(--tx-primary)',
              minWidth: 180,
            }}
          />
          <input
            type="text" placeholder="Şehir filtresi…"
            value={cityFilter}
            onChange={e => setCityFilter(e.target.value)}
            style={{
              padding: '4px 10px', fontSize: 12,
              background: 'var(--bg-elevated)',
              border: '1px solid var(--bd-subtle)',
              borderRadius: 'var(--rx-md)',
              color: 'var(--tx-primary)',
              minWidth: 140,
            }}
          />
        </>
      )}
      <div className="ctrl-spacer" />
      <Button onClick={loadData} disabled={loading} variant="secondary" icon="↻" size="sm">
        {loading ? 'Yükleniyor…' : 'Yenile'}
      </Button>
    </>
  );

  return (
    <>
      <DateCalendar
        selectedDate={date}
        onSelect={handleDateSelect}
        fetchDates={fetchForecastAvailableDates}
        label="Tahmin Tarihi Seç"
      />

      {!dateConfirmed ? (
        <EmptyState icon="🔮" title="Tarih seçin"
          description="Yukarıdaki takvimden zes_station_forecast index'i bulunan bir tarih seçin. (Önce FastAPI'da POST /forecast/train ve /forecast/predict çalıştırılmış olmalı.)" />
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
            minHeight={440}
          >
            {loading ? <div className="loading">Yükleniyor…</div> : current.node}
          </ChartCard>
        </>
      )}
    </>
  );
}
