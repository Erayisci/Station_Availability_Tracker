import { Badge, EmptyState, MetricCard } from './ui';

/* Yatay basit bar — etiket + sayı + oransal dolu çubuk */
function DistBar({ label, value, total, color = 'var(--accent)' }) {
  const pct = total > 0 ? (value / total) * 100 : 0;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 12 }}>
      <div style={{ minWidth: 110, color: 'var(--tx-secondary)' }}>{label}</div>
      <div style={{
        flex: 1,
        height: 6,
        borderRadius: 3,
        background: 'var(--bg-elevated)',
        overflow: 'hidden',
        position: 'relative',
      }}>
        <div style={{
          width: `${pct}%`,
          height: '100%',
          background: color,
          transition: 'width 200ms',
        }}/>
      </div>
      <div style={{
        minWidth: 36, textAlign: 'right',
        fontFamily: 'var(--font-mono)',
        color: 'var(--tx-primary)',
      }}>{value}</div>
    </div>
  );
}

function DistList({ title, dist, colorize }) {
  const entries = dist ? Object.entries(dist) : [];
  if (entries.length === 0) return null;
  const total = entries.reduce((acc, [, v]) => acc + v, 0);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{
        fontSize: 11, fontWeight: 600,
        color: 'var(--tx-tertiary)',
        textTransform: 'uppercase',
        letterSpacing: '0.05em',
      }}>{title}</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {entries.map(([k, v]) => (
          <DistBar key={k} label={k} value={v} total={total}
            color={colorize?.(k) ?? 'var(--accent)'}/>
        ))}
      </div>
    </div>
  );
}

function StationRow({ station, primary }) {
  const value = station[primary];
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '8px 10px',
      background: 'var(--bg-elevated)',
      borderRadius: 'var(--rx-sm)',
      gap: 10,
    }}>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{
          fontSize: 12,
          color: 'var(--tx-primary)',
          fontWeight: 500,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}>{station.station_name}</div>
        <div style={{ display: 'flex', gap: 4, marginTop: 3, flexWrap: 'wrap' }}>
          {station.demand_tier && <Badge value={station.demand_tier}/>}
          {station.supply_tier && <Badge value={station.supply_tier}/>}
        </div>
      </div>
      <div style={{
        fontFamily: 'var(--font-mono)',
        fontSize: 13,
        color: 'var(--tx-primary)',
        fontWeight: 600,
      }}>
        {typeof value === 'number' ? value.toFixed(1) : '—'}
      </div>
    </div>
  );
}

const TIER_COLOR = {
  HIGH: 'var(--clr-danger)', MEDIUM: 'var(--clr-warning)', LOW: 'var(--clr-success)',
  FEW:  'var(--clr-danger)', MODERATE: 'var(--clr-warning)', MANY: 'var(--clr-success)',
};

export default function CityDetailPanel({ city, detail, loading, onClose }) {
  if (!city) {
    return (
      <EmptyState
        icon="🗺️"
        title="Bir şehir seçin"
        description="Haritadan bir il tıklayarak detaylı analiz ve istasyon listesini görebilirsiniz."
      />
    );
  }

  if (loading) {
    return (
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        minHeight: 240, color: 'var(--tx-tertiary)', fontSize: 13,
      }}>
        Yükleniyor…
      </div>
    );
  }

  if (!detail) {
    return (
      <EmptyState
        title="Veri yok"
        description={`${city} için bu tarihe ait performans verisi bulunamadı.`}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* ── Header ──────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <div>
          <div style={{
            fontSize: 11,
            color: 'var(--tx-tertiary)',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
          }}>Şehir Analizi</div>
          <div style={{
            fontSize: 22,
            fontWeight: 700,
            color: 'var(--tx-primary)',
            marginTop: 2,
          }}>{detail.city}</div>
        </div>
        {onClose && (
          <button
            onClick={onClose}
            style={{
              background: 'transparent',
              border: '1px solid var(--bd-default)',
              borderRadius: 'var(--rx-sm)',
              color: 'var(--tx-tertiary)',
              cursor: 'pointer',
              padding: '4px 8px',
              fontSize: 12,
            }}
          >Kapat</button>
        )}
      </div>

      {/* ── Top metrics ─────────────────────────────────────────────── */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: '1fr 1fr',
        gap: 8,
      }}>
        <MetricCard label="İstasyon" value={detail.station_count ?? 0}/>
        <MetricCard label="Konektör" value={detail.connector_count ?? 0}/>
        <MetricCard
          label="Ort. Doluluk"
          value={`${detail.avg_utilization_pct ?? 0}%`}
        />
        <MetricCard
          label="Talep / Arz"
          value={detail.avg_demand_supply_ratio ?? 0}
        />
      </div>

      {/* ── Distributions ───────────────────────────────────────────── */}
      <DistList title="Talep" dist={detail.demand_tier_distribution}
        colorize={k => TIER_COLOR[k]}/>
      <DistList title="Arz" dist={detail.supply_tier_distribution}
        colorize={k => TIER_COLOR[k]}/>
      <DistList title="Konektör Tipi" dist={detail.connector_type_distribution}/>
      <DistList title="Tavsiye" dist={detail.recommendation_distribution}/>

      {/* ── Top stations ────────────────────────────────────────────── */}
      {detail.top_overutilized?.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{
            fontSize: 11, fontWeight: 600,
            color: 'var(--tx-tertiary)',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
          }}>En Yüksek Talep / Arz</div>
          {detail.top_overutilized.map(s => (
            <StationRow key={s.station_id} station={s} primary="demand_supply_ratio"/>
          ))}
        </div>
      )}

      {detail.top_underutilized?.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{
            fontSize: 11, fontWeight: 600,
            color: 'var(--tx-tertiary)',
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
          }}>En Düşük Doluluk %</div>
          {detail.top_underutilized.map(s => (
            <StationRow key={s.station_id} station={s} primary="charging_utilization_pct"/>
          ))}
        </div>
      )}
    </div>
  );
}
