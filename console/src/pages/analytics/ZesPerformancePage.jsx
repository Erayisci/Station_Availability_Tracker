import { useState, useEffect, useCallback } from 'react';
import {
  PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
} from 'recharts';
import {
  fetchStationPerformanceRecommendationDistribution,
  fetchStationPerformanceDemandSupplyMatrix,
  fetchStationPerformanceUnderutilized,
  fetchStationPerformanceOverutilized,
  fetchStationPerformanceSearch,
  fetchStationPerformanceAvailableDates,
} from '../../api/zesApi';
import {
  Badge, UtilBar, Button, FilterChip, ChartCard,
  EmptyState, MetricCard, TOOLTIP_STYLE, SectionLabel, InfoTooltip,
} from '../../components/ui';
import PerformanceBackfillModal from '../../components/PerformanceBackfillModal';
import DateCalendar             from '../../components/DateCalendar';
import { REC_META, recLabel } from '../../lib/recommendations';

/* ── Tooltip helpers ────────────────────────────────────────────── */
function TTSection({ title, children }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ fontSize: 9, fontWeight: 700, color: '#818cf8', textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 5 }}>
        {title}
      </div>
      {children}
    </div>
  );
}

function TTFormula({ lines }) {
  return (
    <div style={{
      background: 'rgba(0,0,0,0.3)', borderRadius: 5, padding: '7px 10px',
      fontFamily: 'var(--font-mono)', fontSize: 10.5, color: '#cbd5e1',
      lineHeight: 1.85, borderLeft: '2px solid #818cf8', margin: '4px 0',
    }}>
      {lines.map((l, i) => <div key={i}>{l}</div>)}
    </div>
  );
}

function TTVar({ sym, desc }) {
  return (
    <div style={{ display: 'flex', gap: 8, marginTop: 2, alignItems: 'flex-start' }}>
      <code style={{ fontFamily: 'var(--font-mono)', color: '#818cf8', fontSize: 9.5, minWidth: 96, flexShrink: 0, paddingTop: 1 }}>{sym}</code>
      <span style={{ color: '#64748b', fontSize: 9.5, lineHeight: 1.4 }}>{desc}</span>
    </div>
  );
}

function TTTier({ color, name, cond }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 3 }}>
      <span style={{ width: 6, height: 6, borderRadius: 2, background: color, flexShrink: 0 }} />
      <code style={{ fontFamily: 'var(--font-mono)', color, fontSize: 9.5, minWidth: 72, flexShrink: 0 }}>{name}</code>
      <span style={{ color: '#94a3b8', fontSize: 9.5 }}>{cond}</span>
    </div>
  );
}

function TTTitle({ children }) {
  return (
    <div style={{ fontSize: 11.5, fontWeight: 600, color: '#e2e8f0', marginBottom: 9, borderBottom: '1px solid rgba(255,255,255,0.07)', paddingBottom: 7 }}>
      {children}
    </div>
  );
}

/* ── Tooltip content: Demand Tier ───────────────────────────────── */
function DemandTierContent() {
  return (
    <div>
      <TTTitle>Talep Seviyesi — Hesaplama Yöntemi</TTTitle>

      <TTSection title="① Şarj Kullanım Oranı  ( U )">
        <TTFormula lines={[
          'U = min(100,  (Σ t_şarj / (N_soket × 1440)) × 100)',
        ]} />
        <TTVar sym="Σ t_şarj"  desc="Günlük toplam şarj süresi (dakika)" />
        <TTVar sym="N_soket"   desc="İstasyondaki konnektör sayısı" />
        <TTVar sym="1440"      desc="Dakika/gün sabiti — 24 × 60" />
        <TTVar sym="min(·,100)" desc="Tavan — %100'ü aşamaz" />
      </TTSection>

      <TTSection title="② Sınıflandırma Eşikleri">
        <TTTier color="var(--clr-danger-text)"  name="HIGH"   cond="U ≥ 40%" />
        <TTTier color="var(--clr-warning-text)" name="MEDIUM" cond="15% ≤ U < 40%" />
        <TTTier color="var(--clr-success-text)" name="LOW"    cond="U < 15%" />
      </TTSection>
    </div>
  );
}

/* ── Tooltip content: Supply Tier ───────────────────────────────── */
function SupplyTierContent() {
  return (
    <div>
      <TTTitle>Arz Seviyesi — Hesaplama Yöntemi</TTTitle>

      <TTSection title="① Haversine Mesafe Formülü  ( d )">
        <TTFormula lines={[
          'd = 2R · arctan2(√a,  √(1−a))',
          'a = sin²(Δφ/2) + cos(φ₁)·cos(φ₂)·sin²(Δλ/2)',
          'R = 6.371 × 10³ km',
        ]} />
        <TTVar sym="φ, λ"   desc="Enlem / boylam (radyan)" />
        <TTVar sym="Δφ, Δλ" desc="İki istasyon arası fark" />
        <TTVar sym="R"       desc="Dünya yarıçapı sabiti" />
      </TTSection>

      <TTSection title="② Yakın İstasyon Ort. Kullanımı  ( U_ort )">
        <TTFormula lines={[
          'U_ort = (Σ U_i) / N,   ∀ i : d(i,  istasyon) ≤ 1.0 km',
        ]} />
        <TTVar sym="U_i" desc="i. yakın istasyonun kullanım oranı (%)" />
        <TTVar sym="N"   desc="Arama yarıçapı içindeki istasyon sayısı" />
        <TTVar sym="r"   desc="Arama yarıçapı — 1.0 km (sabit)" />
      </TTSection>

      <TTSection title="③ Efektif Arz  ( S_eff )">
        <TTFormula lines={[
          'S_eff = N_yakın × (1 − U_ort / 100)',
          '      = N_yakın        (fallback: U_ort mevcut değilse)',
        ]} />
        <TTVar sym="N_yakın" desc="1 km içindeki istasyon sayısı" />
        <TTVar sym="U_ort"   desc="Yakın istasyonların ort. kullanımı (%)" />
      </TTSection>

      <TTSection title="④ Sınıflandırma Eşikleri">
        <TTTier color="var(--clr-danger-text)"  name="FEW"      cond="S_eff ≤ 2.0" />
        <TTTier color="var(--clr-warning-text)" name="MODERATE" cond="2.0 < S_eff ≤ 5.0" />
        <TTTier color="var(--clr-success-text)" name="MANY"     cond="S_eff > 5.0" />
        <TTTier color="#475569"                 name="UNKNOWN"  cond="N_yakın < 0  (API hatası)" />
      </TTSection>
    </div>
  );
}

/* ── Tooltip content: Priority Score ────────────────────────────── */
function PriorityScoreContent() {
  return (
    <div>
      <TTTitle>Öncelik Skoru — Hesaplama Yöntemi</TTTitle>

      <TTSection title="① Formül  ( P )">
        <TTFormula lines={[
          'P = min(100,  U / max(S_eff,  1.0))',
        ]} />
        <TTVar sym="U"           desc="charging_utilization_pct  (%)" />
        <TTVar sym="S_eff"       desc="effective_supply_rate" />
        <TTVar sym="max(·, 1.0)" desc="Zemin — sıfıra bölünmeyi önler" />
        <TTVar sym="min(·, 100)" desc="Tavan — normalize edilmiş skor" />
      </TTSection>

      <TTSection title="② Renk Skalası">
        <TTTier color="var(--clr-danger-text)"  name="P ≥ 40" cond="Yüksek Öncelik" />
        <TTTier color="var(--clr-warning-text)" name="P ≥ 15" cond="Orta Öncelik" />
        <TTTier color="var(--clr-success-text)" name="P < 15" cond="Düşük Öncelik" />
      </TTSection>
    </div>
  );
}

/* ── Reusable TH content with InfoTooltip ───────────────────────── */
const TH_DEMAND = (
  <span style={{ display: 'inline-flex', alignItems: 'center' }}>
    Talep<InfoTooltip width={360}><DemandTierContent /></InfoTooltip>
  </span>
);
const TH_SUPPLY = (
  <span style={{ display: 'inline-flex', alignItems: 'center' }}>
    Arz<InfoTooltip width={410}><SupplyTierContent /></InfoTooltip>
  </span>
);
const TH_PRIORITY = (
  <span style={{ display: 'inline-flex', alignItems: 'center' }}>
    Öncelik<InfoTooltip width={310}><PriorityScoreContent /></InfoTooltip>
  </span>
);

/* ── Priority score rengi (0-100 arası skor) ────────────────────── */
const priorityColor = score => {
  if (score == null) return 'var(--tx-disabled)';
  if (score >= 40) return 'var(--clr-danger-text)';
  if (score >= 15) return 'var(--clr-warning-text)';
  return 'var(--clr-success-text)';
};

/* ── Semantic color maps ────────────────────────────────────────── */
const REC_COLORS = {
  EXPAND:              '#ef4444',
  INCREASE_CAPACITY:   '#f97316',
  OPTIMIZE:            '#f59e0b',
  MONITOR_GROWTH:      '#94a3b8',
  MAINTAIN:            '#10b981',
  IMPROVE_EFFICIENCY:  '#3b82f6',
  MONITOR:             '#64748b',
  REVIEW:              '#8b5cf6',
  RELOCATE:            '#ef4444',
  INSUFFICIENT_DATA:   '#293d52',
};
const SUPPLY_COLORS = { FEW: '#ef4444', MODERATE: '#f59e0b', MANY: '#10b981', UNKNOWN: '#475569' };

const recColor    = code => REC_COLORS[code]    ?? '#64748b';
const supplyColor = tier => SUPPLY_COLORS[tier] ?? '#64748b';

/* ── Recommendation Donut + Legend ─────────────────────────────── */
function RecommendationChart({ data }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;
  const entries = Object.entries(data)
    .map(([name, value]) => ({ name, value }))
    .filter(e => e.value > 0)
    .sort((a, b) => b.value - a.value);
  if (!entries.length) return <EmptyState title="Veri yok" />;
  const total = entries.reduce((s, e) => s + e.value, 0);

  return (
    <div style={{ display: 'flex', gap: 24, alignItems: 'center', height: 320, flexWrap: 'wrap' }}>
      <ResponsiveContainer width={280} height={280}>
        <PieChart>
          <Pie
            data={entries} dataKey="value" nameKey="name"
            cx="50%" cy="50%" innerRadius={70} outerRadius={110}
            paddingAngle={2} strokeWidth={0}
          >
            {entries.map(e => <Cell key={e.name} fill={recColor(e.name)} />)}
          </Pie>
          <Tooltip
            formatter={(v, name) => [`${v.toLocaleString()} istasyon (${((v/total)*100).toFixed(1)}%)`, name]}
            {...TOOLTIP_STYLE}
          />
        </PieChart>
      </ResponsiveContainer>

      {/* Side legend */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6, minWidth: 200 }}>
        {entries.map(e => {
          const pct  = ((e.value / total) * 100).toFixed(1);
          const c    = recColor(e.name);
          const meta = REC_META[e.name];
          return (
            <div key={e.name} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: c, flexShrink: 0 }} />
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 12, color: 'var(--tx-secondary)', fontWeight: 600, lineHeight: 1.3 }}>
                  {meta?.label ?? e.name}
                </div>
                {meta && (
                  <div style={{ fontSize: 10, color: 'var(--tx-disabled)', lineHeight: 1.2 }}>
                    T:{meta.demand} · A:{meta.supply}
                  </div>
                )}
              </div>
              <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: c, fontWeight: 600, minWidth: 44, textAlign: 'right' }}>
                {pct}%
              </span>
              <span style={{ fontSize: 11, color: 'var(--tx-tertiary)', minWidth: 56, textAlign: 'right', fontFamily: 'var(--font-mono)' }}>
                {e.value.toLocaleString()}
              </span>
            </div>
          );
        })}
        <div style={{ borderTop: '1px solid var(--bd-subtle)', marginTop: 4, paddingTop: 6, display: 'flex', justifyContent: 'space-between' }}>
          <span style={{ fontSize: 12, color: 'var(--tx-tertiary)', fontWeight: 600 }}>Toplam</span>
          <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: 'var(--tx-primary)', fontWeight: 700 }}>
            {total.toLocaleString()}
          </span>
        </div>
      </div>
    </div>
  );
}

/* ── Demand × Supply Matrix ─────────────────────────────────────── */
const SUPPLY_TIERS = ['FEW', 'MODERATE', 'MANY', 'UNKNOWN'];

const DEMAND_LABELS = { HIGH: 'Yüksek Talep', MEDIUM: 'Orta Talep', LOW: 'Düşük Talep' };
const SUPPLY_LABELS      = { FEW: 'Az ZES Arzı', MODERATE: 'Orta ZES Arzı', MANY: 'Çok ZES Arzı', UNKNOWN: 'Bilinmiyor' };
const SUPPLY_LABELS_SHORT = { FEW: 'Az Arz', MODERATE: 'Orta Arz', MANY: 'Çok Arz', UNKNOWN: 'Bilinmiyor' };

function DemandSupplyChart({ data }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;
  const entries = Object.entries(data).map(([demand, supplyMap]) => {
    const row = { demand, demandLabel: DEMAND_LABELS[demand] ?? demand };
    for (const st of SUPPLY_TIERS) row[st] = supplyMap[st] ?? 0;
    return row;
  });
  if (!entries.length) return <EmptyState title="Veri yok" />;

  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={entries} margin={{ top: 8, right: 24, left: 0, bottom: 5 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--bd-subtle)" vertical={false} />
        <XAxis
          dataKey="demand"
          tickFormatter={v => DEMAND_LABELS[v] ?? v}
          tick={{ fill: 'var(--tx-secondary)', fontSize: 11, fontWeight: 600 }}
          axisLine={false} tickLine={false}
        />
        <YAxis
          tick={{ fill: 'var(--tx-tertiary)', fontSize: 11 }}
          axisLine={false} tickLine={false} allowDecimals={false}
          label={{ value: 'İstasyon Sayısı', angle: -90, position: 'insideLeft', offset: 12, fill: 'var(--tx-disabled)', fontSize: 11 }}
        />
        <Tooltip
          formatter={(v, name) => [v.toLocaleString() + ' istasyon', SUPPLY_LABELS[name] ?? name]}
          labelFormatter={label => DEMAND_LABELS[label] ?? label}
          {...TOOLTIP_STYLE}
        />
        <Legend
          wrapperStyle={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'center', gap: 4, paddingTop: 8 }}
          formatter={v => (
            <span style={{ color: supplyColor(v), fontSize: 11, fontWeight: 500 }}>
              {SUPPLY_LABELS_SHORT[v] ?? v}
            </span>
          )}
        />
        {SUPPLY_TIERS.map(st => (
          <Bar key={st} dataKey={st} fill={supplyColor(st)} radius={[3, 3, 0, 0]} maxBarSize={40} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  );
}

/* ── Underutilized table ────────────────────────────────────────── */
function UnderutilizedTable({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows) return <div className="loading">Yükleniyor…</div>;
  if (!rows.length) return <EmptyState title="Veri yok" />;

  return (
    <div className="table-wrapper" style={{ maxHeight: 340, overflowY: 'auto' }}>
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 32 }} />
            <th>İstasyon</th>
            <th>Şehir</th>
            <th>{TH_DEMAND}</th>
            <th>{TH_SUPPLY}</th>
            <th>Kullanım</th>
            <th>{TH_PRIORITY}</th>
            <th>Tavsiye</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const isOpen = expanded === i;
            return (
              <>
                <tr
                  key={`r-${i}`}
                  onClick={() => setExpanded(p => p === i ? null : i)}
                  style={{ cursor: 'pointer' }}
                >
                  <td style={{ textAlign: 'center', color: 'var(--tx-disabled)', fontSize: 11, userSelect: 'none' }}>
                    <span style={{ display: 'inline-block', transition: 'transform 150ms', transform: isOpen ? 'rotate(90deg)' : 'none' }}>▶</span>
                  </td>
                  <td style={{ fontWeight: 600, color: 'var(--tx-primary)', maxWidth: 200 }}>{r.station_name ?? '—'}</td>
                  <td style={{ color: 'var(--tx-secondary)' }}>{r.city_name ?? '—'}</td>
                  <td><Badge value={r.demand_tier} /></td>
                  <td><Badge value={r.supply_tier} /></td>
                  <td><UtilBar pct={r.charging_utilization_pct} /></td>
                  <td style={{ textAlign: 'right', color: priorityColor(r.demand_supply_ratio), fontWeight: 600, fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                    {r.demand_supply_ratio != null ? Number(r.demand_supply_ratio).toFixed(1) : '—'}
                  </td>
                  <td><Badge value={r.recommendation_code} label={recLabel(r.recommendation_code)} /></td>
                </tr>
                {isOpen && (
                  <tr key={`expand-${i}`}>
                    <td />
                    <td colSpan={7} style={{ background: 'var(--bg-elevated)', padding: '12px 16px' }}>
                      <ExpandedRow r={r} />
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

/* ── Overutilized table ─────────────────────────────────────────── */
function OverutilizedTable({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows) return <div className="loading">Yükleniyor…</div>;
  if (!rows.length) return <EmptyState title="Veri yok" />;

  return (
    <div className="table-wrapper" style={{ maxHeight: 340, overflowY: 'auto' }}>
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 32 }} />
            <th>İstasyon</th>
            <th>Şehir</th>
            <th>{TH_DEMAND}</th>
            <th>{TH_SUPPLY}</th>
            <th>Kullanım</th>
            <th>{TH_PRIORITY}</th>
            <th>Yakın Soket</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const isOpen = expanded === i;
            return (
              <>
                <tr
                  key={`ov-${i}`}
                  onClick={() => setExpanded(p => p === i ? null : i)}
                  style={{ cursor: 'pointer' }}
                >
                  <td style={{ textAlign: 'center', color: 'var(--tx-disabled)', fontSize: 11, userSelect: 'none' }}>
                    <span style={{ display: 'inline-block', transition: 'transform 150ms', transform: isOpen ? 'rotate(90deg)' : 'none' }}>▶</span>
                  </td>
                  <td style={{ fontWeight: 600, color: 'var(--tx-primary)', maxWidth: 200 }}>{r.station_name ?? '—'}</td>
                  <td style={{ color: 'var(--tx-secondary)' }}>{r.city_name ?? '—'}</td>
                  <td><Badge value={r.demand_tier} /></td>
                  <td><Badge value={r.supply_tier} /></td>
                  <td><UtilBar pct={r.charging_utilization_pct} /></td>
                  <td style={{ textAlign: 'right', color: priorityColor(r.demand_supply_ratio), fontWeight: 700, fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                    {r.demand_supply_ratio != null ? Number(r.demand_supply_ratio).toFixed(1) : '—'}
                  </td>
                  <td style={{ textAlign: 'center', color: 'var(--tx-secondary)', fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                    {r.nearby_connectors_count ?? '—'}
                  </td>
                </tr>
                {isOpen && (
                  <tr key={`ov-exp-${i}`}>
                    <td />
                    <td colSpan={7} style={{ background: 'var(--bg-elevated)', padding: '12px 16px' }}>
                      <ExpandedRow r={r} showCharging />
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

/* ── Search result table ────────────────────────────────────────── */
function SearchResultTable({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows || !rows.length) return <EmptyState title="Sonuç bulunamadı" description="Filtrelerinizi değiştirerek tekrar deneyin" />;

  return (
    <div className="table-wrapper">
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 32 }} />
            <th>İstasyon</th>
            <th>Şehir / İl</th>
            <th>{TH_DEMAND}</th>
            <th>{TH_SUPPLY}</th>
            <th>Kullanım</th>
            <th>{TH_PRIORITY}</th>
            <th>Soket</th>
            <th>Tavsiye</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const isOpen = expanded === i;
            return (
              <>
                <tr
                  key={`sr-${i}`}
                  onClick={() => setExpanded(p => p === i ? null : i)}
                  style={{ cursor: 'pointer' }}
                >
                  <td style={{ textAlign: 'center', color: 'var(--tx-disabled)', fontSize: 11, userSelect: 'none' }}>
                    <span style={{ display: 'inline-block', transition: 'transform 150ms', transform: isOpen ? 'rotate(90deg)' : 'none' }}>▶</span>
                  </td>
                  <td style={{ fontWeight: 600, color: 'var(--tx-primary)', maxWidth: 200 }}>{r.station_name ?? '—'}</td>
                  <td>{[r.city_name, r.province_name].filter(Boolean).join(' / ') || '—'}</td>
                  <td><Badge value={r.demand_tier} /></td>
                  <td><Badge value={r.supply_tier} /></td>
                  <td><UtilBar pct={r.charging_utilization_pct} /></td>
                  <td style={{ textAlign: 'right', color: priorityColor(r.demand_supply_ratio), fontFamily: 'var(--font-mono)', fontSize: 12, fontWeight: 600 }}>
                    {r.demand_supply_ratio != null ? Number(r.demand_supply_ratio).toFixed(1) : '—'}
                  </td>
                  <td style={{ textAlign: 'center', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{r.connector_count ?? '—'}</td>
                  <td><Badge value={r.recommendation_code} label={recLabel(r.recommendation_code)} /></td>
                </tr>
                {isOpen && (
                  <tr key={`sre-${i}`}>
                    <td />
                    <td colSpan={8} style={{ background: 'var(--bg-elevated)', padding: '12px 16px' }}>
                      <ExpandedRow r={r} showCharging />
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

/* ── Expanded row detail panel ──────────────────────────────────── */
function ExpandedRow({ r, showCharging }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {r.address && (
        <div style={{ fontSize: 12, color: 'var(--tx-secondary)' }}>
          <span style={{ color: 'var(--tx-tertiary)', fontWeight: 600 }}>Adres: </span>
          {r.address}
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <MetricCard label="Soket" value={r.connector_count ?? '—'} />
        <MetricCard label="Yakın İstasyon" value={r.nearby_stations_count ?? '—'} />
        <MetricCard label="Yakın Soket" value={r.nearby_connectors_count ?? '—'} />
        {showCharging && r.total_charging_minutes != null && (
          <MetricCard
            label="Toplam Şarj"
            value={`${(r.total_charging_minutes / 60).toFixed(1)} sa`}
            color="var(--clr-success-text)"
          />
        )}
      </div>
      {r.recommendation && (
        <div style={{
          fontSize: 12, padding: '8px 12px',
          background: `${recColor(r.recommendation_code)}12`,
          border: `1px solid ${recColor(r.recommendation_code)}30`,
          borderRadius: 'var(--rx-md)',
          color: recColor(r.recommendation_code),
          fontWeight: 500,
        }}>
          {r.recommendation}
        </div>
      )}
    </div>
  );
}


/* ── Filter options ─────────────────────────────────────────────── */
const DEMAND_OPTS = [
  { value: '',       label: 'Tümü',   color: '#64748b' },
  { value: 'HIGH',   label: 'Yüksek', color: '#ef4444' },
  { value: 'MEDIUM', label: 'Orta',   color: '#f59e0b' },
  { value: 'LOW',    label: 'Düşük',  color: '#10b981' },
];
const SUPPLY_OPTS = [
  { value: '',         label: 'Tümü', color: '#64748b' },
  { value: 'FEW',      label: 'Az',   color: '#ef4444' },
  { value: 'MODERATE', label: 'Orta', color: '#f59e0b' },
  { value: 'MANY',     label: 'Çok',  color: '#10b981' },
];
const SORT_OPTIONS = [
  { value: 'demand_supply_ratio',      label: 'Öncelik Skoru' },
  { value: 'charging_utilization_pct', label: 'Kullanım %' },
  { value: 'connector_count',          label: 'Soket Sayısı' },
  { value: 'nearby_stations_count',    label: 'Yakın İstasyon' },
];

const PAGE_SIZE = 20;

/* ══ Main Component ════════════════════════════════════════════════ */
export default function ZesPerformancePage({ date, onDateChange }) {
  const [dateConfirmed, setDateConfirmed] = useState(!!date);

  const [recDistData,    setRecDistData]    = useState(null);
  const [matrixData,     setMatrixData]     = useState(null);
  const [underutilData,  setUnderutilData]  = useState(null);
  const [overutilData,   setOverutilData]   = useState(null);
  const [statsLoading,  setStatsLoading]  = useState(false);
  const [statsError,    setStatsError]    = useState(null);
  const [chartIndex,    setChartIndex]    = useState(0);
  const [showBackfillModal, setShowBackfillModal] = useState(false);

  const [filters, setFilters] = useState({
    demandTier: '', supplyTier: '', recommendationCode: '',
    cityName: '', stationName: '', minUtilizationPct: '', maxUtilizationPct: '',
    sort: 'demand_supply_ratio', order: 'desc',
  });
  const [searchResults, setSearchResults] = useState(null);
  const [page,          setPage]          = useState(0);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError,   setSearchError]   = useState(null);
  const [searched,      setSearched]      = useState(false);

  const loadStats = useCallback(async () => {
    if (!dateConfirmed) return;
    setStatsLoading(true);
    setStatsError(null);
    try {
      const [rec, matrix, under, over] = await Promise.all([
        fetchStationPerformanceRecommendationDistribution({ date }),
        fetchStationPerformanceDemandSupplyMatrix({ date }),
        fetchStationPerformanceUnderutilized({ date, size: 20 }),
        fetchStationPerformanceOverutilized({ date, size: 20 }),
      ]);
      setRecDistData(rec);
      setMatrixData(matrix);
      setUnderutilData(under);
      setOverutilData(over);
    } catch (e) {
      setStatsError(e.message);
    } finally {
      setStatsLoading(false);
    }
  }, [date, dateConfirmed]);

  useEffect(() => { loadStats(); }, [loadStats]);

  const charts = [
    { title: 'Tavsiye Kodu Dağılımı',                  node: <RecommendationChart data={recDistData} /> },
    { title: 'Talep × Arz Matrisi',                    node: <DemandSupplyChart data={matrixData} /> },
    { title: 'Düşük Kullanımlı İstasyonlar — Top 20',  node: <UnderutilizedTable rows={underutilData} /> },
    { title: 'Yeni İstasyon Kurulması Gereken Bölgeler — Top 20', node: <OverutilizedTable rows={overutilData} /> },
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

  const doSearch = useCallback(async (pageOverride) => {
    const p = pageOverride ?? page;
    setSearchLoading(true);
    setSearchError(null);
    setSearched(true);
    try {
      const data = await fetchStationPerformanceSearch({
        date,
        demandTier:         filters.demandTier         || undefined,
        supplyTier:         filters.supplyTier         || undefined,
        recommendationCode: filters.recommendationCode || undefined,
        cityName:           filters.cityName           || undefined,
        stationName:        filters.stationName        || undefined,
        minUtilizationPct:  filters.minUtilizationPct !== '' ? Number(filters.minUtilizationPct) : undefined,
        maxUtilizationPct:  filters.maxUtilizationPct !== '' ? Number(filters.maxUtilizationPct) : undefined,
        sort:               filters.sort,
        order:              filters.order,
        from:               p * PAGE_SIZE,
        size:               PAGE_SIZE,
      });
      setSearchResults(data);
    } catch (e) {
      setSearchError(e.message);
    } finally {
      setSearchLoading(false);
    }
  }, [date, filters, page]);

  function setFilter(key, val) { setFilters(f => ({ ...f, [key]: val })); }
  function handleSearch()       { setPage(0); doSearch(0); }
  function handleKeyDown(e)     { if (e.key === 'Enter') handleSearch(); }
  function goPage(p)            { setPage(p); doSearch(p); }

  const hasPrev = page > 0;
  const hasNext = searchResults && searchResults.length === PAGE_SIZE;
  const current = charts[chartIndex];

  function handleDateSelect(d) {
    onDateChange(d);
    setDateConfirmed(true);
  }

  /* Chart header controls */
  const chartControls = (
    <>
      <div className="ctrl-spacer" />
      <Button onClick={loadStats} disabled={statsLoading} variant="secondary" icon="↻" size="sm">
        {statsLoading ? 'Yükleniyor…' : 'Yenile'}
      </Button>
      <Button onClick={() => setShowBackfillModal(true)} variant="primary" size="sm">
        Index Senkronize Et
      </Button>
    </>
  );

  return (
    <>
      <DateCalendar
        selectedDate={date}
        onSelect={handleDateSelect}
        fetchDates={fetchStationPerformanceAvailableDates}
        label="Performans Tarihi Seç"
      />

      {!dateConfirmed ? (
        <EmptyState icon="⚡" title="Tarih seçin" description="Yukarıdaki takvimden veri bulunan bir tarih seçin" />
      ) : (
        <>
          {statsError && <div className="error">{statsError}</div>}

          {/* Stats carousel */}
          <ChartCard
            title={current.title}
            counter={chartIndex}
            total={total}
            onPrev={prev}
            onNext={next}
            controls={chartControls}
            dots={total}
            activeIndex={chartIndex}
            onDotClick={setChartIndex}
          >
            {statsLoading
              ? <div className="loading">Yükleniyor…</div>
              : current.node}
          </ChartCard>

          {/* Search panel */}
          <div className="card">
            <div className="card-header">
              <div>
                <div className="card-title">İstasyon Performans Arama</div>
                <div className="card-subtitle">Filtrele ve sırala</div>
              </div>
            </div>
            <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>

              {/* Tile filters */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 20 }}>
                <div>
                  <SectionLabel>Talep Seviyesi</SectionLabel>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {DEMAND_OPTS.map(o => (
                      <FilterChip
                        key={o.value}
                        label={o.label}
                        color={o.color}
                        active={filters.demandTier === o.value}
                        onClick={() => setFilter('demandTier', o.value)}
                      />
                    ))}
                  </div>
                </div>
                <div>
                  <SectionLabel>Arz Seviyesi</SectionLabel>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {SUPPLY_OPTS.map(o => (
                      <FilterChip
                        key={o.value}
                        label={o.label}
                        color={o.color}
                        active={filters.supplyTier === o.value}
                        onClick={() => setFilter('supplyTier', o.value)}
                      />
                    ))}
                  </div>
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <SectionLabel>Tavsiye Kodu</SectionLabel>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {/* "Tümü" chip */}
                    <FilterChip
                      label="Tümü"
                      color="#64748b"
                      active={filters.recommendationCode === ''}
                      onClick={() => setFilter('recommendationCode', '')}
                    />
                    {/* Rich chips with demand/supply sub-label */}
                    {Object.entries(REC_META).map(([code, meta]) => {
                      const active = filters.recommendationCode === code;
                      return (
                        <button
                          key={code}
                          onClick={() => setFilter('recommendationCode', code)}
                          style={{
                            display: 'flex', flexDirection: 'column', alignItems: 'flex-start',
                            padding: '5px 10px', borderRadius: 'var(--rx-full)',
                            border: `1px solid ${active ? meta.color : 'var(--bd-subtle)'}`,
                            background: active ? `${meta.color}22` : 'var(--bg-subtle)',
                            cursor: 'pointer', transition: 'all 120ms',
                          }}
                        >
                          <span style={{
                            fontSize: 11, fontWeight: 600, lineHeight: 1.3,
                            color: active ? meta.color : 'var(--tx-secondary)',
                          }}>
                            {meta.label}
                          </span>
                          <span style={{ fontSize: 9, color: 'var(--tx-disabled)', lineHeight: 1.2 }}>
                            T:{meta.demand} · A:{meta.supply}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>

              <div className="divider" />

              {/* Text + numeric filters */}
              <div className="filter-grid">
                <div className="filter-field">
                  <label>Şehir</label>
                  <input type="text" placeholder="örn. Ankara" value={filters.cityName}
                    onChange={e => setFilter('cityName', e.target.value)} onKeyDown={handleKeyDown} />
                </div>
                <div className="filter-field">
                  <label>İstasyon Adı</label>
                  <input type="text" placeholder="kısmi ad" value={filters.stationName}
                    onChange={e => setFilter('stationName', e.target.value)} onKeyDown={handleKeyDown} />
                </div>
                <div className="filter-field">
                  <label>Min Kullanım %</label>
                  <input type="number" min={0} max={100} placeholder="0" value={filters.minUtilizationPct}
                    onChange={e => setFilter('minUtilizationPct', e.target.value)} onKeyDown={handleKeyDown} />
                </div>
                <div className="filter-field">
                  <label>Max Kullanım %</label>
                  <input type="number" min={0} max={100} placeholder="100" value={filters.maxUtilizationPct}
                    onChange={e => setFilter('maxUtilizationPct', e.target.value)} onKeyDown={handleKeyDown} />
                </div>
                <div className="filter-field">
                  <label>Sıralama</label>
                  <select value={filters.sort} onChange={e => setFilter('sort', e.target.value)}>
                    {SORT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </div>
                <div className="filter-field">
                  <label>Yön</label>
                  <select value={filters.order} onChange={e => setFilter('order', e.target.value)}>
                    <option value="desc">Azalan ↓</option>
                    <option value="asc">Artan ↑</option>
                  </select>
                </div>
              </div>

              <div>
                <Button onClick={handleSearch} disabled={searchLoading} variant="primary" size="lg">
                  {searchLoading ? 'Aranıyor…' : 'Ara'}
                </Button>
              </div>
            </div>
          </div>

          {/* Search error */}
          {searchError && <div className="error">{searchError}</div>}

          {/* Results */}
          {searched && !searchLoading && (
            <div className="card">
              <div className="card-header">
                <div>
                  <div className="card-title">Arama Sonuçları</div>
                  {searchResults && (
                    <div className="card-subtitle">
                      {searchResults.length} kayıt — {page + 1}. sayfa
                    </div>
                  )}
                </div>
                <div className="pagination">
                  <button onClick={() => goPage(page - 1)} disabled={!hasPrev || searchLoading}>← Önceki</button>
                  <span style={{ color: 'var(--tx-tertiary)', padding: '0 8px', fontSize: 12, fontFamily: 'var(--font-mono)' }}>
                    {page + 1}
                  </span>
                  <button onClick={() => goPage(page + 1)} disabled={!hasNext || searchLoading}>Sonraki →</button>
                </div>
              </div>
              <SearchResultTable rows={searchResults} />
            </div>
          )}

          {showBackfillModal && (
            <PerformanceBackfillModal onClose={() => setShowBackfillModal(false)} />
          )}
        </>
      )}
    </>
  );
}
