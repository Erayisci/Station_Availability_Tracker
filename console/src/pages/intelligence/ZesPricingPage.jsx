import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import {
  fetchPricingConfig,
  fetchPricingStationForecast,
  fetchPricingStationHistory,
  fetchPricingAvailableDates,
  fetchForecastTop,
} from '../../api/zesApi';
import {
  Button, ChartCard, EmptyState, MetricCard,
  SectionLabel, SegmentedControl,
} from '../../components/ui';
import DateCalendar from '../../components/DateCalendar';
import { simulate, bandsToDiscounts } from '../../lib/pricingModel';

const SEGMENTS = [{ value: 'DC', label: 'DC (Hızlı)' }, { value: 'AC', label: 'AC' }];

const HH = (h) => `${String(h % 24).padStart(2, '0')}:00`;
const fmtTL = (v) =>
  v >= 1e6 ? `${(v / 1e6).toFixed(2)}M ₺`
  : v >= 1e3 ? `${(v / 1e3).toFixed(1)}K ₺`
  : `${v.toFixed(0)} ₺`;

/** İndirim kesrine göre accent tonu (0.20 = en koyu). */
const bandTint = (d, a = 1) => {
  const alpha = (0.06 + Math.min(d / 0.20, 1) * 0.34) * a;
  return `rgba(99, 102, 241, ${alpha.toFixed(3)})`;
};

const DEFAULT_BANDS = [
  { start: 0,  end: 8,  discount: 0.15 },  // Gece
  { start: 8,  end: 18, discount: 0.06 },  // Gündüz
  { start: 18, end: 24, discount: 0.03 },  // Akşam
];

/* ════════════════ Container width hook ════════════════ */
function useWidth(ref) {
  const [w, setW] = useState(720);
  useEffect(() => {
    if (!ref.current) return;
    const ro = new ResizeObserver((entries) => {
      const cw = entries[0]?.contentRect?.width;
      if (cw) setW(cw);
    });
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, [ref]);
  return w;
}

/* ════════════════ Station typeahead picker ════════════════ */
function StationPicker({ date, value, onPick }) {
  const [q, setQ] = useState('');
  const [hits, setHits] = useState([]);
  const [open, setOpen] = useState(false);
  const timer = useRef(null);

  useEffect(() => {
    clearTimeout(timer.current);
    if (!q || q.trim().length < 2) { setHits([]); return; }
    timer.current = setTimeout(async () => {
      try {
        const res = await fetchForecastTop({ date, stationName: q.trim(), size: 8 });
        setHits(res.rows || []);
        setOpen(true);
      } catch { setHits([]); }
    }, 250);
    return () => clearTimeout(timer.current);
  }, [q, date]);

  return (
    <div style={{ position: 'relative', flex: 1, minWidth: 240 }}>
      <input
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onFocus={() => hits.length && setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        placeholder={value ? `${value.station_name} ✓ — değiştirmek için ara…` : 'İstasyon ara (isim)…'}
        style={{
          width: '100%', height: 34, padding: '0 12px',
          background: 'var(--bg-elevated)', border: '1px solid var(--bd-default)',
          borderRadius: 'var(--rx-md)', color: 'var(--tx-primary)',
          fontFamily: 'var(--font-ui)', fontSize: 13, outline: 'none',
        }}
      />
      {open && hits.length > 0 && (
        <div style={{
          position: 'absolute', top: 38, left: 0, right: 0, zIndex: 20,
          background: 'var(--bg-surface)', border: '1px solid var(--bd-default)',
          borderRadius: 'var(--rx-md)', boxShadow: 'var(--sh-md, 0 8px 24px rgba(0,0,0,0.4))',
          maxHeight: 280, overflowY: 'auto',
        }}>
          {hits.map((h) => (
            <button key={h.station_id}
              onMouseDown={() => { onPick(h); setQ(''); setOpen(false); }}
              style={{
                display: 'block', width: '100%', textAlign: 'left', padding: '8px 12px',
                background: 'transparent', border: 'none', borderBottom: '1px solid var(--bd-subtle)',
                color: 'var(--tx-primary)', fontSize: 12, cursor: 'pointer', fontFamily: 'var(--font-ui)',
              }}>
              <div style={{ fontWeight: 600 }}>{h.station_name}</div>
              <div style={{ fontSize: 11, color: 'var(--tx-tertiary)' }}>{h.city_name} · {h.station_id}</div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/* ════════════════ Utilization chart + draggable band dividers ════════════════ */
function BandChart({ history, forecast, servedPolicy, bands, onBandsChange }) {
  const wrapRef = useRef(null);
  const svgRef = useRef(null);
  const W = useWidth(wrapRef);
  const H = 300;
  const L = 40, R = 14, T = 14, B = 26;            // plot margins
  const plotW = Math.max(W - L - R, 10);
  const plotH = H - T - B;

  const xs = (h) => L + (h / 24) * plotW;           // hour 0..24 → px
  const ys = (u) => T + (1 - Math.min(Math.max(u, 0), 100) / 100) * plotH;
  const cell = (h) => xs(h + 0.5);                  // hour cell center for points

  const path = (arr) => arr.map((u, h) => `${h === 0 ? 'M' : 'L'}${cell(h).toFixed(1)},${ys(Number(u) || 0).toFixed(1)}`).join(' ');

  const [drag, setDrag] = useState(null);           // boundary index being dragged

  const hourFromX = useCallback((clientX) => {
    const rect = svgRef.current.getBoundingClientRect();
    const scale = rect.width / W;                    // svg may be scaled by CSS
    const px = (clientX - rect.left) / scale - L;
    return Math.round((px / plotW) * 24);
  }, [W, plotW]);

  useEffect(() => {
    if (drag == null) return;
    const move = (e) => {
      const h = hourFromX(e.clientX);
      onBandsChange((prev) => {
        const next = prev.map((b) => ({ ...b }));
        const lo = next[drag].start + 1;
        const hi = next[drag + 1].end - 1;
        const clamped = Math.min(Math.max(h, lo), hi);
        next[drag].end = clamped;
        next[drag + 1].start = clamped;
        return next;
      });
    };
    const up = () => setDrag(null);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
  }, [drag, hourFromX, onBandsChange]);

  const boundaries = bands.slice(0, -1).map((b) => b.end);

  return (
    <div ref={wrapRef} style={{ width: '100%', userSelect: drag != null ? 'none' : 'auto' }}>
      <svg ref={svgRef} width={W} height={H} style={{ display: 'block', touchAction: 'none' }}>
        {/* band regions */}
        {bands.map((b, i) => (
          <rect key={i} x={xs(b.start)} y={T} width={xs(b.end) - xs(b.start)} height={plotH}
            fill={bandTint(b.discount)} />
        ))}
        {/* y gridlines */}
        {[0, 25, 50, 75, 100].map((g) => (
          <g key={g}>
            <line x1={L} x2={W - R} y1={ys(g)} y2={ys(g)} stroke="var(--bd-subtle)" strokeDasharray="3 3" />
            <text x={L - 6} y={ys(g) + 3} textAnchor="end" fontSize="10" fill="var(--tx-disabled)">{g}</text>
          </g>
        ))}
        {/* x ticks every 4h */}
        {[0, 4, 8, 12, 16, 20, 24].map((h) => (
          <text key={h} x={xs(h)} y={H - 8} textAnchor="middle" fontSize="10" fill="var(--tx-disabled)">{h}</text>
        ))}
        {/* historical faint curves */}
        {history.map((d, i) => (
          <path key={i} d={path(d.hourly_utilization_pct || [])} fill="none"
            stroke="var(--tx-tertiary)" strokeOpacity={0.22} strokeWidth={1} />
        ))}
        {/* served-under-discount overlay (dashed) */}
        {servedPolicy && (
          <path d={path(servedPolicy)} fill="none" stroke="var(--accent)" strokeOpacity={0.55}
            strokeWidth={1.5} strokeDasharray="4 3" />
        )}
        {/* forecast bold */}
        {forecast && (
          <path d={path(forecast)} fill="none" stroke="var(--accent)" strokeWidth={2.4} />
        )}
        {/* draggable dividers */}
        {boundaries.map((h, i) => (
          <g key={i} style={{ cursor: 'ew-resize' }} onPointerDown={() => setDrag(i)}>
            <line x1={xs(h)} x2={xs(h)} y1={T} y2={T + plotH} stroke="var(--accent)" strokeWidth={1.5} />
            <rect x={xs(h) - 7} y={T - 2} width={14} height={16} rx={3} fill="var(--accent)" />
            <text x={xs(h)} y={T + 10} textAnchor="middle" fontSize="9" fill="#fff" fontWeight="700">{h}</text>
          </g>
        ))}
      </svg>
      <div style={{ display: 'flex', gap: 16, fontSize: 11, color: 'var(--tx-tertiary)', paddingLeft: L, marginTop: 2 }}>
        <Legend color="var(--accent)" w={2.4}>tahmin (hedef tarih)</Legend>
        <Legend color="var(--accent)" dash>indirim altı tahmin</Legend>
        <Legend color="var(--tx-tertiary)" w={1} faint>geçmiş günler</Legend>
        <span style={{ marginLeft: 'auto' }}>↔ bölücüleri sürükleyin</span>
      </div>
    </div>
  );
}

function Legend({ color, w = 1.5, dash, faint, children }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
      <svg width="22" height="8"><line x1="0" y1="4" x2="22" y2="4" stroke={color}
        strokeOpacity={faint ? 0.3 : 0.8} strokeWidth={w} strokeDasharray={dash ? '4 3' : ''} /></svg>
      {children}
    </span>
  );
}

/* ════════════════ Band editor (right panel) ════════════════ */
function BandEditor({ bands, onBandsChange }) {
  const setDiscount = (i, d) => onBandsChange((prev) =>
    prev.map((b, j) => (j === i ? { ...b, discount: d } : b)));

  const addBand = () => onBandsChange((prev) => {
    // en geniş bandı ikiye böl
    let wi = 0, wlen = -1;
    prev.forEach((b, i) => { const len = b.end - b.start; if (len > wlen) { wlen = len; wi = i; } });
    if (wlen < 2) return prev;
    const b = prev[wi];
    const mid = Math.floor((b.start + b.end) / 2);
    const next = prev.slice();
    next.splice(wi, 1, { ...b, end: mid }, { start: mid, end: b.end, discount: b.discount });
    return next;
  });

  const removeBand = (i) => onBandsChange((prev) => {
    if (prev.length <= 1) return prev;
    const next = prev.map((b) => ({ ...b }));
    if (i === 0) next[1].start = 0;
    else next[i - 1].end = next[i].end;
    next.splice(i, 1);
    return next;
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <SectionLabel>Saat-Bandları · İndirim</SectionLabel>
        <Button size="sm" variant="ghost" icon="＋" onClick={addBand}>band</Button>
      </div>
      {bands.map((b, i) => (
        <div key={i} style={{
          border: '1px solid var(--bd-subtle)', borderRadius: 'var(--rx-md)',
          padding: '10px 12px', background: bandTint(b.discount, 0.5),
        }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--tx-secondary)' }}>
              {HH(b.start)}–{HH(b.end)}
            </span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <strong style={{ fontFamily: 'var(--font-mono)', fontSize: 15, color: 'var(--accent)' }}>
                %{(b.discount * 100).toFixed(0)}
              </strong>
              {bands.length > 1 && (
                <button onClick={() => removeBand(i)} title="bandı kaldır"
                  style={{ background: 'none', border: 'none', color: 'var(--tx-disabled)', cursor: 'pointer', fontSize: 14 }}>×</button>
              )}
            </span>
          </div>
          <input type="range" min={0} max={20} step={1} value={Math.round(b.discount * 100)}
            onChange={(e) => setDiscount(i, Number(e.target.value) / 100)}
            style={{ width: '100%', accentColor: 'var(--accent)' }} />
        </div>
      ))}
    </div>
  );
}

/* ════════════════ Elasticity scenario selector ════════════════ */
const SCENARIOS = [
  { value: 'low', label: 'Düşük' },
  { value: 'central', label: 'Merkez' },
  { value: 'high', label: 'Yüksek' },
];

function ScenarioPicker({ scenario, onChange, cfg, segment }) {
  // segment'in η taban büyüklüğünü göster (varsayım şeffaflığı)
  const eb = cfg?.eta_base?.[segment];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <SectionLabel>Elastikiyet Senaryosu</SectionLabel>
        {eb && (
          <span style={{ fontSize: 10, color: 'var(--tx-disabled)', fontFamily: 'var(--font-mono)' }}>
            η₀ {segment}: {eb.low} · {eb.central} · {eb.high}
          </span>
        )}
      </div>
      <SegmentedControl options={SCENARIOS} value={scenario} onChange={onChange} />
      <span style={{ fontSize: 10, color: 'var(--tx-disabled)' }}>
        düşük = talep daha az fiyat-duyarlı (kötümser) · yüksek = daha duyarlı (iyimser)
      </span>
    </div>
  );
}

/* ════════════════ Revenue panel (live) ════════════════ */
function RevenuePanel({ sim, scenario }) {
  if (!sim) return null;
  const up = sim.uplift_pct || {};
  const u = up[scenario] ?? 0;
  const upColor = u >= 0 ? 'var(--clr-success-text)' : 'var(--clr-danger-text)';
  const scnLabel = SCENARIOS.find((s) => s.value === scenario)?.label ?? scenario;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
      <MetricCard label={`Tahmini Gelir (${scnLabel.toLowerCase()})`} value={fmtTL(sim.policy[scenario])}
        sub={`baz: ${fmtTL(sim.baseline)}`} />
      <MetricCard label="Gelir Artışı" value={`${u >= 0 ? '+' : ''}${u.toFixed(1)}%`}
        sub={`bant: ${up.low?.toFixed(1)}% … ${up.high?.toFixed(1)}%`} color={upColor} />
    </div>
  );
}

/* ════════════════ Page ════════════════ */
export default function ZesPricingPage({ date, onDateChange }) {
  const [dateConfirmed, setDateConfirmed] = useState(!!date);
  const [cfg, setCfg] = useState(null);
  const [station, setStation] = useState(null);     // {station_id, station_name, city_name}
  const [segment, setSegment] = useState('DC');
  const [scenario, setScenario] = useState('central');    // elastikiyet senaryosu
  const [forecastDocs, setForecastDocs] = useState([]);   // per-segment
  const [history, setHistory] = useState([]);             // [{date,segment,hourly_utilization_pct}]
  const [bands, setBands] = useState(DEFAULT_BANDS);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // config once
  useEffect(() => { fetchPricingConfig().then(setCfg).catch((e) => setError(e.message)); }, []);

  const loadStation = useCallback(async (st) => {
    if (!st) return;
    setLoading(true); setError(null);
    try {
      const [fc, hist] = await Promise.all([
        fetchPricingStationForecast({ date, stationId: st.station_id }),
        fetchPricingStationHistory({ stationId: st.station_id, days: 14 }),
      ]);
      setForecastDocs(fc || []);
      setHistory(hist || []);
      // varsa DC, yoksa AC'ye düş
      const segs = new Set((fc || []).map((d) => d.segment));
      if (!segs.has(segment)) setSegment(segs.has('DC') ? 'DC' : (segs.values().next().value || 'DC'));
    } catch (e) { setError(e.message); }
    finally { setLoading(false); }
  }, [date]); // segment intentionally excluded

  function handlePick(st) { setStation(st); loadStation(st); }
  function handleDateSelect(d) { onDateChange(d); setDateConfirmed(true); setStation(null); setForecastDocs([]); setHistory([]); }

  const forecast = forecastDocs.find((d) => d.segment === segment);
  const segHistory = useMemo(
    () => history.filter((h) => h.segment === segment),
    [history, segment]);

  // canlı simülasyon
  const sim = useMemo(() => {
    if (!cfg || !forecast) return null;
    const cell = {
      segment,
      hourly: forecast.hourly_predicted_pct || [],
      connector_count: forecast.connector_count,
      avg_power_kw: forecast.avg_power_kw,
      nearby_stations_count: forecast.nearby_stations_count,
    };
    const discounts = bandsToDiscounts(bands);
    return simulate(cell, discounts, cfg);
  }, [cfg, forecast, segment, bands]);

  const availableSegs = new Set(forecastDocs.map((d) => d.segment));
  const segOptions = SEGMENTS.filter((s) => availableSegs.has(s.value));

  const controls = (
    <>
      <StationPicker date={date} value={station} onPick={handlePick} />
      {segOptions.length > 1 && (
        <SegmentedControl options={segOptions} value={segment} onChange={setSegment} />
      )}
    </>
  );

  return (
    <>
      <DateCalendar
        selectedDate={date}
        onSelect={handleDateSelect}
        fetchDates={fetchPricingAvailableDates}
        label="Tahmin Tarihi Seç"
      />
      {!dateConfirmed ? (
        <EmptyState icon="💸" title="Tarih seçin"
          description="Saatlik forecast'ı bulunan bir tarih seçin (zes_station_hourly_forecast)." />
      ) : (
        <>
          {error && <div className="error">{error}</div>}
          <ChartCard
            title="İstasyon Fiyatlandırma"
            subtitle="geçmiş doluluğa bakıp saat-bandı indirimi belirleyin · gelir anlık simülasyon"
            controls={controls}
            minHeight={420}
          >
            {!station ? (
              <EmptyState icon="🔌" title="İstasyon seçin"
                description="Yukarıdan bir istasyon arayın. Geçmiş doluluk eğrileri ile hedef tarih tahmini grafikte gösterilir." />
            ) : loading ? (
              <div className="loading">Yükleniyor…</div>
            ) : !forecast ? (
              <EmptyState icon="📭" title="Forecast yok"
                description={`${station.station_name} için bu tarihte saatlik forecast bulunamadı.`} />
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1.6fr) minmax(260px, 1fr)', gap: 20, alignItems: 'start' }}>
                <div>
                  <SectionLabel>Doluluk (%) · saat 0–24</SectionLabel>
                  <BandChart
                    history={segHistory}
                    forecast={forecast.hourly_predicted_pct}
                    servedPolicy={sim?.served?.[scenario]}
                    bands={bands}
                    onBandsChange={setBands}
                  />
                  <div style={{ fontSize: 11, color: 'var(--tx-tertiary)', marginTop: 8 }}>
                    {station.station_name} · {station.city_name} · {segment} ·
                    {' '}{forecast.connector_count} bağlayıcı · {Number(forecast.avg_power_kw).toFixed(0)} kW ·
                    {' '}{segHistory.length} geçmiş gün
                  </div>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                  <RevenuePanel sim={sim} scenario={scenario} />
                  <ScenarioPicker scenario={scenario} onChange={setScenario} cfg={cfg} segment={segment} />
                  <BandEditor bands={bands} onBandsChange={setBands} />
                  <div style={{ fontSize: 11, color: 'var(--tx-tertiary)', lineHeight: 1.5,
                    background: 'var(--bg-elevated)', border: '1px solid var(--bd-subtle)',
                    borderRadius: 'var(--rx-md)', padding: '8px 10px' }}>
                    Gelir bir <strong>simülasyondur</strong> (varsayılan elastikiyet).
                    Tek sayı değil, <strong>low–high bandı</strong> ile yorumlayın.
                  </div>
                </div>
              </div>
            )}
          </ChartCard>
        </>
      )}
    </>
  );
}
