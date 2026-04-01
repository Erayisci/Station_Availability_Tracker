import React, { useState, useCallback } from 'react';
import { fetchSearch } from '../api/availabilityApi';

const PAGE_SIZE = 20;

const SORT_OPTIONS = [
  { value: 'id',         label: 'ID' },
  { value: 'brand',      label: 'Marka' },
  { value: 'cityid',     label: 'Şehir' },
  { value: 'districtid', label: 'İlçe' },
];

function ResultTable({ rows }) {
  if (!rows || rows.length === 0) return <div className="loading">Sonuç yok</div>;

  // Collect top-level scalar keys from first row (skip arrays/objects)
  const keys = Object.keys(rows[0]).filter(k => {
    const v = rows[0][k];
    return v === null || typeof v !== 'object';
  });

  return (
    <div className="table-wrapper">
      <table className="data-table">
        <thead>
          <tr>
            {keys.map(k => <th key={k}>{k}</th>)}
            <th>Soket Sayısı</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i}>
              {keys.map(k => <td key={k}>{String(row[k] ?? '')}</td>)}
              <td>{Array.isArray(row.sockets) ? row.sockets.length : '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function SearchPage({ date, onDateChange }) {
  const [filters, setFilters] = useState({
    district: '',
    city: '',
    brand: '',
    socketType: '',
    status: '',
    sort: 'id',
    order: 'asc',
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
      const data = await fetchSearch({
        date,
        district:   filters.district   || undefined,
        city:       filters.city       || undefined,
        brand:      filters.brand      || undefined,
        socketType: filters.socketType || undefined,
        status:     filters.status     || undefined,
        from:       currentPage * PAGE_SIZE,
        size:       PAGE_SIZE,
        sort:       filters.sort,
        order:      filters.order,
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

  function handleSearch() {
    setPage(0);
    doSearch(0);
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') handleSearch();
  }

  function goPage(p) {
    setPage(p);
    doSearch(p);
  }

  const hasPrev = page > 0;
  const hasNext = results && results.length === PAGE_SIZE;

  return (
    <>
      <div className="search-filters card">
        <h2>Filtreler</h2>
        <div className="filter-grid">
          <div className="filter-field">
            <label>Tarih</label>
            <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />
          </div>
          <div className="filter-field">
            <label>İlçe ID</label>
            <input
              type="text"
              placeholder="örn. 34"
              value={filters.district}
              onChange={e => handleFilterChange('district', e.target.value)}
              onKeyDown={handleKeyDown}
            />
          </div>
          <div className="filter-field">
            <label>Şehir ID</label>
            <input
              type="text"
              placeholder="örn. 06"
              value={filters.city}
              onChange={e => handleFilterChange('city', e.target.value)}
              onKeyDown={handleKeyDown}
            />
          </div>
          <div className="filter-field">
            <label>Marka</label>
            <input
              type="text"
              placeholder="örn. ZES"
              value={filters.brand}
              onChange={e => handleFilterChange('brand', e.target.value)}
              onKeyDown={handleKeyDown}
            />
          </div>
          <div className="filter-field">
            <label>Konnektör Tipi</label>
            <select value={filters.socketType} onChange={e => handleFilterChange('socketType', e.target.value)}>
              <option value="">Tümü</option>
              <option value="CCS">CCS</option>
              <option value="CHAdeMO">CHAdeMO</option>
              <option value="AC">AC</option>
              <option value="Type2">Type2</option>
            </select>
          </div>
          <div className="filter-field">
            <label>Durum</label>
            <select value={filters.status} onChange={e => handleFilterChange('status', e.target.value)}>
              <option value="">Tümü</option>
              <option value="AVAILABLE">AVAILABLE</option>
              <option value="FREE">FREE</option>
              <option value="IN_USE">IN_USE</option>
              <option value="OCCUPIED">OCCUPIED</option>
              <option value="RESERVED">RESERVED</option>
            </select>
          </div>
          <div className="filter-field">
            <label>Sıralama</label>
            <select value={filters.sort} onChange={e => handleFilterChange('sort', e.target.value)}>
              {SORT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
          <div className="filter-field">
            <label>Yön</label>
            <select value={filters.order} onChange={e => handleFilterChange('order', e.target.value)}>
              <option value="asc">Artan</option>
              <option value="desc">Azalan</option>
            </select>
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
        <div className="card" style={{ marginTop: 20 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <h2>Sonuçlar {results ? `(${results.length} kayıt, sayfa ${page + 1})` : ''}</h2>
            <div className="pagination">
              <button onClick={() => goPage(page - 1)} disabled={!hasPrev || loading}>← Önceki</button>
              <span style={{ color: '#94a3b8', padding: '0 12px' }}>Sayfa {page + 1}</span>
              <button onClick={() => goPage(page + 1)} disabled={!hasNext || loading}>Sonraki →</button>
            </div>
          </div>
          <ResultTable rows={results} />
        </div>
      )}
    </>
  );
}
