import { useState, useMemo } from 'react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

const STATUS_COLORS = {
  Available:     '#4ade80',
  Charging:      '#f87171',
  Unavailable:   '#94a3b8',
  SuspendedEV:   '#fb923c',
  SuspendedEVSE: '#c084fc',
  Preparing:     '#38bdf8',
  Finishing:     '#facc15',
  Faulted:       '#ef4444',
  Reserved:      '#a78bfa',
  Unknown:       '#475569',
};

function colorFor(status) {
  return STATUS_COLORS[status] ?? '#64748b';
}

function transformTimeline(raw) {
  if (!Array.isArray(raw) || raw.length === 0) return { entries: [], statuses: [] };
  const allStatuses = new Set();
  const entries = raw.map(snap => {
    const { t, ...counts } = snap;
    Object.keys(counts).forEach(s => allStatuses.add(s));
    const ts = new Date(t).getTime();
    return { ts, label: formatTime(t), ...counts };
  });
  entries.sort((a, b) => a.ts - b.ts);
  // Append a synthetic terminal point at end-of-day so the last segment renders flat to 23:59
  const last = entries[entries.length - 1];
  const dayEnd = new Date(last.ts);
  dayEnd.setHours(23, 59, 59, 999);
  if (dayEnd.getTime() > last.ts) {
    const tail = { ...last, ts: dayEnd.getTime(), label: '23:59' };
    entries.push(tail);
  }
  // Fill missing statuses with 0 across all entries so stacked Area is consistent
  const statuses = Array.from(allStatuses);
  for (const e of entries) for (const s of statuses) if (e[s] === undefined) e[s] = 0;
  return { entries, statuses };
}

function formatTime(iso) {
  const d = new Date(iso);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

export default function TimelineChart({ data }) {
  const [hidden, setHidden] = useState(new Set());

  const { entries, statuses } = useMemo(() => transformTimeline(data), [data]);

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

      <ResponsiveContainer width="100%" height={320}>
        <AreaChart data={entries} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <defs>
            {visible.map(s => (
              <linearGradient key={s} id={`tlgrad-${s}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%"  stopColor={colorFor(s)} stopOpacity={0.75} />
                <stop offset="95%" stopColor={colorFor(s)} stopOpacity={0.45} />
              </linearGradient>
            ))}
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
          <XAxis
            dataKey="ts"
            type="number"
            scale="time"
            domain={['dataMin', 'dataMax']}
            tickFormatter={ts => {
              const d = new Date(ts);
              return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
            }}
            tick={{ fill: '#94a3b8', fontSize: 11 }}
          />
          <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
          <Tooltip
            contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }}
            labelStyle={{ color: '#e2e8f0' }}
            itemStyle={{ color: '#cbd5e1' }}
            labelFormatter={ts => {
              const d = new Date(ts);
              return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
            }}
          />
          <Legend wrapperStyle={{ color: '#94a3b8', fontSize: 12 }} />
          {visible.map(s => (
            <Area
              key={s}
              type="stepAfter"
              dataKey={s}
              stackId="status"
              stroke={colorFor(s)}
              fill={`url(#tlgrad-${s})`}
              strokeWidth={1.5}
              isAnimationActive={false}
            />
          ))}
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
