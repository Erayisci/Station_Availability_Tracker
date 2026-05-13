import { useMemo } from 'react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer
} from 'recharts';

function transformTimeline(raw, chargingOnly = true) {
  if (!Array.isArray(raw) || raw.length === 0) return { entries: [], statuses: [] };

  const entries = raw.map(snap => {
    const { t, ...counts } = snap;

    if (chargingOnly) {
      const chargingCount = counts.Charging || counts.charging || 0;
      const otherCount = Object.entries(counts)
        .filter(([status]) => status !== 'Charging' && status !== 'charging')
        .reduce((sum, [, count]) => sum + count, 0);

      return {
        ts: new Date(t).getTime(),
        label: formatTime(t),
        Charging: chargingCount,
        Other: otherCount,
      };
    } else {
      const ts = new Date(t).getTime();
      return { ts, label: formatTime(t), ...counts };
    }
  });

  entries.sort((a, b) => a.ts - b.ts);

  // Add terminal point at end-of-day
  if (entries.length > 0) {
    const last = entries[entries.length - 1];
    const dayEnd = new Date(last.ts);
    dayEnd.setHours(23, 59, 59, 999);
    if (dayEnd.getTime() > last.ts) {
      const tail = { ...last, ts: dayEnd.getTime(), label: '23:59' };
      entries.push(tail);
    }
  }

  return {
    entries,
    statuses: chargingOnly ? ['Charging', 'Other'] : []
  };
}

function formatTime(iso) {
  const d = new Date(iso);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

export default function TimelineChart({ data, showChargingOnly = true }) {
  const { entries } = useMemo(
    () => transformTimeline(data, showChargingOnly),
    [data, showChargingOnly]
  );

  if (!data) return <div className="loading">Yükleniyor…</div>;
  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  return (
    <div>
      {/* Legend */}
      <div style={{
        display: 'flex',
        gap: 'var(--space-3)',
        alignItems: 'center',
        marginBottom: 'var(--space-4)',
        justifyContent: 'flex-end'
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
          <div style={{
            width: 12,
            height: 12,
            borderRadius: '50%',
            background: '#10b981',
            boxShadow: '0 0 8px #10b98166'
          }} />
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', fontWeight: 600 }}>
            Charging
          </span>
        </div>
        {showChargingOnly && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
            <div style={{
              width: 12,
              height: 12,
              borderRadius: '50%',
              background: '#475569'
            }} />
            <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', fontWeight: 600 }}>
              Other
            </span>
          </div>
        )}
      </div>

      <ResponsiveContainer width="100%" height={360}>
        <AreaChart data={entries} margin={{ top: 10, right: 20, left: 0, bottom: 20 }}>
          <defs>
            <linearGradient id="tlGradCharging" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#10b981" stopOpacity={0.4} />
              <stop offset="95%" stopColor="#10b981" stopOpacity={0.05} />
            </linearGradient>
            <linearGradient id="tlGradOther" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#475569" stopOpacity={0.2} />
              <stop offset="95%" stopColor="#475569" stopOpacity={0.02} />
            </linearGradient>
          </defs>

          <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" />

          <XAxis
            dataKey="ts"
            type="number"
            scale="time"
            domain={['dataMin', 'dataMax']}
            tickFormatter={ts => {
              const d = new Date(ts);
              return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
            }}
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            stroke="var(--border-medium)"
          />

          <YAxis
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            stroke="var(--border-medium)"
          />

          <Tooltip
            contentStyle={{
              background: 'var(--bg-secondary)',
              border: '1px solid var(--border-medium)',
              borderRadius: 'var(--radius-md)',
              boxShadow: 'var(--shadow-lg)',
              padding: 'var(--space-3)'
            }}
            labelStyle={{
              color: 'var(--text-primary)',
              fontWeight: 600,
              marginBottom: 'var(--space-2)'
            }}
            itemStyle={{
              color: 'var(--text-secondary)',
              fontSize: 'var(--text-sm)',
              padding: 'var(--space-1) 0'
            }}
            labelFormatter={ts => {
              const d = new Date(ts);
              return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
            }}
          />

          <Area
            type="stepAfter"
            dataKey="Charging"
            stackId="status"
            stroke="#10b981"
            fill="url(#tlGradCharging)"
            strokeWidth={2.5}
            isAnimationActive={false}
          />

          {showChargingOnly && (
            <Area
              type="stepAfter"
              dataKey="Other"
              stackId="status"
              stroke="#475569"
              fill="url(#tlGradOther)"
              strokeWidth={1.5}
              strokeDasharray="5 5"
              isAnimationActive={false}
            />
          )}
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
