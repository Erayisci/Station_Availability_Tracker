import { useState, useMemo } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

const ENV_COLORS = {
  RETAIL:      '#f87171',
  COMMERCIAL:  '#60a5fa',
  OFFICE:      '#fbbf24',
  RESIDENTIAL: '#34d399',
  UNKNOWN:     '#94a3b8',
};

const STATUSES = ['Charging', 'Available', 'Unavailable', 'Faulted', 'SuspendedEV', 'SuspendedEVSE', 'Preparing', 'Finishing', 'Reserved'];

const ENV_PALETTE = ['#34d399', '#60a5fa', '#fbbf24', '#f87171', '#8b5cf6'];

function colorFor(env, idx = 0) {
  return ENV_COLORS[env] ?? ENV_PALETTE[idx % ENV_PALETTE.length];
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
            className="ctrl-select"
            style={{ fontSize: 12 }}
          >
            {STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {envTypes.map((env, i) => {
            const c = colorFor(env, i);
            return (
              <button
                key={env}
                onClick={() => toggleEnv(env)}
                style={{
                  padding: '3px 10px',
                  borderRadius: 3,
                  border: `1px solid ${hidden.has(env) ? '#1a2535' : c + '55'}`,
                  background: hidden.has(env) ? 'transparent' : c + '14',
                  color: hidden.has(env) ? '#3d5268' : c,
                  cursor: 'pointer',
                  fontSize: 11,
                  fontFamily: 'var(--font-data)',
                  fontWeight: 600,
                  transition: 'all 0.15s',
                }}
              >
                {env}
              </button>
            );
          })}
        </div>
      </div>

      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={entries} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#1a2535" />
          <XAxis dataKey="time" tick={{ fill: '#3d5268', fontSize: 11 }} interval="preserveStartEnd" />
          <YAxis
            tick={{ fill: '#3d5268', fontSize: 11 }}
            tickFormatter={v => percent ? `${v}%` : v.toLocaleString()}
            domain={percent ? [0, 100] : ['auto', 'auto']}
          />
          <Tooltip
            formatter={(value, name) => [percent ? `${value}%` : value.toLocaleString(), name]}
            contentStyle={{ background: '#0f1623', border: '1px solid #233047', borderRadius: 5, fontSize: 12 }}
            labelStyle={{ color: '#eef2f7' }}
            itemStyle={{ color: '#7d93a8' }}
          />
          <Legend
            formatter={(value, _, i) => (
              <span style={{ color: colorFor(value, i), fontWeight: 600, fontSize: 11, fontFamily: 'var(--font-data)' }}>
                {value}
              </span>
            )}
          />
          {visible.map((env, i) => (
            <Line
              key={env}
              type="monotone"
              dataKey={env}
              stroke={colorFor(env, i)}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, stroke: '#080d18', strokeWidth: 2 }}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
