import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  ScatterChart, Scatter, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  ReferenceLine, BarChart, Bar, LineChart, Line, Legend,
} from 'recharts';
import {
  fetchBacktestDates, fetchBacktestSummary,
  fetchBacktestStations, fetchBacktestTrend,
} from '../api/zesApi';
import {
  MetricCard, ChartCard, EmptyState, TOOLTIP_STYLE,
} from '../components/ui';
import DateCalendar from '../components/DateCalendar';

const BEST_MIN_ACTUAL = 1.0;  // "best" sıralaması için minimum actual eşiği
const N_TABLE_ROWS    = 20;

const COLOR_MODEL = 'var(--accent)';
const COLOR_NAIVE = '#9ca3af';
const COLOR_ACTUAL = '#10b981';
const COLOR_GOOD  = '#10b981';
const COLOR_WARN  = '#d97706';
const COLOR_BAD   = '#dc2626';

const errorColor = (e) => {
  if (e == null) return 'var(--tx-disabled)';
  const a = Math.abs(e);
  if (a <= 5)  return COLOR_GOOD;
  if (a <= 12) return COLOR_WARN;
  return COLOR_BAD;
};

export default function ZesBacktestPage({ date, onDateChange }) {
  const [summary, setSummary]   = useState(null);
  const [stations, setStations] = useState(null);
  const [trend, setTrend]       = useState(null);
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState(null);

  const fetchDates = useCallback(() => fetchBacktestDates(), []);

  useEffect(() => {
    fetchBacktestTrend().then(setTrend).catch(() => setTrend([]));
  }, []);

  useEffect(() => {
    if (!date) { setSummary(null); setStations(null); return; }
    setLoading(true);
    setError(null);
    Promise.all([
      fetchBacktestSummary(date),
      fetchBacktestStations(date),
    ])
      .then(([s, st]) => { setSummary(s); setStations(st); })
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, [date]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <DateCalendar
        selectedDate={date}
        onSelect={onDateChange}
        fetchDates={fetchDates}
        label="Doğrulama Tarihi Seç"
      />

      {!date && (
        <EmptyState title="Bir tarih seç" description="Forecast ve perf indeksi birlikte var olan günleri yukarıdan seçin." />
      )}

      {error && <EmptyState title="Hata" description={error} />}

      {date && loading && <div className="loading">Yükleniyor…</div>}

      {date && !loading && summary && stations && (
        <>
          <KpiRow summary={summary} />
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16 }}>
            <ChartCard title="Tahmin vs Gerçek — istasyon dağılımı">
              <ScatterPredVsActual rows={stations} />
            </ChartCard>
            <ChartCard title="Hata Dağılımı (tahmin − gerçek)">
              <ErrorHistogram rows={stations} />
            </ChartCard>
          </div>
          <ChartCard title={`Şehir Bazında MAE — model vs naive (en yoğun ${15} şehir)`}>
            <CityBreakdown summary={summary} />
          </ChartCard>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <BestStationsTable rows={stations} />
            <WorstStationsTable rows={stations} />
          </div>
          <ChartCard title="Tüm Tarihler — MAE Trendi (Model vs Naive)">
            <TrendStrip trend={trend} selectedDate={date} onSelect={onDateChange} />
          </ChartCard>
        </>
      )}
    </div>
  );
}

/* ── KPI Cards ─────────────────────────────────────────────────────── */
function KpiRow({ summary }) {
  const m = summary.model || {};
  const n = summary.naive || {};
  const imp = summary.improvement_pct_vs_naive ?? 0;
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
      <MetricCard label="İstasyon" value={(summary.n ?? 0).toLocaleString()} />
      <MetricCard
        label="MAE — Model"
        value={`${m.mae?.toFixed(2) ?? '—'} pp`}
        sub={`Naive: ${n.mae?.toFixed(2) ?? '—'}`}
      />
      <MetricCard
        label="İyileşme (Model vs Naive)"
        value={`${imp > 0 ? '+' : ''}${imp.toFixed(1)}%`}
        color={imp > 0 ? COLOR_GOOD : COLOR_BAD}
      />
      <MetricCard
        label="R² — Model"
        value={m.r2?.toFixed(3) ?? '—'}
        sub={`Naive: ${n.r2?.toFixed(3) ?? '—'}`}
      />
      <MetricCard
        label="Bias — Model"
        value={`${(m.bias ?? 0) > 0 ? '+' : ''}${m.bias?.toFixed(2) ?? '—'} pp`}
        sub={(m.bias ?? 0) > 0 ? 'Sistematik fazla tahmin' : 'Sistematik az tahmin'}
      />
    </div>
  );
}

/* ── Scatter: predicted vs actual with y=x ─────────────────────────── */
function ScatterPredVsActual({ rows }) {
  const data = useMemo(() => rows.map(r => ({
    x: r.actual, y: r.predicted, error: Math.abs(r.error),
    station: r.station_name, city: r.city_name,
    actual: r.actual, predicted: r.predicted,
  })), [rows]);

  const colored = useMemo(() => {
    const good = [], warn = [], bad = [];
    for (const d of data) {
      if (d.error <= 5)       good.push(d);
      else if (d.error <= 12) warn.push(d);
      else                    bad.push(d);
    }
    return { good, warn, bad };
  }, [data]);

  return (
    <ResponsiveContainer width="100%" height={380}>
      <ScatterChart margin={{ top: 32, right: 24, left: 0, bottom: 56 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" />
        <XAxis type="number" dataKey="x" name="Gerçek (%)" domain={[0, 'dataMax']}
          tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
          label={{ value: 'Gerçek Utilization (%)', position: 'bottom', offset: 16, fill: 'var(--tx-tertiary)', fontSize: 11 }} />
        <YAxis type="number" dataKey="y" name="Tahmin (%)" domain={[0, 'dataMax']}
          tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
          label={{ value: 'Tahmin (%)', angle: -90, position: 'insideLeft', fill: 'var(--tx-tertiary)', fontSize: 11 }} />
        <ReferenceLine
          segment={[{ x: 0, y: 0 }, { x: 100, y: 100 }]}
          stroke="var(--tx-tertiary)" strokeDasharray="4 4" label={{ value: 'y=x', fill: 'var(--tx-tertiary)', fontSize: 10 }}
        />
        <Tooltip
          cursor={{ strokeDasharray: '3 3' }}
          content={({ payload }) => {
            if (!payload || !payload.length) return null;
            const p = payload[0].payload;
            return (
              <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--bd-default)', borderRadius: 6, padding: 8, fontSize: 11 }}>
                <div style={{ fontWeight: 700, color: 'var(--tx-primary)' }}>{p.station ?? '—'}</div>
                <div style={{ color: 'var(--tx-tertiary)' }}>{p.city ?? '—'}</div>
                <div style={{ marginTop: 4, fontFamily: 'var(--font-mono)' }}>
                  <div>Gerçek: <b>{p.actual?.toFixed(1)}%</b></div>
                  <div>Tahmin: <b>{p.predicted?.toFixed(1)}%</b></div>
                  <div style={{ color: errorColor(p.predicted - p.actual) }}>
                    Hata: <b>{(p.predicted - p.actual > 0 ? '+' : '')}{(p.predicted - p.actual).toFixed(1)}</b>
                  </div>
                </div>
              </div>
            );
          }}
        />
        <Scatter name={`Hata ≤ 5pp (${colored.good.length})`}  data={colored.good} fill={COLOR_GOOD} fillOpacity={0.55} />
        <Scatter name={`Hata 5-12pp (${colored.warn.length})`} data={colored.warn} fill={COLOR_WARN} fillOpacity={0.55} />
        <Scatter name={`Hata >12pp (${colored.bad.length})`}   data={colored.bad}  fill={COLOR_BAD}  fillOpacity={0.6} />
        <Legend
          verticalAlign="top"
          align="center"
          wrapperStyle={{ fontSize: 11, color: 'var(--tx-tertiary)', paddingBottom: 8 }}
        />
      </ScatterChart>
    </ResponsiveContainer>
  );
}

/* ── Histogram of error ────────────────────────────────────────────── */
function ErrorHistogram({ rows }) {
  const buckets = useMemo(() => {
    const step = 2;
    const min = -20, max = 20;
    const arr = [];
    for (let b = min; b < max; b += step) arr.push({ bucket: b, count: 0, label: `${b}` });
    for (const r of rows) {
      const e = r.error;
      if (e == null) continue;
      const clamped = Math.max(min, Math.min(max - 1, e));
      const idx = Math.floor((clamped - min) / step);
      if (arr[idx]) arr[idx].count += 1;
    }
    return arr;
  }, [rows]);

  return (
    <ResponsiveContainer width="100%" height={340}>
      <BarChart data={buckets} margin={{ top: 8, right: 16, left: 0, bottom: 24 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" vertical={false} />
        <XAxis dataKey="label" tick={{ fill: 'var(--tx-tertiary)', fontSize: 10 }}
          label={{ value: 'Hata (pp) — pozitif = fazla tahmin', position: 'insideBottom', offset: -10, fill: 'var(--tx-tertiary)', fontSize: 11 }} />
        <YAxis tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }} allowDecimals={false} />
        <Tooltip
          formatter={(v) => [`${v} istasyon`, 'sayı']}
          labelFormatter={l => `Hata: ${l} ile ${parseInt(l) + 2} pp arası`}
          {...TOOLTIP_STYLE}
        />
        <ReferenceLine x="0" stroke={COLOR_GOOD} strokeDasharray="3 3" />
        <Bar dataKey="count" fill={COLOR_MODEL} radius={[3, 3, 0, 0]} maxBarSize={28} />
      </BarChart>
    </ResponsiveContainer>
  );
}

/* ── City breakdown: horizontal grouped bars ──────────────────────── */
function CityBreakdown({ summary }) {
  const data = useMemo(() => {
    return (summary.by_city || [])
      .slice()
      .sort((a, b) => b.n - a.n)
      .slice(0, 15)
      .reverse();  // recharts y-axis renders bottom-up
  }, [summary]);

  return (
    <ResponsiveContainer width="100%" height={Math.max(220, data.length * 32 + 40)}>
      <BarChart data={data} layout="vertical" margin={{ top: 8, right: 24, left: 80, bottom: 8 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" horizontal={false} />
        <XAxis type="number" tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
          label={{ value: 'MAE (pp)', position: 'insideBottom', offset: -2, fill: 'var(--tx-tertiary)', fontSize: 11 }} />
        <YAxis type="category" dataKey="city" tick={{ fill: 'var(--tx-secondary)', fontSize: 11 }} width={80} />
        <Tooltip
          formatter={(v, name) => [`${Number(v).toFixed(2)} pp`, name]}
          labelFormatter={(l, payload) => {
            const p = payload?.[0]?.payload;
            return p ? `${l} (n=${p.n})` : l;
          }}
          {...TOOLTIP_STYLE}
        />
        <Legend wrapperStyle={{ fontSize: 11, color: 'var(--tx-tertiary)' }} />
        <Bar dataKey="mae_naive" name="Naive MAE" fill={COLOR_NAIVE} radius={[0, 3, 3, 0]} />
        <Bar dataKey="mae_model" name="Model MAE" fill={COLOR_MODEL} radius={[0, 3, 3, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}

/* ── Best 20: smallest |error| among rows with actual >= threshold ─── */
function BestStationsTable({ rows }) {
  const top = useMemo(() => {
    return rows
      .filter(r => (r.actual ?? 0) >= BEST_MIN_ACTUAL)
      .slice()
      .sort((a, b) => a.abs_error - b.abs_error)
      .slice(0, N_TABLE_ROWS);
  }, [rows]);
  return <StationsTable title={`En İsabetli ${N_TABLE_ROWS} Tahmin`} subtitle={`gerçek ≥ ${BEST_MIN_ACTUAL}% olan istasyonlar arasından`} rows={top} />;
}

/* ── Worst 20: largest |error| ─────────────────────────────────────── */
function WorstStationsTable({ rows }) {
  const top = useMemo(() => {
    return rows
      .slice()
      .sort((a, b) => b.abs_error - a.abs_error)
      .slice(0, N_TABLE_ROWS);
  }, [rows]);
  return <StationsTable title={`En Hatalı ${N_TABLE_ROWS} Tahmin`} subtitle="|tahmin − gerçek| en büyük" rows={top} />;
}

function StationsTable({ title, subtitle, rows }) {
  return (
    <div style={{ background: 'var(--bg-surface)', border: '1px solid var(--bd-subtle)', borderRadius: 'var(--rx-lg)', overflow: 'hidden' }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--bd-subtle)' }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--tx-primary)' }}>{title}</div>
        <div style={{ fontSize: 11, color: 'var(--tx-tertiary)', marginTop: 2 }}>{subtitle}</div>
      </div>
      <div className="table-wrapper" style={{ maxHeight: 480, overflowY: 'auto' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th style={{ width: 32, textAlign: 'right' }}>#</th>
              <th>İstasyon</th>
              <th>Şehir</th>
              <th style={{ width: 70, textAlign: 'right' }}>Tahmin</th>
              <th style={{ width: 70, textAlign: 'right' }}>Gerçek</th>
              <th style={{ width: 70, textAlign: 'right' }}>Hata</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.station_id}>
                <td style={{ textAlign: 'right', color: 'var(--tx-disabled)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>{i + 1}</td>
                <td style={{ fontWeight: 600, color: 'var(--tx-primary)' }}>{r.station_name ?? '—'}</td>
                <td style={{ color: 'var(--tx-secondary)' }}>{r.city_name ?? '—'}</td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)' }}>{r.predicted?.toFixed(1)}%</td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', color: COLOR_ACTUAL }}>{r.actual?.toFixed(1)}%</td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', fontWeight: 700, color: errorColor(r.error) }}>
                  {r.error > 0 ? '+' : ''}{r.error?.toFixed(1)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ── Trend strip: per-date MAE line ────────────────────────────────── */
function TrendStrip({ trend, selectedDate, onSelect }) {
  if (!trend || !trend.length) return <div className="loading">Trend yükleniyor…</div>;
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={trend} margin={{ top: 8, right: 24, left: 0, bottom: 32 }}
        onClick={(e) => { if (e?.activeLabel) onSelect(e.activeLabel); }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" vertical={false} />
        <XAxis dataKey="date" tick={{ fill: 'var(--tx-tertiary)', fontSize: 10 }} angle={-45} textAnchor="end" interval={0} height={70} />
        <YAxis tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
          label={{ value: 'MAE (pp)', angle: -90, position: 'insideLeft', fill: 'var(--tx-tertiary)', fontSize: 11 }} />
        <Tooltip
          formatter={(v) => [`${Number(v).toFixed(2)} pp`, '']}
          {...TOOLTIP_STYLE}
        />
        <Legend wrapperStyle={{ fontSize: 11, color: 'var(--tx-tertiary)' }} />
        {selectedDate && <ReferenceLine x={selectedDate} stroke={COLOR_MODEL} strokeDasharray="3 3" />}
        <Line type="monotone" dataKey="mae_naive" name="Naive" stroke={COLOR_NAIVE} strokeWidth={1.5} dot={{ r: 3 }} />
        <Line type="monotone" dataKey="mae_model" name="Model" stroke={COLOR_MODEL} strokeWidth={2} dot={{ r: 4 }} />
      </LineChart>
    </ResponsiveContainer>
  );
}
