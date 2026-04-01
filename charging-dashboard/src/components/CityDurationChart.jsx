import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
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
};

function colorFor(status) {
  return STATUS_COLORS[status] ?? '#64748b';
}

function toHours(minutes) {
  return Math.round(minutes / 60);
}

function transformData(raw) {
  const allStatuses = new Set();
  const entries = Object.entries(raw).map(([city, durations]) => {
    const row = { city };
    for (const [status, minutes] of Object.entries(durations)) {
      allStatuses.add(status);
      row[status] = toHours(minutes);
    }
    return row;
  });
  return { entries, statuses: Array.from(allStatuses) };
}

const customTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null;
  return (
    <div style={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 8, padding: '8px 12px' }}>
      <p style={{ color: '#e2e8f0', marginBottom: 4, fontWeight: 600 }}>{label}</p>
      {payload.map(p => (
        <p key={p.dataKey} style={{ color: p.fill, margin: '2px 0', fontSize: 12 }}>
          {p.dataKey}: {p.value.toLocaleString()} saat
        </p>
      ))}
    </div>
  );
};

export default function CityDurationChart({ data }) {
  if (!data) return <div className="loading">Yükleniyor…</div>;

  const { entries, statuses } = transformData(data);

  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  return (
    <ResponsiveContainer width="100%" height={340}>
      <BarChart data={entries} margin={{ top: 5, right: 20, left: 0, bottom: 50 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
        <XAxis
          dataKey="city"
          tick={{ fill: '#94a3b8', fontSize: 10 }}
          angle={-45}
          textAnchor="end"
          interval={0}
        />
        <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} unit="sa" />
        <Tooltip content={customTooltip} />
        <Legend wrapperStyle={{ color: '#94a3b8', fontSize: 12 }} />
        {statuses.map((s, i) => (
          <Bar
            key={s}
            dataKey={s}
            stackId="a"
            fill={colorFor(s)}
            radius={i === statuses.length - 1 ? [4, 4, 0, 0] : [0, 0, 0, 0]}
          />
        ))}
      </BarChart>
    </ResponsiveContainer>
  );
}
