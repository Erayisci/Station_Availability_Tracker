import React from 'react';
import {
  PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

const COLORS = ['#3b82f6', '#a78bfa', '#34d399', '#f59e0b', '#f87171', '#94a3b8'];

function transformData(raw) {
  // Flatten: for each socket type, sum all statuses into total
  return Object.entries(raw).map(([type, counts]) => ({
    name: type,
    value: Object.values(counts).reduce((a, b) => a + b, 0),
    counts,
  })).sort((a, b) => b.value - a.value);
}

const CustomTooltip = ({ active, payload }) => {
  if (!active || !payload?.length) return null;
  const { name, counts } = payload[0].payload;
  return (
    <div style={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8, padding: '10px 14px' }}>
      <div style={{ color: '#e2e8f0', fontWeight: 600, marginBottom: 6 }}>{name}</div>
      {Object.entries(counts).map(([s, v]) => (
        <div key={s} style={{ color: '#94a3b8', fontSize: 12 }}>{s}: <span style={{ color: '#e2e8f0' }}>{v}</span></div>
      ))}
    </div>
  );
};

export default function SocketTypeChart({ data }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;

  const entries = transformData(data);

  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  return (
    <ResponsiveContainer width="100%" height={280}>
      <PieChart>
        <Pie
          data={entries}
          cx="50%"
          cy="50%"
          innerRadius={60}
          outerRadius={100}
          paddingAngle={3}
          dataKey="value"
        >
          {entries.map((entry, i) => (
            <Cell key={entry.name} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip content={<CustomTooltip />} />
        <Legend wrapperStyle={{ color: '#94a3b8', fontSize: 12 }} />
      </PieChart>
    </ResponsiveContainer>
  );
}
