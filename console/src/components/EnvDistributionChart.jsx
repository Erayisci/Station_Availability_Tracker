import { useMemo } from 'react';
import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { TOOLTIP_STYLE } from './ui';

const ENV_COLORS = {
  RETAIL:      '#f87171',
  COMMERCIAL:  '#60a5fa',
  OFFICE:      '#fbbf24',
  RESIDENTIAL: '#34d399',
  UNKNOWN:     '#64748b',
};
const PALETTE = ['#34d399', '#60a5fa', '#fbbf24', '#f87171', '#8b5cf6'];

function colorFor(env, idx) {
  return ENV_COLORS[env] ?? PALETTE[idx % PALETTE.length];
}

export default function EnvDistributionChart({ data }) {
  const entries = useMemo(() => {
    if (!data) return [];
    return Object.entries(data)
      .map(([name, value]) => ({ name, value }))
      .sort((a, b) => b.value - a.value);
  }, [data]);

  if (!data)           return <div className="loading">Yükleniyor…</div>;
  if (!entries.length) return <div className="loading">Veri yok</div>;

  const total = entries.reduce((s, e) => s + e.value, 0);

  return (
    <div style={{ display: 'flex', gap: 24, alignItems: 'center', flexWrap: 'wrap', height: 300 }}>
      <ResponsiveContainer width={280} height={280}>
        <PieChart>
          <Pie
            data={entries}
            dataKey="value"
            nameKey="name"
            cx="50%" cy="50%"
            innerRadius={60} outerRadius={100}
            paddingAngle={2} strokeWidth={0}
          >
            {entries.map((e, i) => (
              <Cell key={e.name} fill={colorFor(e.name, i)} />
            ))}
          </Pie>
          <Tooltip
            formatter={(v) => [v.toLocaleString(), 'İstasyon']}
            {...TOOLTIP_STYLE}
          />
        </PieChart>
      </ResponsiveContainer>

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8, minWidth: 180 }}>
        {entries.map((e, i) => {
          const c   = colorFor(e.name, i);
          const pct = ((e.value / total) * 100).toFixed(1);
          return (
            <div key={e.name} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: c, flexShrink: 0 }} />
              <span style={{ flex: 1, fontSize: 12, color: 'var(--tx-secondary)', fontWeight: 500 }}>{e.name}</span>
              <span style={{ fontSize: 12, fontFamily: 'var(--font-mono)', color: c, fontWeight: 600, minWidth: 44, textAlign: 'right' }}>
                {pct}%
              </span>
              <span style={{ fontSize: 11, color: 'var(--tx-tertiary)', minWidth: 64, textAlign: 'right', fontFamily: 'var(--font-mono)' }}>
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
