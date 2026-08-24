import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer
} from 'recharts';

function toHours(minutes) {
  return Math.round(minutes / 60);
}

function transformData(raw, chargingOnly = true) {
  const entries = Object.entries(raw).map(([city, durations]) => {
    if (chargingOnly) {
      const chargingMinutes = durations.Charging || durations.charging || 0;
      const otherMinutes = Object.entries(durations)
        .filter(([status]) => status !== 'Charging' && status !== 'charging')
        .reduce((sum, [, minutes]) => sum + minutes, 0);

      return {
        city,
        Charging: toHours(chargingMinutes),
        Other: toHours(otherMinutes),
      };
    } else {
      const row = { city };
      for (const [status, minutes] of Object.entries(durations)) {
        row[status] = toHours(minutes);
      }
      return row;
    }
  });

  // Sort by Charging hours descending
  entries.sort((a, b) => (b.Charging || 0) - (a.Charging || 0));

  return { entries };
}

const customTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null;
  return (
    <div style={{
      background: 'var(--bg-secondary)',
      border: '1px solid var(--border-medium)',
      borderRadius: 'var(--radius-md)',
      padding: 'var(--space-3)',
      boxShadow: 'var(--shadow-lg)'
    }}>
      <p style={{
        color: 'var(--text-primary)',
        marginBottom: 'var(--space-2)',
        fontWeight: 600,
        fontSize: 'var(--text-sm)'
      }}>
        {label}
      </p>
      {payload.map(p => (
        <p key={p.dataKey} style={{
          color: p.fill,
          margin: '2px 0',
          fontSize: 'var(--text-sm)',
          display: 'flex',
          justifyContent: 'space-between',
          gap: 'var(--space-4)'
        }}>
          <span>{p.dataKey}:</span>
          <span style={{ fontWeight: 600, fontFamily: 'var(--font-mono)' }}>
            {p.value.toLocaleString()} sa
          </span>
        </p>
      ))}
    </div>
  );
};

export default function CityDurationChart({ data, showChargingOnly = true }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;

  const { entries } = transformData(data, showChargingOnly);

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
            borderRadius: 'var(--radius-sm)',
            background: '#10b981'
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
              borderRadius: 'var(--radius-sm)',
              background: '#475569'
            }} />
            <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', fontWeight: 600 }}>
              Other
            </span>
          </div>
        )}
      </div>

      <ResponsiveContainer width="100%" height={360}>
        <BarChart data={entries} margin={{ top: 10, right: 20, left: 0, bottom: 60 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" />
          <XAxis
            dataKey="city"
            tick={{ fill: 'var(--text-muted)', fontSize: 10 }}
            stroke="var(--border-medium)"
            angle={-45}
            textAnchor="end"
            interval={0}
            height={80}
          />
          <YAxis
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            stroke="var(--border-medium)"
            unit=" sa"
          />
          <Tooltip content={customTooltip} />

          <Bar
            dataKey="Charging"
            stackId="a"
            fill="#10b981"
            radius={showChargingOnly ? [0, 0, 0, 0] : [4, 4, 0, 0]}
          />

          {showChargingOnly && (
            <Bar
              dataKey="Other"
              stackId="a"
              fill="#475569"
              opacity={0.6}
              radius={[4, 4, 0, 0]}
            />
          )}
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
