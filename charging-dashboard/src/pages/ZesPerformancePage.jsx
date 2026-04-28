import { useState, useEffect, useCallback } from 'react';
import {
  PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
} from 'recharts';
import {
  fetchStationPerformanceRecommendationDistribution,
  fetchStationPerformanceDemandSupplyMatrix,
  fetchStationPerformanceUnderutilized,
  fetchStationPerformanceSearch,
} from '../api/zesApi';

// ── Renk paleti ──────────────────────────────────────────────────────────────

const REC_COLORS = {
  EXPAND:              '#f87171',
  INCREASE_CAPACITY:   '#fb923c',
  OPTIMIZE:            '#facc15',
  MONITOR_GROWTH:      '#38bdf8',
  MAINTAIN:            '#4ade80',
  IMPROVE_EFFICIENCY:  '#a78bfa',
  MONITOR:             '#94a3b8',
  REVIEW:              '#e2e8f0',
  RELOCATE:            '#f43f5e',
  INSUFFICIENT_DATA:   '#475569',
};

const DEMAND_COLORS = { HIGH: '#f87171', MEDIUM: '#facc15', LOW: '#4ade80' };
const SUPPLY_COLORS = { FEW: '#f87171', MODERATE: '#fb923c', MANY: '#4ade80', UNKNOWN: '#64748b' };

function recColor(code)    { return REC_COLORS[code]    ?? '#64748b'; }
function demandColor(tier) { return DEMAND_COLORS[tier] ?? '#64748b'; }
function supplyColor(tier) { return SUPPLY_COLORS[tier] ?? '#64748b'; }

// ── Küçük badge bileşenleri ──────────────────────────────────────────────────

function TierBadge({ value, colorFn }) {
  const c = colorFn(value);
  return (
    <span style={{
      display: 'inline-block', padding: '2px 10px', borderRadius: 999,
      background: c + '22', color: c, border: `1px solid ${c}55`,
      fontSize: 11, fontWeight: 700, letterSpacing: '0.03em',
    }}>
      {value ?? '—'}
    </span>
  );
}

function RecBadge({ value }) {
  const c = recColor(value);
  return (
    <span style={{
      display: 'inline-block', padding: '2px 9px', borderRadius: 6,
      background: c + '22', color: c, border: `1px solid ${c}44`,
      fontSize: 11, fontWeight: 700,
    }}>
      {value ?? '—'}
    </span>
  );
}

function UtilBar({ pct }) {
  const val  = pct != null ? Number(pct).toFixed(1) : null;
  const fill = pct >= 40 ? '#f87171' : pct >= 15 ? '#facc15' : '#4ade80';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <div style={{ flex: 1, height: 6, background: '#1e293b', borderRadius: 3, overflow: 'hidden' }}>
        <div style={{ width: `${Math.min(pct ?? 0, 100)}%`, height: '100%', background: fill, borderRadius: 3 }} />
      </div>
      <span style={{ color: fill, fontSize: 12, fontWeight: 600, minWidth: 38, textAlign: 'right' }}>
        {val != null ? `${val}%` : '—'}
      </span>
    </div>
  );
}

// ── Chart: Tavsiye Dağılımı (Pie) ───────────────────────────────────────────

function RecommendationPieChart({ data }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;
  const entries = Object.entries(data).map(([name, value]) => ({ name, value })).sort((a, b) => b.value - a.value);
  if (!entries.length) return <div className="loading">Veri yok</div>;
  const total = entries.reduce((s, e) => s + e.value, 0);
  return (
    <ResponsiveContainer width="100%" height={320}>
      <PieChart>
        <Pie
          data={entries} dataKey="value" nameKey="name"
          cx="50%" cy="50%" outerRadius={110}
          label={false}
          labelLine={false}
        >
          {entries.map(e => <Cell key={e.name} fill={recColor(e.name)} />)}
        </Pie>
        <Tooltip
          formatter={(v, name) => [`${v.toLocaleString()} istasyon  (${((v / total) * 100).toFixed(1)}%)`, name]}
          contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }}
          labelStyle={{ color: '#e2e8f0' }}
          itemStyle={{ color: '#cbd5e1' }}
        />
        <Legend formatter={(v) => <span style={{ color: recColor(v), fontWeight: 600, fontSize: 12 }}>{v}</span>} />
      </PieChart>
    </ResponsiveContainer>
  );
}

// ── Chart: Talep × Arz Matrisi (Grouped Bar) ─────────────────────────────────

const SUPPLY_TIERS = ['FEW', 'MODERATE', 'MANY', 'UNKNOWN'];

function DemandSupplyChart({ data }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;
  const entries = Object.entries(data).map(([demand, supplyMap]) => {
    const row = { demand };
    for (const st of SUPPLY_TIERS) row[st] = supplyMap[st] ?? 0;
    return row;
  });
  if (!entries.length) return <div className="loading">Veri yok</div>;

  const customTooltip = ({ active, payload, label }) => {
    if (!active || !payload?.length) return null;
    return (
      <div style={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8, padding: '8px 12px' }}>
        <p style={{ color: '#e2e8f0', marginBottom: 4, fontWeight: 600 }}>Talep: {label}</p>
        {payload.map(p => (
          <p key={p.dataKey} style={{ color: p.fill, margin: '2px 0', fontSize: 12 }}>
            {p.dataKey}: {p.value}
          </p>
        ))}
      </div>
    );
  };

  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={entries} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
        <XAxis dataKey="demand" tick={{ fill: '#94a3b8', fontSize: 13, fontWeight: 700 }} />
        <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} allowDecimals={false} />
        <Tooltip content={customTooltip} />
        <Legend formatter={(v) => <span style={{ color: supplyColor(v), fontWeight: 600, fontSize: 12 }}>{v}</span>} />
        {SUPPLY_TIERS.map(st => (
          <Bar key={st} dataKey={st} fill={supplyColor(st)} radius={[3, 3, 0, 0]} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  );
}

// ── Tablo: Düşük Kullanımlı İstasyonlar ─────────────────────────────────────

function UnderutilizedTable({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows) return <div className="loading">Yükleniyor…</div>;
  if (!rows.length) return <div className="loading">Veri yok</div>;

  return (
    <div className="table-wrapper" style={{ maxHeight: 320, overflowY: 'auto' }}>
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 24 }} />
            <th>İstasyon</th>
            <th>Şehir</th>
            <th>Talep</th>
            <th>Arz</th>
            <th>Kullanım %</th>
            <th>D/S Ratio</th>
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
                  style={{ cursor: 'pointer', background: isOpen ? '#1a2744' : undefined }}
                >
                  <td style={{ textAlign: 'center', color: '#475569', fontSize: 11, userSelect: 'none' }}>
                    {isOpen ? '▾' : '▸'}
                  </td>
                  <td style={{ fontWeight: 600, color: '#e2e8f0', maxWidth: 200 }}>{r.station_name ?? '—'}</td>
                  <td style={{ color: '#94a3b8' }}>{r.city_name ?? '—'}</td>
                  <td><TierBadge value={r.demand_tier} colorFn={demandColor} /></td>
                  <td><TierBadge value={r.supply_tier} colorFn={supplyColor} /></td>
                  <td style={{ minWidth: 140 }}><UtilBar pct={r.charging_utilization_pct} /></td>
                  <td style={{ textAlign: 'right', color: '#facc15', fontWeight: 600, fontFamily: 'monospace' }}>
                    {r.demand_supply_ratio != null ? Number(r.demand_supply_ratio).toFixed(2) : '—'}
                  </td>
                  <td><RecBadge value={r.recommendation_code} /></td>
                </tr>
                {isOpen && (
                  <tr key={`expand-${i}`}>
                    <td />
                    <td colSpan={7} style={{ background: '#0f172a', padding: '10px 16px 14px' }}>
                      <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 6 }}>
                        <strong style={{ color: '#64748b' }}>Adres:</strong> {r.address || '—'}
                      </div>
                      <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 6 }}>
                        <strong style={{ color: '#64748b' }}>Soket:</strong> {r.connector_count ?? '—'} &nbsp;|&nbsp;
                        <strong style={{ color: '#64748b' }}>Yakın İstasyon:</strong> {r.nearby_stations_count ?? '—'} &nbsp;|&nbsp;
                        <strong style={{ color: '#64748b' }}>Yakın Soket:</strong> {r.nearby_connectors_count ?? '—'}
                      </div>
                      <div style={{ fontSize: 12, color: recColor(r.recommendation_code) }}>
                        {r.recommendation || '—'}
                      </div>
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

// ── Arama Sonuç Tablosu ──────────────────────────────────────────────────────

function SearchResultTable({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows || !rows.length) return <div className="loading">Sonuç yok</div>;

  return (
    <div className="table-wrapper">
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 24 }} />
            <th>İstasyon</th>
            <th>Şehir / İl</th>
            <th>Talep</th>
            <th>Arz</th>
            <th>Kullanım %</th>
            <th>D/S Ratio</th>
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
                  style={{ cursor: 'pointer', background: isOpen ? '#1a2744' : undefined }}
                >
                  <td style={{ textAlign: 'center', color: '#475569', fontSize: 11, userSelect: 'none' }}>
                    {isOpen ? '▾' : '▸'}
                  </td>
                  <td style={{ fontWeight: 600, color: '#e2e8f0', maxWidth: 200 }}>{r.station_name ?? '—'}</td>
                  <td style={{ color: '#94a3b8' }}>
                    {[r.city_name, r.province_name].filter(Boolean).join(' / ') || '—'}
                  </td>
                  <td><TierBadge value={r.demand_tier} colorFn={demandColor} /></td>
                  <td><TierBadge value={r.supply_tier} colorFn={supplyColor} /></td>
                  <td style={{ minWidth: 140 }}><UtilBar pct={r.charging_utilization_pct} /></td>
                  <td style={{ textAlign: 'right', color: '#facc15', fontWeight: 600, fontFamily: 'monospace' }}>
                    {r.demand_supply_ratio != null ? Number(r.demand_supply_ratio).toFixed(2) : '—'}
                  </td>
                  <td style={{ textAlign: 'center', color: '#94a3b8' }}>{r.connector_count ?? '—'}</td>
                  <td><RecBadge value={r.recommendation_code} /></td>
                </tr>
                {isOpen && (
                  <tr key={`sre-${i}`}>
                    <td />
                    <td colSpan={8} style={{ background: '#0f172a', padding: '10px 16px 14px' }}>
                      <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 6 }}>
                        <strong style={{ color: '#64748b' }}>Adres:</strong> {r.address || '—'}
                      </div>
                      <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 6 }}>
                        <strong style={{ color: '#64748b' }}>Yakın İstasyon:</strong> {r.nearby_stations_count ?? '—'} &nbsp;|&nbsp;
                        <strong style={{ color: '#64748b' }}>Yakın Soket:</strong> {r.nearby_connectors_count ?? '—'} &nbsp;|&nbsp;
                        <strong style={{ color: '#64748b' }}>Toplam Şarj:</strong>{' '}
                        {r.total_charging_minutes != null ? `${(r.total_charging_minutes / 60).toFixed(1)} sa` : '—'}
                      </div>
                      <div style={{ fontSize: 12, color: recColor(r.recommendation_code) }}>
                        {r.recommendation || '—'}
                      </div>
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

// ── TileGroup (filtre butonları) ─────────────────────────────────────────────

function TileGroup({ options, value, onChange }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
      {options.map(opt => {
        const active = value === opt.value;
        const color  = opt.color;
        return (
          <button
            key={opt.value}
            onClick={() => onChange(opt.value)}
            style={{
              padding: '5px 13px', borderRadius: 7, border: 'none',
              background: active ? color : '#1e293b',
              color: active ? '#0f172a' : color,
              fontWeight: 700, fontSize: 12, cursor: 'pointer',
              outline: 'none', transition: 'all 0.15s',
              transform: active ? 'scale(1.05)' : 'scale(1)',
              boxShadow: active ? `0 2px 10px ${color}66` : 'none',
            }}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

// ── Filtre seçenekleri ────────────────────────────────────────────────────────

const DEMAND_OPTIONS = [
  { value: '',       label: 'Tümü',   color: '#64748b' },
  { value: 'HIGH',   label: 'HIGH',   color: '#f87171' },
  { value: 'MEDIUM', label: 'MEDIUM', color: '#facc15' },
  { value: 'LOW',    label: 'LOW',    color: '#4ade80' },
];

const SUPPLY_OPTIONS = [
  { value: '',         label: 'Tümü',     color: '#64748b' },
  { value: 'FEW',      label: 'FEW',      color: '#f87171' },
  { value: 'MODERATE', label: 'MODERATE', color: '#fb923c' },
  { value: 'MANY',     label: 'MANY',     color: '#4ade80' },
];

const REC_OPTIONS = [
  { value: '',                  label: 'Tümü',              color: '#64748b' },
  { value: 'EXPAND',            label: 'EXPAND',            color: '#f87171' },
  { value: 'INCREASE_CAPACITY', label: 'INCREASE_CAPACITY', color: '#fb923c' },
  { value: 'OPTIMIZE',          label: 'OPTIMIZE',          color: '#facc15' },
  { value: 'MONITOR_GROWTH',    label: 'MONITOR_GROWTH',    color: '#38bdf8' },
  { value: 'MAINTAIN',          label: 'MAINTAIN',          color: '#4ade80' },
  { value: 'IMPROVE_EFFICIENCY',label: 'IMPROVE_EFF.',      color: '#a78bfa' },
  { value: 'MONITOR',           label: 'MONITOR',           color: '#94a3b8' },
  { value: 'REVIEW',            label: 'REVIEW',            color: '#e2e8f0' },
  { value: 'RELOCATE',          label: 'RELOCATE',          color: '#f43f5e' },
];

const SORT_OPTIONS = [
  { value: 'demand_supply_ratio',      label: 'D/S Ratio' },
  { value: 'charging_utilization_pct', label: 'Kullanım %' },
  { value: 'connector_count',          label: 'Soket Sayısı' },
  { value: 'nearby_stations_count',    label: 'Yakın İstasyon' },
];

const PAGE_SIZE = 20;

const arrowStyle = {
  background: 'transparent', border: '1px solid #334155', borderRadius: 8,
  color: '#94a3b8', fontSize: 18, width: 36, height: 36, cursor: 'pointer',
  display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
};

// ── Ana Sayfa Bileşeni ───────────────────────────────────────────────────────

export default function ZesPerformancePage({ date, onDateChange }) {
  // ── Stats carousel ────────────────────────────────────────────────────────
  const [recDistData,   setRecDistData]   = useState(null);
  const [matrixData,    setMatrixData]    = useState(null);
  const [underutilData, setUnderutilData] = useState(null);
  const [statsLoading,  setStatsLoading]  = useState(false);
  const [statsError,    setStatsError]    = useState(null);
  const [chartIndex,    setChartIndex]    = useState(0);

  // ── Search ────────────────────────────────────────────────────────────────
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

  // ── Stats yükleme ─────────────────────────────────────────────────────────
  const loadStats = useCallback(async () => {
    setStatsLoading(true);
    setStatsError(null);
    try {
      const [rec, matrix, under] = await Promise.all([
        fetchStationPerformanceRecommendationDistribution({ date }),
        fetchStationPerformanceDemandSupplyMatrix({ date }),
        fetchStationPerformanceUnderutilized({ date, size: 20 }),
      ]);
      setRecDistData(rec);
      setMatrixData(matrix);
      setUnderutilData(under);
    } catch (e) {
      setStatsError(e.message);
    } finally {
      setStatsLoading(false);
    }
  }, [date]);

  useEffect(() => { loadStats(); }, [loadStats]);

  // ── Klavye navigasyonu ────────────────────────────────────────────────────
  const charts = [
    {
      title: 'Tavsiye Kodu Dağılımı',
      node:  <RecommendationPieChart data={recDistData} />,
    },
    {
      title: 'Talep × Arz Matrisi',
      node:  <DemandSupplyChart data={matrixData} />,
    },
    {
      title: 'Düşük Kullanımlı İstasyonlar — Top 20',
      node:  <UnderutilizedTable rows={underutilData} />,
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

  // ── Arama ─────────────────────────────────────────────────────────────────
  const doSearch = useCallback(async (pageOverride) => {
    const p = pageOverride ?? page;
    setSearchLoading(true);
    setSearchError(null);
    setSearched(true);
    try {
      const data = await fetchStationPerformanceSearch({
        date,
        demandTier:        filters.demandTier        || undefined,
        supplyTier:        filters.supplyTier        || undefined,
        recommendationCode:filters.recommendationCode|| undefined,
        cityName:          filters.cityName          || undefined,
        stationName:       filters.stationName       || undefined,
        minUtilizationPct: filters.minUtilizationPct !== '' ? Number(filters.minUtilizationPct) : undefined,
        maxUtilizationPct: filters.maxUtilizationPct !== '' ? Number(filters.maxUtilizationPct) : undefined,
        sort:              filters.sort,
        order:             filters.order,
        from:              p * PAGE_SIZE,
        size:              PAGE_SIZE,
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

  return (
    <>
      {/* ── Kontroller ──────────────────────────────────────────────────── */}
      <div className="page-controls">
        <div className="controls">
          <label>Tarih</label>
          <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />
          <button onClick={loadStats} disabled={statsLoading}>
            {statsLoading ? 'Yükleniyor…' : 'Yenile'}
          </button>
        </div>
      </div>

      {statsError && <div className="error">{statsError}</div>}

      {/* ── İstatistik Carousel ─────────────────────────────────────────── */}
      <div className="card" style={{ position: 'relative', padding: '24px 48px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <button onClick={prev} style={arrowStyle}>&#8592;</button>
          <h2 style={{ margin: 0, color: '#e2e8f0', fontSize: 15, textAlign: 'center', flex: 1 }}>
            {current.title}
          </h2>
          <button onClick={next} style={arrowStyle}>&#8594;</button>
        </div>

        <div style={{ minHeight: 340 }}>
          {statsLoading ? <div className="loading">Yükleniyor…</div> : current.node}
        </div>

        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 16 }}>
          {charts.map((_, i) => (
            <button
              key={i}
              onClick={() => setChartIndex(i)}
              style={{
                width: 8, height: 8, borderRadius: '50%', border: 'none',
                background: i === chartIndex ? '#38bdf8' : '#334155',
                cursor: 'pointer', padding: 0, transition: 'background 0.2s',
              }}
            />
          ))}
        </div>
      </div>

      {/* ── İstasyon Performans Arama ───────────────────────────────────── */}
      <div className="card" style={{ marginTop: 16 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: '#94a3b8', textTransform: 'uppercase',
          letterSpacing: '0.08em', marginBottom: 16 }}>
          İstasyon Performans Arama
        </div>

        {/* Tile filtreler */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16, marginBottom: 16 }}>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Talep Seviyesi
            </div>
            <TileGroup options={DEMAND_OPTIONS} value={filters.demandTier}
              onChange={v => setFilter('demandTier', v)} />
          </div>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Arz Seviyesi
            </div>
            <TileGroup options={SUPPLY_OPTIONS} value={filters.supplyTier}
              onChange={v => setFilter('supplyTier', v)} />
          </div>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Tavsiye Kodu
            </div>
            <TileGroup options={REC_OPTIONS} value={filters.recommendationCode}
              onChange={v => setFilter('recommendationCode', v)} />
          </div>
        </div>

        <div style={{ borderTop: '1px solid #1e293b', marginBottom: 16 }} />

        {/* Metin + sayı filtreler */}
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

        <div style={{ marginTop: 16 }}>
          <button onClick={handleSearch} disabled={searchLoading} className="btn-search">
            {searchLoading ? 'Aranıyor…' : 'Ara'}
          </button>
        </div>
      </div>

      {searchError && <div className="error" style={{ marginTop: 16 }}>{searchError}</div>}

      {searched && !searchLoading && (
        <div className="card" style={{ marginTop: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
            <div>
              <span style={{ fontWeight: 600, color: '#e2e8f0', fontSize: 15 }}>Sonuçlar</span>
              {searchResults && (
                <span style={{ marginLeft: 8, fontSize: 12, color: '#64748b' }}>
                  {searchResults.length} kayıt — Sayfa {page + 1}
                </span>
              )}
            </div>
            <div className="pagination">
              <button onClick={() => goPage(page - 1)} disabled={!hasPrev || searchLoading}>← Önceki</button>
              <span style={{ color: '#94a3b8', padding: '0 12px', fontSize: 13 }}>{page + 1}</span>
              <button onClick={() => goPage(page + 1)} disabled={!hasNext || searchLoading}>Sonraki →</button>
            </div>
          </div>
          <SearchResultTable rows={searchResults} />
        </div>
      )}
    </>
  );
}
