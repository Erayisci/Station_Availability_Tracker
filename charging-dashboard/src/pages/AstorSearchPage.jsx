import { useState, useCallback } from 'react';
import { fetchAstorSearch } from '../api/astorApi';

const PAGE_SIZE = 20;

const STATUS_OPTIONS = [
  { value: '',            label: 'Tümü',        color: '#64748b' },
  { value: 'available',   label: 'Available',   color: '#4ade80' },
  { value: 'inUse',    label: 'InUse',    color: '#38bdf8' },
  { value: 'faulted',     label: 'Faulted',     color: '#ef4444' },
  { value: 'offline',     label: 'Offline',     color: '#475569' },
];

const TENANT_OPTIONS = [
  { value: '',        label: 'Tümü',    color: '#64748b' },
  { value: 'astor',   label: 'ASTOR',   color: '#f97316' },
  { value: 'beefull', label: 'Beefull', color: '#a78bfa' },
];

const CONNECTOR_OPTIONS = [
  { value: '',   label: 'Tümü', color: '#64748b' },
  { value: 'ac', label: 'AC',   color: '#4ade80' },
  { value: 'dc', label: 'DC',   color: '#38bdf8' },
];

const STATUS_COLORS  = Object.fromEntries(STATUS_OPTIONS.map(o => [o.value || '_', o.color]));
const TENANT_COLORS  = Object.fromEntries(TENANT_OPTIONS.map(o => [o.value || '_', o.color]));
const CONNECTOR_COLORS = { ac: '#4ade80', dc: '#38bdf8' };

function TileGroup({ options, value, onChange }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
      {options.map(opt => {
        const active = value === opt.value;
        return (
          <button
            key={opt.value}
            onClick={() => onChange(opt.value)}
            style={{
              padding: '6px 14px', borderRadius: 7, border: 'none',
              background: active ? opt.color : '#1e293b',
              color: active ? '#0f172a' : opt.color,
              fontWeight: 700, fontSize: 12, cursor: 'pointer',
              transform: active ? 'scale(1.05)' : 'scale(1)',
              boxShadow: active ? `0 2px 10px ${opt.color}66` : 'none',
              transition: 'all 0.15s',
            }}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

function Badge({ color, label }) {
  return (
    <span style={{
      display: 'inline-block', padding: '2px 10px', borderRadius: 999,
      background: color + '22', color, border: `1px solid ${color}55`,
      fontSize: 11, fontWeight: 700, letterSpacing: '0.03em',
    }}>
      {label || '—'}
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
          {rows.map((a, i) => {
            const color = STATUS_COLORS[a.status] ?? '#64748b';
            return (
              <tr key={i} style={{ borderBottom: '1px solid #0f172a' }}>
                <td style={{ padding: '5px 12px' }}>
                  <Badge color={color} label={a.status} />
                </td>
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
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function ResultTable({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows || rows.length === 0) return <div className="loading">Sonuç yok</div>;

  function toggle(i) { setExpanded(prev => prev === i ? null : i); }

  return (
    <div className="table-wrapper">
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 24 }} />
            <th>İstasyon</th>
            <th>İl Kodu</th>
            <th>Adres</th>
            <th>Şarj Tipi</th>
            <th>Güç (kW)</th>
            <th>Marka</th>
            <th>Açılış</th>
            <th>Kapanış</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => {
            const isOpen = expanded === i;
            const avail  = Array.isArray(row.availability) ? row.availability : [];
            const tenantColor    = TENANT_COLORS[row.tenant_code] ?? '#64748b';
            const connectorColor = CONNECTOR_COLORS[row.connector_current_type] ?? '#64748b';
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
                  <td style={{ fontWeight: 600, color: '#e2e8f0' }}>{row.unit_name ?? '—'}</td>
                  <td style={{ color: '#94a3b8', fontFamily: 'monospace', fontSize: 12 }}>{row.location_code ?? '—'}</td>
                  <td style={{ maxWidth: 220, color: '#94a3b8', fontSize: 12 }}>{row.address ?? '—'}</td>
                  <td>
                    <Badge color={connectorColor} label={(row.connector_current_type ?? '').toUpperCase()} />
                  </td>
                  <td style={{ textAlign: 'right', color: '#facc15', fontWeight: 600 }}>{row.max_power ?? '—'}</td>
                  <td>
                    <Badge color={tenantColor} label={row.tenant_code ?? '—'} />
                  </td>
                  <td style={{ color: '#64748b', fontSize: 12 }}>{row.opens_at ?? '—'}</td>
                  <td style={{ color: '#64748b', fontSize: 12 }}>{row.closes_at ?? '—'}</td>
                </tr>
                {isOpen && (
                  <tr key={`avail-${i}`}>
                    <td />
                    <td colSpan={8} style={{ background: '#0f172a', padding: '12px 16px 16px' }}>
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

export default function AstorSearchPage({ date, onDateChange }) {
  const [filters, setFilters] = useState({
    status:               '',
    connectorCurrentType: '',
    tenantCode:           '',
    locationCode:         '',
    address:              '',
    time:                 '',
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
      const data = await fetchAstorSearch({
        date,
        status:               filters.status               || undefined,
        connectorCurrentType: filters.connectorCurrentType || undefined,
        tenantCode:           filters.tenantCode           || undefined,
        locationCode:         filters.locationCode         || undefined,
        address:              filters.address              || undefined,
        time:                 filters.time                 || undefined,
        from:                 currentPage * PAGE_SIZE,
        size:                 PAGE_SIZE,
      });
      setResults(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, filters, page]);

  function handleFilterChange(key, value) { setFilters(f => ({ ...f, [key]: value })); }
  function handleKeyDown(e) { if (e.key === 'Enter') { setPage(0); doSearch(0); } }
  function handleSearch() { setPage(0); doSearch(0); }
  function goPage(p) { setPage(p); doSearch(p); }

  const hasPrev = page > 0;
  const hasNext = results && results.length === PAGE_SIZE;

  return (
    <>
      <div className="card" style={{ marginBottom: 16 }}>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 20, marginBottom: 20 }}>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Durum
            </div>
            <TileGroup options={STATUS_OPTIONS} value={filters.status}
              onChange={v => handleFilterChange('status', v)} />
          </div>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Marka
            </div>
            <TileGroup options={TENANT_OPTIONS} value={filters.tenantCode}
              onChange={v => handleFilterChange('tenantCode', v)} />
          </div>
          <div>
            <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>
              Şarj Tipi
            </div>
            <TileGroup options={CONNECTOR_OPTIONS} value={filters.connectorCurrentType}
              onChange={v => handleFilterChange('connectorCurrentType', v)} />
          </div>
        </div>

        <div style={{ borderTop: '1px solid #1e293b', marginBottom: 16 }} />

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
            <label>İl Kodu</label>
            <input type="text" placeholder="örn. tr-06" value={filters.locationCode}
              onChange={e => handleFilterChange('locationCode', e.target.value)}
              onKeyDown={handleKeyDown} />
          </div>
          <div className="filter-field">
            <label>Adres</label>
            <input type="text" placeholder="örn. Atatürk" value={filters.address}
              onChange={e => handleFilterChange('address', e.target.value)}
              onKeyDown={handleKeyDown} />
          </div>
        </div>

        <div style={{ marginTop: 16 }}>
          <button onClick={handleSearch} disabled={loading} className="btn-search">
            {loading ? 'Aranıyor…' : 'Ara'}
          </button>
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
