import React from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

const STATUS_COLORS = {
  AVAILABLE: '#4ade80',
  FREE:      '#4ade80',
  IN_USE:    '#f87171',
  OCCUPIED:  '#f87171',
  RESERVED:  '#facc15',
  UNKNOWN:   '#94a3b8',
};

function transformData(raw) {
  const allStatuses = new Set();
  const entries = Object.entries(raw).map(([brand, counts]) => {
    Object.keys(counts).forEach(s => allStatuses.add(s));
    const total = Object.values(counts).reduce((a, b) => a + b, 0);
    return { brand, ...counts, _total: total };
  });
  entries.sort((a, b) => b._total - a._total);
  return { entries: entries.slice(0, 15), statuses: Array.from(allStatuses) };
}

export default function BrandChart({ data }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;

  const { entries, statuses } = transformData(data);

  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={entries} layout="vertical" margin={{ top: 5, right: 30, left: 60, bottom: 5 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
        <XAxis type="number" tick={{ fill: '#94a3b8', fontSize: 11 }} />
        <YAxis dataKey="brand" type="category" tick={{ fill: '#94a3b8', fontSize: 11 }} width={80} />
        <Tooltip
          contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }}
          labelStyle={{ color: '#e2e8f0' }}
          itemStyle={{ color: '#cbd5e1' }}
        />
        <Legend wrapperStyle={{ color: '#94a3b8', fontSize: 12 }} />
        {statuses.map(s => (
          <Bar key={s} dataKey={s} stackId="a" fill={STATUS_COLORS[s] ?? '#94a3b8'} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  );
}
