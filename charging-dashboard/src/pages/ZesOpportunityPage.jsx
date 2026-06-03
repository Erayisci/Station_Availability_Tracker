import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  ScatterChart, Scatter, ZAxis,
} from 'recharts';
import {
  fetchOpportunitySummary,
  fetchOpportunityTop,
  fetchOpportunityScatter,
  fetchOpportunityAvailableDates,
} from '../api/zesApi';
import {
  Button, ChartCard, EmptyState, MetricCard,
  TOOLTIP_STYLE, SectionLabel, FilterChip,
} from '../components/ui';
import DateCalendar from '../components/DateCalendar';
import { REC_META } from './ZesPerformancePage';

/* ── Rule recommendation chip (demand × supply, self-explanatory) ──── */
const DEMAND_COLOR = {
  'Yüksek': 'var(--clr-danger-text)',
  'Orta':   'var(--clr-warning-text)',
  'Düşük':  'var(--clr-success-text)',
};
const SUPPLY_COLOR = {
  'Az':   'var(--clr-danger-text)',
  'Orta': 'var(--clr-warning-text)',
  'Çok':  'var(--clr-success-text)',
};

function MiniPill({ label, color }) {
  return (
    <span style={{
      fontSize: 9, padding: '1px 6px', borderRadius: 'var(--rx-sm)',
      background: 'var(--bg-elevated)',
      border: `1px solid ${color}40`,
      color, fontWeight: 600, whiteSpace: 'nowrap',
      fontFamily: 'var(--font-mono)',
    }}>
      {label}
    </span>
  );
}

function RuleRecChip({ code }) {
  if (!code || code === 'INSUFFICIENT_DATA') {
    return (
      <span style={{ fontSize: 11, color: 'var(--tx-disabled)' }}>
        {code === 'INSUFFICIENT_DATA' ? 'Yetersiz veri' : '—'}
      </span>
    );
  }
  const meta = REC_META[code];
  if (!meta) {
    return <span style={{ fontSize: 11, color: 'var(--tx-secondary)' }}>{code}</span>;
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
      <div style={{ display: 'flex', gap: 4 }}>
        <MiniPill label={`T:${meta.demand}`} color={DEMAND_COLOR[meta.demand] ?? 'var(--tx-tertiary)'} />
        <MiniPill label={`A:${meta.supply}`} color={SUPPLY_COLOR[meta.supply] ?? 'var(--tx-tertiary)'} />
      </div>
      <span style={{ fontSize: 10, color: 'var(--tx-tertiary)', lineHeight: 1.2 }}>
        {meta.label}
      </span>
    </div>
  );
}

/* ── Signal metadata (UI labels + direction) ───────────────────────── */
const SIGNAL_META = {
  charging_ratio:         { label: 'Şarj Oranı',       short: 'Şarj',     inverse: false, format: v => `${(v * 100).toFixed(1)}%` },
  peakiness:              { label: 'Tepe Yoğunluğu',   short: 'Tepe',     inverse: false, format: v => v.toFixed(2) },
  nearby_avg_utilization: { label: 'Yakın Kullanım',   short: 'Yakın K.', inverse: false, format: v => `${v.toFixed(1)}%` },
  nearby_stations_count:  { label: 'Yakın İstasyon',   short: 'Yakın İ.', inverse: true,  format: v => v.toFixed(0) },
  city_density:           { label: 'Şehir Yoğunluğu', short: 'Şehir',    inverse: true,  format: v => v.toFixed(0) },
  connector_count:        { label: 'Soket Sayısı',     short: 'Soket',    inverse: true,  format: v => v.toFixed(0) },
};
const SIGNAL_ORDER = [
  'charging_ratio',
  'peakiness',
  'nearby_avg_utilization',
  'nearby_stations_count',
  'city_density',
  'connector_count',
];

/* ── Growth tier color (monochrome scale) ──────────────────────────── */
const GROWTH_COLORS = {
  HIGH:    'var(--clr-danger-text)',
  MEDIUM:  'var(--clr-warning-text)',
  LOW:     'var(--tx-tertiary)',
  MINIMAL: 'var(--tx-disabled)',
};
const GROWTH_LABEL = {
  HIGH: 'Yüksek', MEDIUM: 'Orta', LOW: 'Düşük', MINIMAL: 'Minimal',
};
const growthColor = g => GROWTH_COLORS[g] ?? 'var(--tx-tertiary)';

// Agreement = tier-distance between model growth_potential and rule code's
// expected tier (see _REC_TO_EXPECTED_TIER in opportunity.py).
//   AGREE    — same tier (Δ=0)
//   NEAR     — adjacent tier (Δ=1)
//   DISAGREE — genuine divergence (Δ≥2)
const AGREE_COLORS = {
  AGREE:    'var(--clr-success-text)',
  NEAR:     'var(--clr-warning-text)',
  DISAGREE: 'var(--clr-danger-text)',
  UNKNOWN:  'var(--tx-disabled)',
};
const AGREE_LABEL = {
  AGREE:    'Mutabık',
  NEAR:     'Komşu Tier',
  DISAGREE: 'Mutabakatsızlık',
  UNKNOWN:  'Bilinmiyor',
};

/* ── Component breakdown bar (horizontal stacked) ──────────────────── */
function ComponentBar({ components }) {
  if (!components || !components.length) return null;
  const sorted = [...components].sort((a, b) => {
    return SIGNAL_ORDER.indexOf(a.signal) - SIGNAL_ORDER.indexOf(b.signal);
  });
  const total = sorted.reduce((s, c) => s + (c.contribution ?? 0), 0);
  if (total <= 0) return null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, minWidth: 220 }}>
      <div style={{
        display: 'flex', width: '100%', height: 8,
        borderRadius: 4, overflow: 'hidden',
        background: 'var(--bg-elevated)',
      }}>
        {sorted.map((c, i) => {
          const w = ((c.contribution ?? 0) / total) * 100;
          const opacity = 1 - i * 0.12;
          return (
            <div
              key={c.signal}
              title={`${SIGNAL_META[c.signal]?.label ?? c.signal}: ${c.contribution?.toFixed(1)} (pct=${(c.percentile * 100).toFixed(0)}%)`}
              style={{
                width: `${w}%`, height: '100%',
                background: 'var(--accent)',
                opacity,
                borderRight: i < sorted.length - 1 ? '1px solid var(--bg-base)' : 'none',
              }}
            />
          );
        })}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, fontSize: 9, color: 'var(--tx-tertiary)' }}>
        {sorted.map((c, i) => (
          <span key={c.signal} style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
            <span style={{
              width: 6, height: 6, borderRadius: 1,
              background: 'var(--accent)', opacity: 1 - i * 0.12,
            }} />
            {SIGNAL_META[c.signal]?.short ?? c.signal}
            <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--tx-disabled)' }}>
              {(c.contribution ?? 0).toFixed(1)}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}

/* ── Hot list (Top-N stations) ─────────────────────────────────────── */
function HotList({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows) return <div className="loading">Yükleniyor…</div>;
  if (!rows.length) return <EmptyState title="Veri yok" description="Bu tarih için opportunity index'i boş" />;

  return (
    <div className="table-wrapper" style={{ maxHeight: 540, overflowY: 'auto' }}>
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 32 }} />
            <th style={{ width: 36, textAlign: 'right' }}>#</th>
            <th>İstasyon</th>
            <th>Şehir</th>
            <th style={{ width: 80, textAlign: 'right' }}>Skor</th>
            <th style={{ width: 90 }}>Potansiyel</th>
            <th>Bileşen Kırılımı</th>
            <th style={{ width: 160 }}>Kural Tavsiyesi</th>
            <th style={{ width: 110 }}>Mutabakat</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const isOpen = expanded === i;
            return (
              <>
                <tr
                  key={`opp-${i}`}
                  onClick={() => setExpanded(p => p === i ? null : i)}
                  style={{ cursor: 'pointer' }}
                >
                  <td style={{ textAlign: 'center', color: 'var(--tx-disabled)', fontSize: 11, userSelect: 'none' }}>
                    <span style={{ display: 'inline-block', transition: 'transform 150ms', transform: isOpen ? 'rotate(90deg)' : 'none' }}>▶</span>
                  </td>
                  <td style={{ textAlign: 'right', color: 'var(--tx-disabled)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                    {i + 1}
                  </td>
                  <td style={{ fontWeight: 600, color: 'var(--tx-primary)', maxWidth: 220 }}>{r.station_name ?? '—'}</td>
                  <td style={{ color: 'var(--tx-secondary)' }}>{r.city_name ?? '—'}</td>
                  <td style={{
                    textAlign: 'right', fontFamily: 'var(--font-mono)', fontWeight: 700,
                    color: growthColor(r.growth_potential), fontSize: 13,
                  }}>
                    {Number(r.opportunity_score).toFixed(1)}
                  </td>
                  <td>
                    <span style={{
                      display: 'inline-block', padding: '2px 8px', borderRadius: 'var(--rx-full)',
                      fontSize: 10, fontWeight: 600,
                      color: growthColor(r.growth_potential),
                      background: 'var(--bg-elevated)',
                      border: `1px solid ${growthColor(r.growth_potential)}40`,
                    }}>
                      {GROWTH_LABEL[r.growth_potential] ?? r.growth_potential}
                    </span>
                  </td>
                  <td><ComponentBar components={r.components} /></td>
                  <td><RuleRecChip code={r.existing_recommendation_code} /></td>
                  <td>
                    <span style={{
                      display: 'inline-block', padding: '2px 8px', borderRadius: 'var(--rx-full)',
                      fontSize: 10, fontWeight: 600,
                      color: AGREE_COLORS[r.agreement] ?? 'var(--tx-tertiary)',
                      background: 'var(--bg-elevated)',
                      border: `1px solid ${(AGREE_COLORS[r.agreement] ?? 'var(--bd-subtle)')}40`,
                    }}>
                      {AGREE_LABEL[r.agreement] ?? r.agreement ?? '—'}
                    </span>
                  </td>
                </tr>
                {isOpen && (
                  <tr key={`opp-exp-${i}`}>
                    <td />
                    <td colSpan={8} style={{ background: 'var(--bg-elevated)', padding: '14px 18px' }}>
                      <DrillIn row={r} />
                    </td>
                  </tr>
                )}
              </>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/* ── Drill-in panel (per-signal bars + raw values) ─────────────────── */
function DrillIn({ row }) {
  const components = row.components ?? [];
  const sorted = [...components].sort((a, b) => {
    return SIGNAL_ORDER.indexOf(a.signal) - SIGNAL_ORDER.indexOf(b.signal);
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* Top stats row */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <MetricCard label="Opportunity Skoru" value={Number(row.opportunity_score).toFixed(1)}
          color={growthColor(row.growth_potential)} sub={GROWTH_LABEL[row.growth_potential]} />
        <MetricCard label="Demand/Supply (kural)"
          value={row.existing_demand_supply_ratio != null ? Number(row.existing_demand_supply_ratio).toFixed(1) : '—'} />
        <MetricCard label="Soket" value={row.connector_count ?? '—'} />
        <MetricCard label="Yakın İstasyon" value={row.nearby_stations_count ?? '—'} />
        <MetricCard label="Şehir Yoğ." value={row.city_density ?? '—'} sub={row.city_name} />
      </div>

      {/* Per-signal breakdown */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <SectionLabel>Sinyal Kırılımı</SectionLabel>
        {sorted.map(c => {
          const meta = SIGNAL_META[c.signal] ?? {};
          const pct  = Math.round((c.percentile ?? 0) * 100);
          return (
            <div key={c.signal} style={{
              display: 'grid', gridTemplateColumns: '160px 1fr 80px 80px',
              alignItems: 'center', gap: 12, padding: '6px 0',
              borderBottom: '1px solid var(--bd-subtle)',
            }}>
              <div style={{ fontSize: 12, color: 'var(--tx-secondary)' }}>
                {meta.label ?? c.signal}
                {c.inverse && (
                  <span style={{ marginLeft: 6, fontSize: 9, color: 'var(--tx-disabled)' }}>
                    (ters)
                  </span>
                )}
              </div>
              <div style={{ position: 'relative', height: 8, background: 'var(--bg-base)', borderRadius: 4, overflow: 'hidden' }}>
                <div style={{
                  width: `${pct}%`, height: '100%',
                  background: 'var(--accent)',
                  transition: 'width 200ms',
                }} />
              </div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--tx-tertiary)', textAlign: 'right' }}>
                p{pct}
              </div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--tx-secondary)', textAlign: 'right' }}>
                {meta.format ? meta.format(c.raw_value) : c.raw_value}
              </div>
            </div>
          );
        })}
      </div>

      {row.lat && row.lng && (
        <div style={{ fontSize: 11, color: 'var(--tx-disabled)', fontFamily: 'var(--font-mono)' }}>
          {row.lat.toFixed(4)}, {row.lng.toFixed(4)} · ID: {row.station_id}
        </div>
      )}
    </div>
  );
}

/* ── Summary stats + score histogram (sanity-check view) ───────────── */
function SummaryView({ summary }) {
  if (!summary) return <div className="loading">Yükleniyor…</div>;
  const stats = summary.score_stats ?? {};
  const hist  = summary.score_histogram ?? [];
  const growth = summary.growth_potential_counts ?? {};
  const agree  = summary.agreement_counts ?? {};
  const totalScored = stats.count ?? 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      {/* Stats row */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <MetricCard label="Skorlanan İstasyon" value={totalScored.toLocaleString()} />
        <MetricCard label="Min" value={stats.min?.toFixed(1) ?? '—'} />
        <MetricCard label="Ort." value={stats.avg?.toFixed(1) ?? '—'} />
        <MetricCard label="Max" value={stats.max?.toFixed(1) ?? '—'} />
        <MetricCard label="Std. Sapma" value={stats.stddev?.toFixed(1) ?? '—'} />
      </div>

      {/* Histogram */}
      <div>
        <SectionLabel>Skor Dağılımı (0-100, 10'luk kovalar)</SectionLabel>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={hist} margin={{ top: 8, right: 24, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" vertical={false} />
            <XAxis dataKey="bucket"
              tick={{ fill: 'var(--tx-tertiary)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
              axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
              axisLine={false} tickLine={false} allowDecimals={false} />
            <Tooltip
              formatter={(v) => [`${v} istasyon`, 'sayı']}
              labelFormatter={l => `${l}-${l + 10} skor aralığı`}
              {...TOOLTIP_STYLE}
            />
            <Bar dataKey="count" fill="var(--accent)" radius={[3, 3, 0, 0]} maxBarSize={40} />
          </BarChart>
        </ResponsiveContainer>
        <div style={{ fontSize: 10, color: 'var(--tx-disabled)', marginTop: 6, lineHeight: 1.5 }}>
          Persentil sıralamasıyla üretilen skor için dağılım yaklaşık düzgün (uniform) olmalıdır.
          Tek bir kovaya yığılma sinyallerin bilgisiz/korelasyonlu olduğuna işaret eder.
        </div>
      </div>

      {/* Growth + Agreement distributions side-by-side */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 18 }}>
        <DistributionList title="Büyüme Potansiyeli Dağılımı" data={growth}
          order={['HIGH', 'MEDIUM', 'LOW', 'MINIMAL']} labelMap={GROWTH_LABEL} colorFn={growthColor} />
        <DistributionList title="Kural Tabanlı Sistem ile Mutabakat" data={agree}
          order={['AGREE', 'NEAR', 'DISAGREE', 'UNKNOWN']} labelMap={AGREE_LABEL}
          colorFn={k => AGREE_COLORS[k] ?? 'var(--tx-tertiary)'} />
      </div>
    </div>
  );
}

function DistributionList({ title, data, order, labelMap, colorFn }) {
  const total = Object.values(data).reduce((s, v) => s + v, 0);
  if (!total) return null;
  return (
    <div>
      <SectionLabel>{title}</SectionLabel>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {order.filter(k => data[k]).map(k => {
          const v = data[k];
          const pct = ((v / total) * 100).toFixed(1);
          const c = colorFn(k);
          return (
            <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: c, flexShrink: 0 }} />
              <div style={{ flex: 1, fontSize: 12, color: 'var(--tx-secondary)' }}>
                {labelMap[k] ?? k}
              </div>
              <div style={{ position: 'relative', flex: 2, height: 6, background: 'var(--bg-elevated)', borderRadius: 3, overflow: 'hidden' }}>
                <div style={{ width: `${pct}%`, height: '100%', background: c }} />
              </div>
              <div style={{
                fontFamily: 'var(--font-mono)', fontSize: 11, color: c, fontWeight: 600,
                minWidth: 44, textAlign: 'right',
              }}>
                {pct}%
              </div>
              <div style={{
                fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--tx-tertiary)',
                minWidth: 48, textAlign: 'right',
              }}>
                {v.toLocaleString()}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ── Agreement scatter (score vs demand_supply_ratio) ──────────────── */
function AgreementScatter({ points }) {
  const grouped = useMemo(() => {
    const groups = { AGREE: [], NEAR: [], DISAGREE: [], UNKNOWN: [] };
    for (const p of points ?? []) {
      const g = groups[p.agreement] ?? groups.UNKNOWN;
      g.push({
        x: p.existing_demand_supply_ratio ?? 0,
        y: p.opportunity_score ?? 0,
        name: p.station_name,
        city: p.city_name,
        rec:  p.existing_recommendation_code,
      });
    }
    return groups;
  }, [points]);

  if (!points) return <div className="loading">Yükleniyor…</div>;
  if (!points.length) return <EmptyState title="Veri yok" />;

  return (
    <div>
      <ResponsiveContainer width="100%" height={420}>
        <ScatterChart margin={{ top: 16, right: 24, left: 8, bottom: 24 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" />
          <XAxis
            type="number" dataKey="x" name="D/S Oranı"
            domain={[0, 100]}
            tick={{ fill: 'var(--tx-tertiary)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
            axisLine={{ stroke: 'var(--bd-subtle)' }} tickLine={false}
            label={{ value: 'Kural Tabanlı Demand/Supply Oranı →', position: 'insideBottom',
              offset: -10, fill: 'var(--tx-disabled)', fontSize: 11 }}
          />
          <YAxis
            type="number" dataKey="y" name="Opportunity"
            domain={[0, 100]}
            tick={{ fill: 'var(--tx-tertiary)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
            axisLine={{ stroke: 'var(--bd-subtle)' }} tickLine={false}
            label={{ value: 'Opportunity Skoru ↑', angle: -90, position: 'insideLeft',
              offset: 12, fill: 'var(--tx-disabled)', fontSize: 11 }}
          />
          <ZAxis range={[20, 20]} />
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
                    Opp: {p.y?.toFixed(1)} · D/S: {p.x?.toFixed(1)}
                  </div>
                  {p.rec && <div style={{ fontSize: 10, color: 'var(--tx-disabled)' }}>{p.rec}</div>}
                </div>
              );
            }}
          />
          {Object.entries(grouped).map(([key, data]) => data.length > 0 && (
            <Scatter
              key={key} name={AGREE_LABEL[key]} data={data}
              fill={AGREE_COLORS[key]}
              fillOpacity={key === 'DISAGREE' ? 0.85 : 0.45}
            />
          ))}
        </ScatterChart>
      </ResponsiveContainer>
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginTop: 10, justifyContent: 'center' }}>
        {Object.entries(AGREE_LABEL).map(([k, lbl]) => (
          <span key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--tx-secondary)' }}>
            <span style={{ width: 8, height: 8, borderRadius: 4, background: AGREE_COLORS[k] }} />
            {lbl}
          </span>
        ))}
      </div>
      <div style={{ fontSize: 10, color: 'var(--tx-disabled)', marginTop: 10, lineHeight: 1.5, textAlign: 'center' }}>
        Yeşil noktalar (AGREE) modelin aynı tier'da olduğu, sarı (NEAR) komşu tier'da olduğu istasyonlar — ikisi de "model tutarlı" sinyali.
        Kırmızı (DISAGREE) noktalar tier farkı ≥ 2 olan gerçek ayrışmalardır; analist için en değerli kümedir.
      </div>
    </div>
  );
}

/* ══ Main page ════════════════════════════════════════════════════════ */
const GROWTH_FILTER_OPTS = [
  { value: '',        label: 'Tümü',    color: 'var(--tx-tertiary)' },
  { value: 'HIGH',    label: 'Yüksek',  color: GROWTH_COLORS.HIGH },
  { value: 'MEDIUM',  label: 'Orta',    color: GROWTH_COLORS.MEDIUM },
  { value: 'LOW',     label: 'Düşük',   color: GROWTH_COLORS.LOW },
  { value: 'MINIMAL', label: 'Minimal', color: GROWTH_COLORS.MINIMAL },
];

export default function ZesOpportunityPage({ date, onDateChange }) {
  const [dateConfirmed, setDateConfirmed] = useState(!!date);
  const [chartIndex,    setChartIndex]    = useState(0);

  const [topRows,    setTopRows]    = useState(null);
  const [bottomRows, setBottomRows] = useState(null);
  const [summary,    setSummary]    = useState(null);
  const [scatter,    setScatter]    = useState(null);

  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);

  const [growthFilter, setGrowthFilter] = useState('');
  const [cityFilter,   setCityFilter]   = useState('');

  const loadData = useCallback(async () => {
    if (!dateConfirmed) return;
    setLoading(true);
    setError(null);
    try {
      const [topData, bottomData, summaryData, scatterData] = await Promise.all([
        fetchOpportunityTop({ date, size: 50, growth: growthFilter || undefined, city: cityFilter || undefined, order: 'desc' }),
        fetchOpportunityTop({ date, size: 20, city: cityFilter || undefined, order: 'asc' }),
        fetchOpportunitySummary({ date }),
        fetchOpportunityScatter({ date, size: 2000 }),
      ]);
      setTopRows(topData);
      setBottomRows(bottomData);
      setSummary(summaryData);
      setScatter(scatterData);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, dateConfirmed, growthFilter, cityFilter]);

  useEffect(() => { loadData(); }, [loadData]);

  function handleDateSelect(d) {
    onDateChange(d);
    setDateConfirmed(true);
  }

  const charts = [
    {
      title: 'Büyüme Fırsatı — Top 50',
      subtitle: 'opportunity_score desc · bileşen kırılımı satır içinde',
      node: <HotList rows={topRows} />,
    },
    {
      title: 'Gözden Çıkarılabilir — Bottom 20',
      subtitle: 'opportunity_score asc · en düşük potansiyelli istasyonlar',
      node: <HotList rows={bottomRows} />,
    },
    {
      title: 'Skor Sağlığı / Sanity-Check',
      subtitle: 'dağılımlar, kural sistemi mutabakatı, skor histogramı',
      node: <SummaryView summary={summary} />,
    },
    {
      title: 'Mutabakat Scatter',
      subtitle: 'opportunity_score vs kural tabanlı demand_supply_ratio',
      node: <AgreementScatter points={scatter} />,
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

  const isListView = chartIndex === 0 || chartIndex === 1;

  const chartControls = (
    <>
      {chartIndex === 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 10, color: 'var(--tx-disabled)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
            Potansiyel:
          </span>
          {GROWTH_FILTER_OPTS.map(o => (
            <FilterChip
              key={o.value}
              label={o.label}
              color={o.color}
              active={growthFilter === o.value}
              onClick={() => setGrowthFilter(o.value)}
            />
          ))}
        </div>
      )}
      {isListView && (
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
        fetchDates={fetchOpportunityAvailableDates}
        label="Opportunity Tarihi Seç"
      />

      {!dateConfirmed ? (
        <EmptyState icon="🌱" title="Tarih seçin"
          description="Yukarıdaki takvimden zes_station_opportunity index'i bulunan bir tarih seçin." />
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
            minHeight={420}
          >
            {loading ? <div className="loading">Yükleniyor…</div> : current.node}
          </ChartCard>
        </>
      )}
    </>
  );
}
