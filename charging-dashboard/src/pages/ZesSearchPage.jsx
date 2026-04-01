import { useState, useCallback } from 'react';
import { fetchZesConnectorSearch } from '../api/zesApi';

const PAGE_SIZE = 20;

const ENV_OPTIONS = [
  { value: '',            label: 'Tümü',        color: '#64748b' },
  { value: 'RETAIL',      label: 'Retail',       color: '#f87171' },
  { value: 'COMMERCIAL',  label: 'Commercial',   color: '#38bdf8' },
  { value: 'OFFICE',      label: 'Office',       color: '#facc15' },
  { value: 'RESIDENTIAL', label: 'Residential',  color: '#4ade80' },
  { value: 'UNKNOWN',     label: 'Unknown',      color: '#475569' },
];

const STATUS_OPTIONS = [
  { value: '',               label: 'Tümü',          color: '#64748b' },
  { value: 'Available',      label: 'Available',     color: '#4ade80' },
  { value: 'Charging',       label: 'Charging',      color: '#f87171' },
  { value: 'Unavailable',    label: 'Unavailable',   color: '#94a3b8' },
  { value: 'Faulted',        label: 'Faulted',       color: '#ef4444' },
  { value: 'Preparing',      label: 'Preparing',     color: '#38bdf8' },
  { value: 'Finishing',      label: 'Finishing',     color: '#facc15' },
  { value: 'Reserved',       label: 'Reserved',      color: '#a78bfa' },
  { value: 'SuspendedEV',    label: 'SuspendedEV',   color: '#fb923c' },
  { value: 'SuspendedEVSE',  label: 'SuspendedEVSE', color: '#c084fc' },
];

const ENV_COLORS = Object.fromEntries(ENV_OPTIONS.map(o => [o.value || 'UNKNOWN', o.color]));

function TileGroup({ options, value, onChange }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
      {options.map(opt => {
        const active = value === opt.value;
        const color  = opt.color;
        return (
          <button
            key={opt.value}
            onClick={() => onChange(opt.value)}
            style={{
              padding: '6px 14px',
              borderRadius: 7,
              border: 'none',
              background: active ? color : '#1e293b',
              color: active ? '#0f172a' : color,
              fontWeight: 700,
              fontSize: 12,
              cursor: 'pointer',
              outline: 'none',
              transition: 'background 0.15s, color 0.15s, transform 0.1s',
              transform: active ? 'scale(1.05)' : 'scale(1)',
              boxShadow: active ? `0 2px 10px ${color}66` : 'none',
              letterSpacing: '0.02em',
            }}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

function EnvBadge({ value }) {
  const color = ENV_COLORS[value] ?? '#64748b';
  return (
    <span style={{
      display: 'inline-block',
      padding: '2px 10px',
      borderRadius: 999,
      background: color + '22',
      color,
      border: `1px solid ${color}55`,
      fontSize: 11,
      fontWeight: 700,
      letterSpacing: '0.03em',
    }}>
      {value || '—'}
    </span>
  );
}

function ConnectorBadge({ value }) {
  const colors = { DC_CCS: '#38bdf8', DC_CHAdeMO: '#a78bfa', AC_Type_2: '#4ade80', AC_Type_2_Plug: '#fb923c' };
  const color  = colors[value] ?? '#94a3b8';
  return (
    <span style={{
      display: 'inline-block',
      padding: '2px 8px',
      borderRadius: 6,
      background: color + '22',
      color,
      fontSize: 11,
      fontWeight: 600,
    }}>
      {value || '—'}
    </span>
  );
}

const STATUS_COLORS = {
  Available:     '#4ade80',
  Charging:      '#f87171',
  Unavailable:   '#94a3b8',
  Faulted:       '#ef4444',
  Preparing:     '#38bdf8',
  Finishing:     '#facc15',
  Reserved:      '#a78bfa',
  SuspendedEV:   '#fb923c',
  SuspendedEVSE: '#c084fc',
};

function StatusBadge({ value }) {
  const color = STATUS_COLORS[value] ?? '#64748b';
  return (
    <span style={{
      display: 'inline-block', padding: '2px 9px', borderRadius: 999,
      background: color + '22', color, border: `1px solid ${color}55`,
      fontSize: 11, fontWeight: 700,
    }}>
      {value}
    </span>
  );
}

function AvailabilityPanel({ rows }) {
  if (!rows || rows.length === 0)
    return <div style={{ color: '#64748b', padding: '12px 0', fontSize: 13 }}>Availability verisi yok</div>;

  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
        <thead>
          <tr>
            {['Durum', 'Başlangıç', 'Bitiş', 'Süre (dk)', 'Time Slot'].map(h => (
              <th key={h} style={{
                padding: '6px 12px', textAlign: 'left', color: '#475569',
                fontWeight: 600, borderBottom: '1px solid #1e293b',
                textTransform: 'uppercase', fontSize: 10, letterSpacing: '0.05em',
              }}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((a, i) => (
            <tr key={i} style={{ borderBottom: '1px solid #0f172a' }}>
              <td style={{ padding: '5px 12px' }}><StatusBadge value={a.status} /></td>
              <td style={{ padding: '5px 12px', color: '#94a3b8', fontFamily: 'monospace' }}>
                {a.start_time ? a.start_time.replace('T', ' ').slice(0, 16) : '—'}
              </td>
              <td style={{ padding: '5px 12px', color: '#94a3b8', fontFamily: 'monospace' }}>
                {a.end_time ? a.end_time.replace('T', ' ').slice(0, 16) : <span style={{ color: '#475569' }}>devam ediyor</span>}
              </td>
              <td style={{ padding: '5px 12px', color: '#facc15', textAlign: 'right' }}>
                {a.duration_minutes != null ? Number(a.duration_minutes).toFixed(0) : '—'}
              </td>
              <td style={{ padding: '5px 12px', color: '#64748b', fontFamily: 'monospace' }}>
                {a.time_slot ?? '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ResultTable({ rows }) {
  const [expanded, setExpanded] = useState(null);

  if (!rows || rows.length === 0) return <div className="loading">Sonuç yok</div>;

  function toggle(i) {
    setExpanded(prev => prev === i ? null : i);
  }

  return (
    <div className="table-wrapper">
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 24 }} />
            <th>İstasyon</th>
            <th>İl / Şehir</th>
            <th>Adres</th>
            <th>Konnektör</th>
            <th>Güç (kW)</th>
            <th>No</th>
            <th>Açılış</th>
            <th>Kapanış</th>
            <th>Çevre Tipi</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => {
            const isOpen = expanded === i;
            const avail  = Array.isArray(row.availability) ? row.availability : [];
            return (
              <>
                <tr
                  key={`row-${i}`}
                  onClick={() => toggle(i)}
                  style={{ cursor: 'pointer', background: isOpen ? '#1a2744' : undefined }}
                >
                  <td style={{ textAlign: 'center', color: '#475569', fontSize: 11, userSelect: 'none' }}>
                    {isOpen ? '▾' : '▸'}
                  </td>
                  <td style={{ fontWeight: 600, color: '#e2e8f0' }}>{row.station_name ?? '—'}</td>
                  <td style={{ color: '#94a3b8' }}>{[row.province_name, row.city_name].filter(Boolean).join(' / ')}</td>
                  <td style={{ maxWidth: 220 }}>{row.address ?? '—'}</td>
                  <td><ConnectorBadge value={row.connector_type} /></td>
                  <td style={{ textAlign: 'right', color: '#facc15', fontWeight: 600 }}>{row.max_power_kw ?? '—'}</td>
                  <td style={{ textAlign: 'center', color: '#64748b' }}>{row.connector_no ?? '—'}</td>
                  <td style={{ color: '#64748b', fontSize: 12 }}>{row.opening_time ?? '—'}</td>
                  <td style={{ color: '#64748b', fontSize: 12 }}>{row.closing_time ?? '—'}</td>
                  <td><EnvBadge value={row.env_type} /></td>
                </tr>
                {isOpen && (
                  <tr key={`avail-${i}`}>
                    <td />
                    <td colSpan={9} style={{ background: '#0f172a', padding: '12px 16px 16px' }}>
                      <div style={{ color: '#64748b', fontSize: 11, marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                        Availability Geçmişi — {avail.length} kayıt
                      </div>
                      <AvailabilityPanel rows={avail} />
                    </td>
                  </tr>
                )}
              </>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function ZesSearchPage({ date, onDateChange }) {
  const [filters, setFilters] = useState({
    province:      '',
    connectorType: '',
    status:        '',
    time:          '',
    address:       '',
    envType:       '',
  });

  const [results, setResults]   = useState(null);
  const [page, setPage]         = useState(0);
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState(null);
  const [searched, setSearched] = useState(false);

  const doSearch = useCallback(async (pageOverride) => {
    const currentPage = pageOverride ?? page;
    setLoading(true);
    setError(null);
    setSearched(true);
    try {
      const data = await fetchZesConnectorSearch({
        date,
        province:      filters.province      || undefined,
        connectorType: filters.connectorType || undefined,
        status:        filters.status        || undefined,
        time:          filters.time          || undefined,
        address:       filters.address       || undefined,
        envType:       filters.envType       || undefined,
        from:          currentPage * PAGE_SIZE,
        size:          PAGE_SIZE,
      });
      setResults(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, filters, page]);

  function handleFilterChange(key, value) {
    setFilters(f => ({ ...f, [key]: value }));
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') { setPage(0); doSearch(0); }
  }

  function handleSearch() { setPage(0); doSearch(0); }
  function goPage(p)       { setPage(p); doSearch(p); }

  const hasPrev = page > 0;
  const hasNext = results && results.length === PAGE_SIZE;

  const activeEnv    = ENV_OPTIONS.find(o => o.value === filters.envType);
  const activeStatus = STATUS_OPTIONS.find(o => o.value === filters.status);

  return (
    <>
      <div className="card" style={{ marginBottom: 16 }}>

        {/* Öne çıkan filtreler */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20, marginBottom: 20 }}>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Çevre Tipi
            </div>
            <TileGroup
              options={ENV_OPTIONS}
              value={filters.envType}
              onChange={v => handleFilterChange('envType', v)}
            />
          </div>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Soket Durumu
            </div>
            <TileGroup
              options={STATUS_OPTIONS}
              value={filters.status}
              onChange={v => handleFilterChange('status', v)}
            />
          </div>
        </div>

        {/* Ayırıcı */}
        <div style={{ borderTop: '1px solid #1e293b', marginBottom: 16 }} />

        {/* İkincil filtreler */}
        <div className="filter-grid">
          <div className="filter-field">
            <label>Tarih</label>
            <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />
          </div>
          <div className="filter-field">
            <label>Saat</label>
            <input type="time" value={filters.time} step="60"
              onChange={e => handleFilterChange('time', e.target.value)}
              onKeyDown={handleKeyDown} />
          </div>
          <div className="filter-field">
            <label>İl</label>
            <input type="text" placeholder="örn. Ankara" value={filters.province}
              onChange={e => handleFilterChange('province', e.target.value)}
              onKeyDown={handleKeyDown} />
          </div>
          <div className="filter-field">
            <label>Adres</label>
            <input type="text" placeholder="örn. Atatürk Cad" value={filters.address}
              onChange={e => handleFilterChange('address', e.target.value)}
              onKeyDown={handleKeyDown} />
          </div>
          <div className="filter-field">
            <label>Konnektör Tipi</label>
            <select value={filters.connectorType} onChange={e => handleFilterChange('connectorType', e.target.value)}>
              <option value="">Tümü</option>
              <option value="DC_CCS">DC_CCS</option>
              <option value="DC_CHAdeMO">DC_CHAdeMO</option>
              <option value="AC_Type_2_Plug">AC_Type_2_Plug</option>
              <option value="AC_Type_2">AC_Type_2</option>
            </select>
          </div>
        </div>

        <div style={{ marginTop: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
          <button onClick={handleSearch} disabled={loading} className="btn-search">
            {loading ? 'Aranıyor…' : 'Ara'}
          </button>
          {(activeEnv?.value || activeStatus?.value) && (
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              {activeEnv?.value && (
                <span style={{ fontSize: 12, color: '#64748b' }}>
                  Çevre: <EnvBadge value={activeEnv.value} />
                </span>
              )}
              {activeStatus?.value && (
                <span style={{ fontSize: 12, color: '#64748b' }}>
                  Durum:{' '}
                  <span style={{
                    display: 'inline-block', padding: '2px 10px', borderRadius: 999,
                    background: (activeStatus.color) + '22', color: activeStatus.color,
                    border: `1px solid ${activeStatus.color}55`, fontSize: 11, fontWeight: 700,
                  }}>
                    {activeStatus.label}
                  </span>
                </span>
              )}
              {filters.time && (
                <span style={{ fontSize: 12, color: '#64748b' }}>
                  Saat: <strong style={{ color: '#e2e8f0' }}>{filters.time}</strong>
                </span>
              )}
            </div>
          )}
        </div>
      </div>

      {error && <div className="error" style={{ marginTop: 16 }}>{error}</div>}

      {searched && !loading && (
        <div className="card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
            <div>
              <span style={{ fontWeight: 600, color: '#e2e8f0', fontSize: 15 }}>Sonuçlar</span>
              {results && (
                <span style={{ marginLeft: 8, fontSize: 12, color: '#64748b' }}>
                  {results.length} kayıt — Sayfa {page + 1}
                </span>
              )}
            </div>
            <div className="pagination">
              <button onClick={() => goPage(page - 1)} disabled={!hasPrev || loading}>← Önceki</button>
              <span style={{ color: '#94a3b8', padding: '0 12px', fontSize: 13 }}>{page + 1}</span>
              <button onClick={() => goPage(page + 1)} disabled={!hasNext || loading}>Sonraki →</button>
            </div>
          </div>
          <ResultTable rows={results} />
        </div>
      )}
    </>
  );
}
