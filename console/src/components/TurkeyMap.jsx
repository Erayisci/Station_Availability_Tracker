import { useMemo, useState, useRef } from 'react';
import { geoMercator, geoPath } from 'd3-geo';
import turkeyGeo from '../data/turkey-provinces.json';

/* GeoJSON 'Afyon' → ES 'Afyonkarahisar' (tek bilinen isim farkı) */
const GEO_TO_ES = { Afyon: 'Afyonkarahisar' };
const ES_TO_GEO = Object.fromEntries(Object.entries(GEO_TO_ES).map(([g, e]) => [e, g]));

/* Tek tonlu choropleth skalası — açık → koyu accent.
   value ∈ [0, 1]; veri yoksa null döner */
function colorScale(value) {
  if (value == null || isNaN(value)) return null;
  const v = Math.max(0, Math.min(1, value));
  // 8 step ramp: rgba(99,102,241,α) — α 0.06 → 0.95
  const alpha = 0.06 + v * 0.89;
  return `rgba(99, 102, 241, ${alpha.toFixed(3)})`;
}

const METRICS = [
  { value: 'avg_demand_supply_ratio', label: 'Talep / Arz', max: 100, unit: '' },
  { value: 'avg_utilization_pct',     label: 'Doluluk %',   max: 100, unit: '%' },
  { value: 'station_count',           label: 'İstasyon',    max: null, unit: '' },
];

export default function TurkeyMap({
  citySummary,         // { "Ankara": { avg_demand_supply_ratio, ..., }, ... }
  metric = 'avg_demand_supply_ratio',
  selectedCity,        // ES city name (e.g. "Afyonkarahisar") or null
  onSelect,            // (esCityName | null) => void
  width  = 880,
  height = 380,
}) {
  const [hover, setHover] = useState(null); // { city, x, y, value }
  const svgRef = useRef(null);

  const metricMeta = useMemo(
    () => METRICS.find(m => m.value === metric) ?? METRICS[0],
    [metric]
  );

  /* Veri max'ini hesapla (station_count gibi sınırsız metrikler için) */
  const dataMax = useMemo(() => {
    if (metricMeta.max != null) return metricMeta.max;
    if (!citySummary) return 1;
    let max = 0;
    for (const row of Object.values(citySummary)) {
      const v = row?.[metric];
      if (typeof v === 'number' && v > max) max = v;
    }
    return max || 1;
  }, [citySummary, metric, metricMeta.max]);

  /* d3-geo projection: GeoJSON'ı viewBox'a sığdır */
  const { pathD, projection } = useMemo(() => {
    const proj = geoMercator().fitSize([width, height], turkeyGeo);
    const path = geoPath(proj);
    return {
      pathD: turkeyGeo.features.map(f => ({
        name: f.properties.name,                 // GeoJSON adı (örn. 'Afyon')
        esName: GEO_TO_ES[f.properties.name] ?? f.properties.name, // ES adı
        d: path(f),
      })),
      projection: proj,
    };
  }, [width, height]);

  function handleHover(feature, e) {
    const v = citySummary?.[feature.esName]?.[metric];
    const rect = svgRef.current?.getBoundingClientRect();
    setHover({
      city: feature.esName,
      x: e.clientX - (rect?.left ?? 0),
      y: e.clientY - (rect?.top ?? 0),
      value: typeof v === 'number' ? v : null,
      stationCount: citySummary?.[feature.esName]?.station_count ?? 0,
    });
  }

  const selectedGeoName = selectedCity ? (ES_TO_GEO[selectedCity] ?? selectedCity) : null;

  return (
    <div style={{ position: 'relative', width: '100%' }}>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', height: 'auto', display: 'block' }}
        onMouseLeave={() => setHover(null)}
      >
        <rect width={width} height={height} fill="var(--bg-base)" />
        {pathD.map(feature => {
          const row = citySummary?.[feature.esName];
          const value = typeof row?.[metric] === 'number' ? row[metric] : null;
          const fill = value != null
            ? colorScale(value / dataMax)
            : 'rgba(100, 116, 139, 0.06)';
          const isSelected = feature.name === selectedGeoName;
          const hasData = value != null;
          return (
            <path
              key={feature.name}
              d={feature.d}
              fill={fill}
              stroke={isSelected ? 'var(--accent)' : 'var(--bd-default)'}
              strokeWidth={isSelected ? 1.8 : 0.5}
              style={{
                cursor: hasData ? 'pointer' : 'default',
                transition: 'fill 120ms, stroke 120ms',
              }}
              onMouseMove={(e) => handleHover(feature, e)}
              onClick={() => {
                if (!hasData) return;
                onSelect?.(feature.esName === selectedCity ? null : feature.esName);
              }}
            />
          );
        })}
      </svg>

      {/* ── Hover tooltip ───────────────────────────────────────────── */}
      {hover && (
        <div
          style={{
            position: 'absolute',
            left: hover.x + 12,
            top: hover.y + 12,
            pointerEvents: 'none',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--bd-default)',
            borderRadius: 'var(--rx-md)',
            padding: '8px 12px',
            fontSize: 12,
            color: 'var(--tx-primary)',
            boxShadow: 'var(--sh-md)',
            whiteSpace: 'nowrap',
            zIndex: 2,
          }}
        >
          <div style={{ fontWeight: 600, marginBottom: 2 }}>{hover.city}</div>
          <div style={{ color: 'var(--tx-secondary)', fontSize: 11 }}>
            {metricMeta.label}:{' '}
            <span style={{ color: 'var(--tx-primary)', fontFamily: 'var(--font-mono)' }}>
              {hover.value != null ? `${hover.value}${metricMeta.unit}` : '—'}
            </span>
          </div>
          <div style={{ color: 'var(--tx-tertiary)', fontSize: 11 }}>
            {hover.stationCount} istasyon
          </div>
        </div>
      )}

      {/* ── Legend ──────────────────────────────────────────────────── */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        marginTop: 12,
        fontSize: 11,
        color: 'var(--tx-tertiary)',
        fontFamily: 'var(--font-mono)',
      }}>
        <span>0</span>
        <div style={{
          flex: 1, height: 8, maxWidth: 240,
          borderRadius: 4,
          background: 'linear-gradient(to right, rgba(99,102,241,0.06), rgba(99,102,241,0.95))',
        }} />
        <span>{metricMeta.max != null ? metricMeta.max : Math.round(dataMax)}{metricMeta.unit}</span>
        <span style={{ marginLeft: 12, color: 'var(--tx-secondary)' }}>{metricMeta.label}</span>
      </div>
    </div>
  );
}

export { METRICS as TURKEY_MAP_METRICS };
