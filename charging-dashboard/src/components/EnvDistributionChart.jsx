import { useMemo } from 'react';
import {
  PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

const ENV_COLORS = {
  RETAIL:      '#f87171',
  COMMERCIAL:  '#38bdf8',
  OFFICE:      '#facc15',
  RESIDENTIAL: '#4ade80',
  UNKNOWN:     '#64748b',
};

function colorFor(env) {
  return ENV_COLORS[env] ?? '#94a3b8';
}

export default function EnvDistributionChart({ data }) {
  const entries = useMemo(() => {
    if (!data) return [];
    return Object.entries(data)
      .map(([name, value]) => ({ name, value }))
      .sort((a, b) => b.value - a.value);
  }, [data]);

  if (!data)    return <div className="loading">Yükleniyor…</div>;
  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  const total = entries.reduce((s, e) => s + e.value, 0);

  return (
    <ResponsiveContainer width="100%" height={300}>
      <PieChart>
        <Pie
          data={entries}
          dataKey="value"
          nameKey="name"
          cx="50%"
          cy="50%"
          outerRadius={110}
          label={({ name, value }) =>
            `${name} ${((value / total) * 100).toFixed(1)}%`
          }
          labelLine={{ stroke: '#475569' }}
        >
          {entries.map(entry => (
            <Cell key={entry.name} fill={colorFor(entry.name)} />
          ))}
        </Pie>
        <Tooltip
          formatter={(value) => [value.toLocaleString(), 'İstasyon']}
          contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }}
          labelStyle={{ color: '#e2e8f0' }}
          itemStyle={{ color: '#cbd5e1' }}
        />
        <Legend
          formatter={(value) => (
            <span style={{ color: colorFor(value), fontWeight: 600 }}>{value}</span>
          )}
        />
      </PieChart>
    </ResponsiveContainer>
  );
}
