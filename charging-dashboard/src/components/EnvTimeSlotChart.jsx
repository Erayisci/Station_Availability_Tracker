import { useState, useMemo } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

const ENV_COLORS = {
  RETAIL:      '#f87171',
  COMMERCIAL:  '#38bdf8',
  OFFICE:      '#facc15',
  RESIDENTIAL: '#4ade80',
  UNKNOWN:     '#64748b',
};

const STATUSES = ['Charging', 'Available', 'Unavailable', 'Faulted', 'SuspendedEV', 'SuspendedEVSE', 'Preparing', 'Finishing', 'Reserved'];

const STATUS_COLORS = {
  Charging:      '#f87171',
  Available:     '#4ade80',
  Unavailable:   '#94a3b8',
  Faulted:       '#ef4444',
  SuspendedEV:   '#fb923c',
  SuspendedEVSE: '#c084fc',
  Preparing:     '#38bdf8',
  Finishing:     '#facc15',
  Reserved:      '#a78bfa',
};

function colorFor(env) {
  return ENV_COLORS[env] ?? '#94a3b8';
}

function transformData(raw, status, percent) {
  const envTypes = Object.keys(raw);
  const slotSet  = new Set();
  envTypes.forEach(env => Object.keys(raw[env]).forEach(slot => slotSet.add(slot)));

  const slots = Array.from(slotSet).sort();

  return slots.map(slot => {
    const entry = { time: slot };
    envTypes.forEach(env => {
      const counts = raw[env]?.[slot] ?? {};
      const val    = counts[status] ?? 0;
      if (percent) {
        const total = Object.values(counts).reduce((s, v) => s + v, 0);
        entry[env]  = total > 0 ? parseFloat(((val / total) * 100).toFixed(1)) : 0;
      } else {
        entry[env] = val;
      }
    });
    return entry;
  });
}

export default function EnvTimeSlotChart({ data, percent = true }) {
  const [status, setStatus] = useState('Charging');
  const [hidden, setHidden] = useState(new Set());

  const envTypes = useMemo(() => (data ? Object.keys(data) : []), [data]);
  const entries  = useMemo(() => {
    if (!data || envTypes.length === 0) return [];
    return transformData(data, status, percent);
  }, [data, envTypes, status, percent]);

  if (!data)                return <div className="loading">Yükleniyor…</div>;
  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  function toggleEnv(env) {
    setHidden(prev => {
      const next = new Set(prev);
      next.has(env) ? next.delete(env) : next.add(env);
      return next;
    });
  }

  const visible = envTypes.filter(e => !hidden.has(e));

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{
            fontSize: 10, color: '#64748b', textTransform: 'uppercase',
            letterSpacing: '0.06em', fontWeight: 600,
          }}>
            Durum
          </span>
          <select
            value={status}
            onChange={e => setStatus(e.target.value)}
            style={{
              background: '#0f172a',
              border: `2px solid ${STATUS_COLORS[status] ?? '#334155'}`,
              borderRadius: 8,
              color: STATUS_COLORS[status] ?? '#e2e8f0',
              fontSize: 13,
              fontWeight: 700,
              padding: '5px 12px',
              outline: 'none',
              cursor: 'pointer',
              boxShadow: `0 0 8px ${(STATUS_COLORS[status] ?? '#334155')}44`,
              transition: 'border-color 0.15s, box-shadow 0.15s',
            }}
          >
            {STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {envTypes.map(env => (
            <button
              key={env}
              onClick={() => toggleEnv(env)}
              style={{
                padding: '3px 10px',
                borderRadius: 999,
                border: `2px solid ${colorFor(env)}`,
                background: hidden.has(env) ? 'transparent' : colorFor(env) + '33',
                color: hidden.has(env) ? '#64748b' : colorFor(env),
                cursor: 'pointer',
                fontSize: 12,
                fontWeight: 600,
                opacity: hidden.has(env) ? 0.45 : 1,
                transition: 'all 0.15s',
              }}
            >
              {env}
            </button>
          ))}
        </div>
      </div>

      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={entries} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
          <XAxis dataKey="time" tick={{ fill: '#94a3b8', fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis
            tick={{ fill: '#94a3b8', fontSize: 11 }}
            tickFormatter={v => percent ? `${v}%` : v.toLocaleString()}
            domain={percent ? [0, 100] : ['auto', 'auto']}
          />
          <Tooltip
            formatter={(value, name) => [percent ? `${value}%` : value.toLocaleString(), name]}
            contentStyle={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8 }}
            labelStyle={{ color: '#e2e8f0' }}
            itemStyle={{ color: '#cbd5e1' }}
          />
          <Legend
            formatter={value => (
              <span style={{ color: colorFor(value), fontWeight: 600 }}>{value}</span>
            )}
          />
          {visible.map(env => (
            <Line
              key={env}
              type="monotone"
              dataKey={env}
              stroke={colorFor(env)}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4 }}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
