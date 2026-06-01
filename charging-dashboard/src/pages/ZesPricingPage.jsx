import { useState, useEffect, useCallback } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
} from 'recharts';
import {
  fetchPricingSummary,
  fetchPricingRecommendations,
  fetchPricingHeatmap,
  fetchPricingAvailableDates,
} from '../api/zesApi';
import {
  Button, ChartCard, EmptyState, MetricCard,
  TOOLTIP_STYLE, SectionLabel, SegmentedControl,
} from '../components/ui';
import DateCalendar from '../components/DateCalendar';

const PAGE_SIZE = 20;
const SEGMENTS = [{ value: 'DC', label: 'DC (Hızlı)' }, { value: 'AC', label: 'AC' }];

const upliftColor = (v) =>
  v == null ? 'var(--tx-disabled)' : v > 0 ? 'var(--clr-success-text)' : 'var(--clr-danger-text)';

/* ── Discount cell color: single-tone accent ramp by discount fraction ── */
function discountColor(d) {
  if (!d || d <= 0) return 'var(--bg-elevated)';
  const alpha = 0.12 + Math.min(d / 0.20, 1) * 0.8;  // 5%→light, 20%→strong
  return `rgba(99, 102, 241, ${alpha.toFixed(2)})`;   // accent indigo
}

/* ════════════════ Summary view ════════════════ */
function SummaryView({ summary }) {
  if (!summary) return <div className="loading">Yükleniyor…</div>;
  if (!summary.available) {
    return <EmptyState icon="💸" title="Fiyatlandırma verisi yok"
      description="Bu tarih için zes_station_pricing index'i bulunamadı. Önce FastAPI'da POST /pricing/run-all çalıştırın." />;
  }
  const up = summary.revenue_uplift_pct || {};
  const hist = summary.discount_histogram || {};
  const histData = Object.entries(hist)
    .map(([k, v]) => ({ tier: k, count: v }))
    .sort((a, b) => parseFloat(a.tier) - parseFloat(b.tier));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{
        padding: '8px 12px', fontSize: 12, color: 'var(--tx-tertiary)',
        background: 'var(--bg-elevated)', border: '1px solid var(--bd-subtle)',
        borderRadius: 'var(--rx-md)',
      }}>
        ⚠️ Gelir artışı bir <strong>simülasyondur</strong> (varsayılan elastikiyet altında).
        Tek bir sayı değil, <strong>low–high bandı</strong> ile yorumlayın.
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <MetricCard label="Gelir Artışı (merkez)" value={`${up.central >= 0 ? '+' : ''}${up.central ?? 0}%`}
          sub={`bant: ${up.low ?? 0}% … ${up.high ?? 0}%`} color={upliftColor(up.central)} />
        <MetricCard label="PAR (tepe/ortalama) Düşüşü" value={`${summary.par_reduction ?? 0}`}
          sub={`${summary.par_mean_baseline ?? 0} → ${summary.par_mean_policy ?? 0}`}
          color={summary.par_reduction > 0 ? 'var(--clr-success-text)' : 'var(--tx-secondary)'} />
        <MetricCard label="Ortalama İndirim" value={`${((summary.mean_discount ?? 0) * 100).toFixed(1)}%`}
          sub="bağlayıcı-ağırlıklı" />
        <MetricCard label="Şehir-Segment Hücresi" value={summary.n_city_segments ?? 0}
          sub={Object.entries(summary.segment_counts || {}).map(([k, v]) => `${k}:${v}`).join('  ')} />
      </div>

      <div>
        <SectionLabel>İndirim Kademesi Dağılımı (şehir × saat)</SectionLabel>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={histData} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" />
            <XAxis dataKey="tier" tick={{ fontSize: 11, fill: 'var(--tx-tertiary)' }} />
            <YAxis tick={{ fontSize: 11, fill: 'var(--tx-tertiary)' }} />
            <Tooltip contentStyle={TOOLTIP_STYLE} cursor={{ fill: 'var(--bg-highlight)' }} />
            <Bar dataKey="count" radius={[3, 3, 0, 0]}>
              {histData.map((e, i) => (
                <Cell key={i} fill={discountColor(parseFloat(e.tier) / 100)} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

/* ════════════════ Discount heatmap (city × hour) ════════════════ */
function HeatmapView({ rows }) {
  if (!rows) return <div className="loading">Yükleniyor…</div>;
  if (!rows.length) return <EmptyState title="Veri yok" description="Bu segment için öneri yok" />;
  const hours = Array.from({ length: 24 }, (_, h) => h);
  // Show the busiest cities first (already sorted by mean_discount server-side); cap for readability.
  const shown = rows.slice(0, 30);

  return (
    <div className="table-wrapper" style={{ maxHeight: 480, overflow: 'auto' }}>
      <table style={{ borderCollapse: 'collapse', fontSize: 11 }}>
        <thead>
          <tr>
            <th style={{ position: 'sticky', left: 0, background: 'var(--bg-surface)', textAlign: 'left', padding: '4px 8px', zIndex: 1 }}>Şehir</th>
            {hours.map(h => (
              <th key={h} style={{ padding: '2px 0', width: 20, color: 'var(--tx-disabled)', fontWeight: 500 }}>{h}</th>
            ))}
            <th style={{ padding: '4px 8px', textAlign: 'right' }}>Δ Gelir</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((r) => {
            const disc = r.recommended_discount || [];
            return (
              <tr key={`${r.city_name}_${r.segment}`}>
                <td style={{ position: 'sticky', left: 0, background: 'var(--bg-surface)', padding: '3px 8px', whiteSpace: 'nowrap', maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {r.city_name}
                </td>
                {hours.map(h => {
                  const d = disc[h] ?? 0;
                  return (
                    <td key={h} title={`${r.city_name} ${h}:00 — %${(d * 100).toFixed(0)} indirim`}
                      style={{ background: discountColor(d), width: 20, height: 20, border: '1px solid var(--bg-base)' }} />
                  );
                })}
                <td style={{ padding: '3px 8px', textAlign: 'right', fontFamily: 'var(--font-mono)', color: upliftColor(r.revenue_uplift_pct) }}>
                  {r.revenue_uplift_pct >= 0 ? '+' : ''}{r.revenue_uplift_pct ?? 0}%
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/* ════════════════ Recommendations table ════════════════ */
function RecommendationsView({ result, page, onPageChange }) {
  if (!result) return <div className="loading">Yükleniyor…</div>;
  const { total = 0, rows = [] } = result;
  if (!rows.length) return <EmptyState title="Öneri yok" description="Filtreyi gevşetin" />;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="table-wrapper" style={{ maxHeight: 460, overflowY: 'auto' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th>Şehir</th>
              <th style={{ width: 60 }}>Segment</th>
              <th style={{ width: 90, textAlign: 'right' }}>Ort. İndirim</th>
              <th style={{ width: 90, textAlign: 'right' }}>İndirimli Saat</th>
              <th style={{ width: 100, textAlign: 'right' }}>Δ Gelir (merkez)</th>
              <th style={{ width: 110, textAlign: 'right' }}>Bant</th>
              <th style={{ width: 70, textAlign: 'right' }}>Bağlayıcı</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.city_name}_${r.segment}`}>
                <td>{r.city_name}</td>
                <td><span className="badge">{r.segment}</span></td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)' }}>{((r.mean_discount ?? 0) * 100).toFixed(1)}%</td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)' }}>{r.pct_hours_discounted ?? 0}%</td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', color: upliftColor(r.revenue_uplift_pct) }}>
                  {r.revenue_uplift_pct >= 0 ? '+' : ''}{r.revenue_uplift_pct ?? 0}%
                </td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--tx-tertiary)' }}>
                  {r.revenue_uplift_pct_low ?? 0} … {r.revenue_uplift_pct_high ?? 0}
                </td>
                <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)' }}>{r.connector_count ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 12, color: 'var(--tx-tertiary)' }}>
        <span>{total} şehir-segment · sayfa {page + 1}/{totalPages}</span>
        <div style={{ display: 'flex', gap: 6 }}>
          <Button size="sm" variant="secondary" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>‹ Önceki</Button>
          <Button size="sm" variant="secondary" disabled={page >= totalPages - 1} onClick={() => onPageChange(page + 1)}>Sonraki ›</Button>
        </div>
      </div>
    </div>
  );
}

/* ════════════════ Page ════════════════ */
export default function ZesPricingPage({ date, onDateChange }) {
  const [dateConfirmed, setDateConfirmed] = useState(!!date);
  const [chartIndex, setChartIndex] = useState(0);
  const [segment, setSegment] = useState('DC');
  const [page, setPage] = useState(0);

  const [summary, setSummary] = useState(null);
  const [heatmap, setHeatmap] = useState(null);
  const [recs, setRecs] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => { setPage(0); }, [segment]);

  const loadData = useCallback(async () => {
    if (!dateConfirmed) return;
    setLoading(true); setError(null);
    try {
      const [s, h, r] = await Promise.all([
        fetchPricingSummary({ date }),
        fetchPricingHeatmap({ date, segment }),
        fetchPricingRecommendations({ date, from: page * PAGE_SIZE, size: PAGE_SIZE, segment, sort: 'uplift', order: 'desc' }),
      ]);
      setSummary(s); setHeatmap(h); setRecs(r);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, dateConfirmed, segment, page]);

  useEffect(() => { loadData(); }, [loadData]);

  function handleDateSelect(d) { onDateChange(d); setDateConfirmed(true); }

  const charts = [
    { title: 'Fiyatlandırma Özeti', subtitle: 'gelir artışı (± bant) · profil düzleşmesi (PAR)', node: <SummaryView summary={summary} /> },
    { title: 'İndirim Isı Haritası', subtitle: 'şehir × saat · koyu = yüksek indirim', node: <HeatmapView rows={heatmap} /> },
    { title: 'Şehir-Segment Önerileri', subtitle: 'gelir artışına göre sıralı', node: <RecommendationsView result={recs} page={page} onPageChange={setPage} /> },
  ];
  const total = charts.length;
  const prev = () => setChartIndex(i => (i - 1 + total) % total);
  const next = () => setChartIndex(i => (i + 1) % total);
  const current = charts[chartIndex];

  const chartControls = (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 10, color: 'var(--tx-disabled)', textTransform: 'uppercase', letterSpacing: '0.06em' }}>Segment:</span>
        <SegmentedControl options={SEGMENTS} value={segment} onChange={setSegment} />
      </div>
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
        fetchDates={fetchPricingAvailableDates}
        label="Fiyatlandırma Tarihi Seç"
      />
      {!dateConfirmed ? (
        <EmptyState icon="💸" title="Tarih seçin"
          description="zes_station_pricing index'i bulunan bir tarih seçin. (FastAPI'da POST /pricing/run-all çalıştırılmış olmalı.)" />
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
