import { useState, useCallback } from 'react';
import { fetchAstorSearch } from '../api/astorApi';
import { Badge, Button, FilterChip, EmptyState, SectionLabel } from '../components/ui';

const PAGE_SIZE = 20;

const STATUS_OPTIONS = [
  { value: '',          label: 'Tümü',     color: '#64748b' },
  { value: 'available', label: 'Available', color: '#10b981' },
  { value: 'inUse',     label: 'In Use',   color: '#3b82f6' },
  { value: 'faulted',   label: 'Faulted',  color: '#ef4444' },
  { value: 'offline',   label: 'Offline',  color: '#64748b' },
];

const TENANT_OPTIONS = [
  { value: '',        label: 'Tümü',   color: '#64748b' },
  { value: 'astor',   label: 'ASTOR',  color: '#f97316' },
  { value: 'beefull', label: 'Beefull',color: '#8b5cf6' },
];

const CONNECTOR_OPTIONS = [
  { value: '',   label: 'Tümü', color: '#64748b' },
  { value: 'ac', label: 'AC',   color: '#10b981' },
  { value: 'dc', label: 'DC',   color: '#3b82f6' },
];

/* ── Availability sub-table ─────────────────────────────────────── */
function AvailabilityPanel({ rows }) {
  if (!rows || rows.length === 0)
    return <div style={{ color: 'var(--tx-tertiary)', padding: '12px 0', fontSize: 13 }}>Bu istasyona ait kayıt yok</div>;

  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
        <thead>
          <tr>
            {['Durum', 'Başlangıç', 'Bitiş', 'Süre (dk)', 'Zaman Dilimi'].map(h => (
              <th key={h} style={{
                padding: '6px 12px', textAlign: 'left', color: 'var(--tx-disabled)',
                fontWeight: 600, borderBottom: '1px solid var(--bd-subtle)',
                textTransform: 'uppercase', fontSize: 10, letterSpacing: '0.06em',
              }}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((a, i) => (
            <tr key={i} style={{ borderBottom: '1px solid var(--bd-subtle)' }}>
              <td style={{ padding: '6px 12px' }}><Badge value={a.status} /></td>
              <td style={{ padding: '6px 12px', color: 'var(--tx-secondary)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                {a.start_time ? a.start_time.replace('T', ' ').slice(0, 16) : '—'}
              </td>
              <td style={{ padding: '6px 12px', fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                {a.end_time
                  ? <span style={{ color: 'var(--tx-secondary)' }}>{a.end_time.replace('T', ' ').slice(0, 16)}</span>
                  : <span style={{ color: 'var(--clr-success-text)', fontSize: 11 }}>devam ediyor</span>}
              </td>
              <td style={{ padding: '6px 12px', color: 'var(--clr-warning-text)', textAlign: 'right', fontFamily: 'var(--font-mono)', fontWeight: 600 }}>
                {a.duration_minutes != null ? Number(a.duration_minutes).toFixed(0) : '—'}
              </td>
              <td style={{ padding: '6px 12px', color: 'var(--tx-tertiary)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                {a.time_slot ?? '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ── Result table ───────────────────────────────────────────────── */
function ResultTable({ rows }) {
  const [expanded, setExpanded] = useState(null);
  if (!rows || rows.length === 0)
    return <EmptyState title="Sonuç bulunamadı" description="Filtrelerinizi değiştirerek tekrar deneyin" />;

  return (
    <div className="table-wrapper">
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 32 }} />
            <th>İstasyon</th>
            <th>İl Kodu</th>
            <th>Adres</th>
            <th>Şarj Tipi</th>
            <th>Güç (kW)</th>
            <th>Marka</th>
            <th>Saat</th>
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
                  onClick={() => setExpanded(p => p === i ? null : i)}
                  style={{ cursor: 'pointer' }}
                >
                  <td style={{ textAlign: 'center', color: 'var(--tx-disabled)', fontSize: 11, userSelect: 'none' }}>
                    <span style={{ display: 'inline-block', transition: 'transform 150ms', transform: isOpen ? 'rotate(90deg)' : 'none' }}>▶</span>
                  </td>
                  <td style={{ fontWeight: 600, color: 'var(--tx-primary)' }}>{row.unit_name ?? '—'}</td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{row.location_code ?? '—'}</td>
                  <td style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 12 }}>
                    {row.address ?? '—'}
                  </td>
                  <td>
                    <Badge value={row.connector_current_type ?? 'ac'} label={(row.connector_current_type ?? '').toUpperCase()} />
                  </td>
                  <td style={{ textAlign: 'right', color: 'var(--clr-warning-text)', fontFamily: 'var(--font-mono)', fontWeight: 600 }}>
                    {row.max_power ?? '—'}
                  </td>
                  <td>
                    <Badge value={row.tenant_code ?? 'astor'} label={row.tenant_code ?? '—'} />
                  </td>
                  <td style={{ fontSize: 12, color: 'var(--tx-tertiary)', fontFamily: 'var(--font-mono)' }}>
                    {row.opens_at && row.closes_at
                      ? `${row.opens_at} – ${row.closes_at}`
                      : row.opens_at ?? '—'}
                  </td>
                </tr>
                {isOpen && (
                  <tr key={`avail-${i}`}>
                    <td />
                    <td colSpan={7} style={{ background: 'var(--bg-elevated)', padding: '14px 16px' }}>
                      <div style={{ fontSize: 11, color: 'var(--tx-tertiary)', marginBottom: 10, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                        Availability geçmişi — {avail.length} kayıt
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

/* ══ Main Component ════════════════════════════════════════════════ */
export default function AstorSearchPage({ date, onDateChange }) {
  const [filters, setFilters] = useState({
    status: '', connectorCurrentType: '', tenantCode: '',
    locationCode: '', address: '', time: '',
  });
  const [results,  setResults]  = useState(null);
  const [page,     setPage]     = useState(0);
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState(null);
  const [searched, setSearched] = useState(false);

  const doSearch = useCallback(async (pageOverride) => {
    const p = pageOverride ?? page;
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
        from:                 p * PAGE_SIZE,
        size:                 PAGE_SIZE,
      });
      setResults(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [date, filters, page]);

  function set(key, val) { setFilters(f => ({ ...f, [key]: val })); }
  function handleKeyDown(e) { if (e.key === 'Enter') { setPage(0); doSearch(0); } }
  function handleSearch()   { setPage(0); doSearch(0); }
  function goPage(p)        { setPage(p); doSearch(p); }

  const hasPrev = page > 0;
  const hasNext = results && results.length === PAGE_SIZE;

  return (
    <>
      <div className="card">
        <div className="card-header">
          <div>
            <div className="card-title">İstasyon Ara</div>
            <div className="card-subtitle">Durum, marka ve şarj tipine göre filtrele</div>
          </div>
        </div>
        <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>

          {/* Tile filters */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 20 }}>
            <div>
              <SectionLabel>Durum</SectionLabel>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {STATUS_OPTIONS.map(o => (
                  <FilterChip key={o.value} label={o.label} color={o.color}
                    active={filters.status === o.value}
                    onClick={() => set('status', o.value)} />
                ))}
              </div>
            </div>
            <div>
              <SectionLabel>Marka</SectionLabel>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {TENANT_OPTIONS.map(o => (
                  <FilterChip key={o.value} label={o.label} color={o.color}
                    active={filters.tenantCode === o.value}
                    onClick={() => set('tenantCode', o.value)} />
                ))}
              </div>
            </div>
            <div>
              <SectionLabel>Şarj Tipi</SectionLabel>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {CONNECTOR_OPTIONS.map(o => (
                  <FilterChip key={o.value} label={o.label} color={o.color}
                    active={filters.connectorCurrentType === o.value}
                    onClick={() => set('connectorCurrentType', o.value)} />
                ))}
              </div>
            </div>
          </div>

          <div className="divider" />

          {/* Text filters */}
          <div className="filter-grid">
            <div className="filter-field">
              <label>Tarih</label>
              <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />
            </div>
            <div className="filter-field">
              <label>Saat</label>
              <input type="time" value={filters.time} step="60"
                onChange={e => set('time', e.target.value)} onKeyDown={handleKeyDown} />
            </div>
            <div className="filter-field">
              <label>İl Kodu</label>
              <input type="text" placeholder="örn. tr-06" value={filters.locationCode}
                onChange={e => set('locationCode', e.target.value)} onKeyDown={handleKeyDown} />
            </div>
            <div className="filter-field">
              <label>Adres</label>
              <input type="text" placeholder="örn. Atatürk" value={filters.address}
                onChange={e => set('address', e.target.value)} onKeyDown={handleKeyDown} />
            </div>
          </div>

          <div>
            <Button onClick={handleSearch} disabled={loading} variant="primary" size="lg">
              {loading ? 'Aranıyor…' : 'Ara'}
            </Button>
          </div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {searched && !loading && (
        <div className="card">
          <div className="card-header">
            <div>
              <div className="card-title">Sonuçlar</div>
              {results && (
                <div className="card-subtitle">{results.length} kayıt — {page + 1}. sayfa</div>
              )}
            </div>
            <div className="pagination">
              <button onClick={() => goPage(page - 1)} disabled={!hasPrev || loading}>← Önceki</button>
              <span style={{ color: 'var(--tx-tertiary)', padding: '0 8px', fontSize: 12, fontFamily: 'var(--font-mono)' }}>
                {page + 1}
              </span>
              <button onClick={() => goPage(page + 1)} disabled={!hasNext || loading}>Sonraki →</button>
            </div>
          </div>
          <ResultTable rows={results} />
        </div>
      )}
    </>
  );
}
