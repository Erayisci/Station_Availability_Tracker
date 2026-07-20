import { useState, useCallback } from 'react';
import { fetchZesConnectorSearch } from '../api/zesApi';
import { Badge, Button, FilterChip, EmptyState, SectionLabel } from '../components/ui';

const PAGE_SIZE = 20;

const ENV_OPTIONS = [
  { value: '',            label: 'Tümü',       color: '#64748b' },
  { value: 'RETAIL',      label: 'Retail',      color: '#f87171' },
  { value: 'COMMERCIAL',  label: 'Commercial',  color: '#60a5fa' },
  { value: 'OFFICE',      label: 'Office',      color: '#fbbf24' },
  { value: 'RESIDENTIAL', label: 'Residential', color: '#34d399' },
  { value: 'UNKNOWN',     label: 'Unknown',     color: '#64748b' },
];

const STATUS_OPTIONS = [
  { value: '',               label: 'Tümü',         color: '#64748b' },
  { value: 'Available',      label: 'Available',    color: '#10b981' },
  { value: 'Charging',       label: 'Charging',     color: '#3b82f6' },
  { value: 'Unavailable',    label: 'Unavailable',  color: '#94a3b8' },
  { value: 'Faulted',        label: 'Faulted',      color: '#ef4444' },
  { value: 'Preparing',      label: 'Preparing',    color: '#38bdf8' },
  { value: 'Finishing',      label: 'Finishing',    color: '#f59e0b' },
  { value: 'Reserved',       label: 'Reserved',     color: '#8b5cf6' },
  { value: 'SuspendedEV',    label: 'SuspendedEV',  color: '#f97316' },
  { value: 'SuspendedEVSE',  label: 'SuspendedEVSE',color: '#a78bfa' },
];

/* ── Availability sub-table ─────────────────────────────────────── */
function AvailabilityPanel({ rows }) {
  if (!rows || rows.length === 0)
    return (
      <div style={{ color: 'var(--tx-tertiary)', padding: '12px 0', fontSize: 13 }}>
        Bu konnektöre ait kayıt yok
      </div>
    );

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
              <td style={{ padding: '6px 12px' }}>
                <Badge value={a.status} />
              </td>
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
            <th>İl / Şehir</th>
            <th>Adres</th>
            <th>Konnektör</th>
            <th>Güç (kW)</th>
            <th>No</th>
            <th>Saat</th>
            <th>Çevre</th>
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
                  <td style={{ fontWeight: 600, color: 'var(--tx-primary)' }}>{row.station_name ?? '—'}</td>
                  <td>{[row.province_name, row.city_name].filter(Boolean).join(' / ') || '—'}</td>
                  <td style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {row.address ?? '—'}
                  </td>
                  <td><Badge value={row.connector_type} /></td>
                  <td style={{ textAlign: 'right', color: 'var(--clr-warning-text)', fontFamily: 'var(--font-mono)', fontWeight: 600 }}>
                    {row.max_power_kw ?? '—'}
                  </td>
                  <td style={{ textAlign: 'center', color: 'var(--tx-tertiary)', fontFamily: 'var(--font-mono)' }}>
                    {row.connector_no ?? '—'}
                  </td>
                  <td style={{ fontSize: 12, color: 'var(--tx-tertiary)', fontFamily: 'var(--font-mono)' }}>
                    {row.opening_time && row.closing_time
                      ? `${row.opening_time} – ${row.closing_time}`
                      : row.opening_time ?? '—'}
                  </td>
                  <td><Badge value={row.env_type ?? 'UNKNOWN'} /></td>
                </tr>
                {isOpen && (
                  <tr key={`avail-${i}`}>
                    <td />
                    <td colSpan={8} style={{ background: 'var(--bg-elevated)', padding: '14px 16px' }}>
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
export default function ZesSearchPage({ date, onDateChange }) {
  const [filters, setFilters] = useState({
    province: '', connectorType: '', status: '',
    time: '', address: '', envType: '',
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
      const data = await fetchZesConnectorSearch({
        date,
        province:      filters.province      || undefined,
        connectorType: filters.connectorType || undefined,
        status:        filters.status        || undefined,
        time:          filters.time          || undefined,
        address:       filters.address       || undefined,
        envType:       filters.envType       || undefined,
        from:          p * PAGE_SIZE,
        size:          PAGE_SIZE,
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
      {/* Filter card */}
      <div className="card">
        <div className="card-header">
          <div>
            <div className="card-title">Soket Ara</div>
            <div className="card-subtitle">Konnektör, durum ve çevre tipine göre filtrele</div>
          </div>
        </div>
        <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>

          {/* Tile filters */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 20 }}>
            <div>
              <SectionLabel>Çevre Tipi</SectionLabel>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {ENV_OPTIONS.map(o => (
                  <FilterChip key={o.value} label={o.label} color={o.color}
                    active={filters.envType === o.value}
                    onClick={() => set('envType', o.value)} />
                ))}
              </div>
            </div>
            <div>
              <SectionLabel>Soket Durumu</SectionLabel>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {STATUS_OPTIONS.map(o => (
                  <FilterChip key={o.value} label={o.label} color={o.color}
                    active={filters.status === o.value}
                    onClick={() => set('status', o.value)} />
                ))}
              </div>
            </div>
          </div>

          <div className="divider" />

          {/* Secondary filters */}
          <div className="filter-grid">
            <div className="filter-field">
              <label>Tarih</label>
              <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />
            </div>
            <div className="filter-field">
              <label>Saat</label>
              <input
                type="text"
                value={filters.time}
                placeholder="HH:MM"
                inputMode="numeric"
                maxLength={5}
                pattern="^([01][0-9]|2[0-3]):[0-5][0-9]$"
                title="24 saat formatı (00:00 – 23:59)"
                onChange={e => {
                  // Allow only digits + auto-insert ":" after 2 chars
                  let v = e.target.value.replace(/[^0-9:]/g, '');
                  if (v.length === 2 && !v.includes(':') && filters.time.length < 2) v += ':';
                  set('time', v.slice(0, 5));
                }}
                onKeyDown={handleKeyDown}
                style={{ fontFamily: 'var(--font-mono)' }} />
            </div>
            <div className="filter-field">
              <label>İl</label>
              <input type="text" placeholder="örn. Ankara" value={filters.province}
                onChange={e => set('province', e.target.value)} onKeyDown={handleKeyDown} />
            </div>
            <div className="filter-field">
              <label>Adres</label>
              <input type="text" placeholder="örn. Atatürk Cad" value={filters.address}
                onChange={e => set('address', e.target.value)} onKeyDown={handleKeyDown} />
            </div>
            <div className="filter-field">
              <label>Konnektör Tipi</label>
              <select value={filters.connectorType} onChange={e => set('connectorType', e.target.value)}>
                <option value="">Tümü</option>
                <option value="DC_CCS">DC CCS</option>
                <option value="DC_CHAdeMO">DC CHAdeMO</option>
                <option value="AC_Type_2_Plug">AC Type 2 Plug</option>
                <option value="AC_Type_2">AC Type 2</option>
              </select>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <Button onClick={handleSearch} disabled={loading} variant="primary" size="lg">
              {loading ? 'Aranıyor…' : 'Ara'}
            </Button>
            {filters.time && (
              <span style={{ fontSize: 12, color: 'var(--tx-tertiary)' }}>
                Saat filtresi: <strong style={{ color: 'var(--tx-primary)', fontFamily: 'var(--font-mono)' }}>{filters.time}</strong>
              </span>
            )}
          </div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {/* Results */}
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
