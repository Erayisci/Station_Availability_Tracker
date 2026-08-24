import { useState, useMemo } from 'react';
import {
  LineChart, Line, AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer
} from 'recharts';

// Charging-focused color scheme (monochrome + single accent)
const CHARGING_COLORS = {
  Charging: '#10b981',  // Primary accent - emerald
  charging: '#10b981',
  Other: '#475569',     // Muted gray for non-charging states
};

function transformData(raw, chargingOnly = true) {
  const allStatuses = new Set();
  const entries = Object.entries(raw).map(([time, counts]) => {
    if (chargingOnly) {
      // Aggregate all non-Charging states into "Other"
      const chargingCount = counts.Charging || counts.charging || 0;
      const otherCount = Object.entries(counts)
        .filter(([status]) => status !== 'Charging' && status !== 'charging')
        .reduce((sum, [, count]) => sum + count, 0);

      return {
        time,
        Charging: chargingCount,
        Other: otherCount,
        Total: chargingCount + otherCount,
      };
    } else {
      Object.keys(counts).forEach(s => allStatuses.add(s));
      return { time, ...counts };
    }
  });

  entries.sort((a, b) => a.time.localeCompare(b.time));
  return {
    entries,
    statuses: chargingOnly ? ['Charging', 'Other'] : Array.from(allStatuses)
  };
}

export default function TimeSlotChart({ data, colors, showChargingOnly = true }) {
  const [viewMode, setViewMode] = useState('area'); // 'area' or 'line'

  const { entries, statuses } = useMemo(() => {
    if (!data) return { entries: [], statuses: [] };
    return transformData(data, showChargingOnly);
  }, [data, showChargingOnly]);

  if (!data) return <div className="loading">Yükleniyor…</div>;
  if (entries.length === 0) return <div className="loading">Veri yok</div>;

  const ChartComponent = viewMode === 'area' ? AreaChart : LineChart;

  return (
    <div>
      {/* View mode toggle */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 'var(--space-4)'
      }}>
        <div style={{
          display: 'flex',
          gap: 'var(--space-2)',
          background: 'var(--bg-tertiary)',
          padding: 'var(--space-1)',
          borderRadius: 'var(--radius-sm)',
          border: '1px solid var(--border-subtle)'
        }}>
          <button
            onClick={() => setViewMode('area')}
            style={{
              padding: 'var(--space-1) var(--space-3)',
              border: 'none',
              borderRadius: 'var(--radius-sm)',
              background: viewMode === 'area' ? 'var(--accent-primary)' : 'transparent',
              color: viewMode === 'area' ? '#fff' : 'var(--text-muted)',
              fontSize: 'var(--text-xs)',
              fontWeight: 600,
              cursor: 'pointer',
              transition: 'all var(--transition-fast)',
            }}
          >
            Area
          </button>
          <button
            onClick={() => setViewMode('line')}
            style={{
              padding: 'var(--space-1) var(--space-3)',
              border: 'none',
              borderRadius: 'var(--radius-sm)',
              background: viewMode === 'line' ? 'var(--accent-primary)' : 'transparent',
              color: viewMode === 'line' ? '#fff' : 'var(--text-muted)',
              fontSize: 'var(--text-xs)',
              fontWeight: 600,
              cursor: 'pointer',
              transition: 'all var(--transition-fast)',
            }}
          >
            Line
          </button>
        </div>

        {/* Legend */}
        <div style={{ display: 'flex', gap: 'var(--space-3)', alignItems: 'center' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
            <div style={{
              width: 12,
              height: 12,
              borderRadius: '50%',
              background: CHARGING_COLORS.Charging,
              boxShadow: `0 0 8px ${CHARGING_COLORS.Charging}66`
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
                borderRadius: '50%',
                background: CHARGING_COLORS.Other
              }} />
              <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', fontWeight: 600 }}>
                Other
              </span>
            </div>
          )}
        </div>
      </div>

      <ResponsiveContainer width="100%" height={360}>
        <ChartComponent data={entries} margin={{ top: 10, right: 20, left: 0, bottom: 20 }}>
          <defs>
            <linearGradient id="gradCharging" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#10b981" stopOpacity={0.4} />
              <stop offset="95%" stopColor="#10b981" stopOpacity={0.05} />
            </linearGradient>
            <linearGradient id="gradOther" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#475569" stopOpacity={0.2} />
              <stop offset="95%" stopColor="#475569" stopOpacity={0.02} />
            </linearGradient>
          </defs>

          <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" />

          <XAxis
            dataKey="time"
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            stroke="var(--border-medium)"
            interval="preserveStartEnd"
          />

          <YAxis
            yAxisId="other"
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            stroke="var(--border-medium)"
          />
          <YAxis
            yAxisId="charging"
            orientation="right"
            tick={{ fill: '#10b981', fontSize: 11 }}
            stroke="#10b98166"
            width={48}
          />

          <Tooltip
            contentStyle={{
              background: 'var(--bg-secondary)',
              border: '1px solid var(--border-medium)',
              borderRadius: 'var(--radius-md)',
              boxShadow: 'var(--shadow-lg)',
              padding: 'var(--space-3)'
            }}
            labelStyle={{
              color: 'var(--text-primary)',
              fontWeight: 600,
              marginBottom: 'var(--space-2)'
            }}
            itemStyle={{
              color: 'var(--text-secondary)',
              fontSize: 'var(--text-sm)',
              padding: 'var(--space-1) 0'
            }}
          />

          {viewMode === 'area' ? (
            <>
              <Area
                yAxisId="charging"
                type="monotone"
                dataKey="Charging"
                stroke="#10b981"
                fill="url(#gradCharging)"
                strokeWidth={3}
                dot={false}
                activeDot={{ r: 6, fill: '#10b981', stroke: '#fff', strokeWidth: 2 }}
              />
              {showChargingOnly && (
                <Area
                  yAxisId="other"
                  type="monotone"
                  dataKey="Other"
                  stroke="#475569"
                  fill="url(#gradOther)"
                  strokeWidth={1.5}
                  strokeDasharray="5 5"
                  dot={false}
                />
              )}
            </>
          ) : (
            <>
              <Line
                yAxisId="charging"
                type="monotone"
                dataKey="Charging"
                stroke="#10b981"
                strokeWidth={3}
                dot={{ fill: '#10b981', r: 3 }}
                activeDot={{ r: 6, fill: '#10b981', stroke: '#fff', strokeWidth: 2 }}
              />
              {showChargingOnly && (
                <Line
                  yAxisId="other"
                  type="monotone"
                  dataKey="Other"
                  stroke="#475569"
                  strokeWidth={1.5}
                  strokeDasharray="5 5"
                  dot={false}
                />
              )}
            </>
          )}
        </ChartComponent>
      </ResponsiveContainer>
    </div>
  );
}
