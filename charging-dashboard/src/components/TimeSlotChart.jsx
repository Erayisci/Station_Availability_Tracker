import { useState, useMemo } from 'react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

const STATUS_COLORS = {
  // ZES — PascalCase
  Available:     '#4ade80',
  Charging:      '#f87171',
  Unavailable:   '#94a3b8',
  SuspendedEV:   '#fb923c',
  SuspendedEVSE: '#c084fc',
  Preparing:     '#38bdf8',
  Finishing:     '#facc15',
  Faulted:       '#ef4444',
  Reserved:      '#a78bfa',
  // ASTOR — lowercase
  available:     '#4ade80',
  charging:      '#38bdf8',
  faulted:       '#ef4444',
  offline:       '#475569',
  unavailable:   '#94a3b8',
};

function makeColorFor(overrides = {}) {
  const merged = { ...STATUS_COLORS, ...overrides };
  return (status) => merged[status] ?? '#64748b';
}

function transformData(raw) {
  const allStatuses = new Set();
  const entries = Object.entries(raw).map(([time, counts]) => {
    Object.keys(counts).forEach(s => allStatuses.add(s));
    return { time, ...counts };
  });
  entries.sort((a, b) => a.time.localeCompare(b.time));
  return { entries, statuses: Array.from(allStatuses) };
}

export default function TimeSlotChart({ data, colors }) {
  const [hidden, setHidden] = useState(new Set());
  const colorFor = useMemo(() => makeColorFor(colors), [colors]);

  const { entries, statuses } = useMemo(() => {
    if (!data) return { entries: [], statuses: [] };
    return transformData(data);
  }, [data]);

  if (!data) return <div className="loading">Yükleniyor…</div>;
  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  function toggle(status) {
    setHidden(prev => {
      const next = new Set(prev);
      next.has(status) ? next.delete(status) : next.add(status);
      return next;
    });
  }

  const visible = statuses.filter(s => !hidden.has(s));

  return (
    <div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
        {statuses.map(s => (
          <button
            key={s}
            onClick={() => toggle(s)}
            style={{
              padding: '3px 10px',
              borderRadius: 999,
              border: `2px solid ${colorFor(s)}`,
              background: hidden.has(s) ? 'transparent' : colorFor(s) + '33',
              color: hidden.has(s) ? '#64748b' : colorFor(s),
              cursor: 'pointer',
              fontSize: 12,
              fontWeight: 600,
              opacity: hidden.has(s) ? 0.45 : 1,
              transition: 'all 0.15s',
            }}
          >
            {s}
          </button>
        ))}
      </div>

      <ResponsiveContainer width="100%" height={300}>
        <AreaChart data={entries} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <defs>
            {visible.map(s => (
              <linearGradient key={s} id={`grad-${s}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%"  stopColor={colorFor(s)} stopOpacity={0.75} />
                <stop offset="95%" stopColor={colorFor(s)} stopOpacity={0.45} />
              </linearGradient>
            ))}
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
          <XAxis dataKey="time" tick={{ fill: '#94a3b8', fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
          <Tooltip
            contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }}
            labelStyle={{ color: '#e2e8f0' }}
            itemStyle={{ color: '#cbd5e1' }}
          />
          <Legend wrapperStyle={{ color: '#94a3b8', fontSize: 12 }} />
          {visible.map(s => (
            <Area
              key={s}
              type="monotone"
              dataKey={s}
              stackId="status"
              stroke={colorFor(s)}
              fill={`url(#grad-${s})`}
              strokeWidth={2}
            />
          ))}
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
