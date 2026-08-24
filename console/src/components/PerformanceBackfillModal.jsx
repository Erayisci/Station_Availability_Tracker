import { useState } from 'react';

export default function PerformanceBackfillModal({ onClose }) {
  const today = new Date().toISOString().split('T')[0];
  const monthAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

  const [startDate, setStartDate] = useState(monthAgo);
  const [endDate, setEndDate] = useState(today);
  const [radius, setRadius] = useState(1.0);
  const [force, setForce] = useState(false);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);

  const handleBackfill = async () => {
    setLoading(true);
    setResult(null);

    try {
      const response = await fetch(
        `/zes/availability/station-performance/backfill?` +
        `startDate=${startDate}&endDate=${endDate}&radius=${radius}&force=${force}`,
        { method: 'POST' }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json();
      setResult(data);
    } catch (error) {
      alert('Backfill failed: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-content"
        onClick={(e) => e.stopPropagation()}
        style={{
          background: '#1a202c',
          border: '1px solid #2d3748',
          borderRadius: 12,
          padding: 32,
          maxWidth: 720,
          maxHeight: '80vh',
          overflowY: 'auto',
        }}
      >
        <h2 style={{ color: '#e2e8f0', marginBottom: 8, fontSize: 18 }}>
          Performance Index Senkronizasyonu
        </h2>
        <p style={{ color: '#94a3b8', fontSize: 14, marginBottom: 16, lineHeight: 1.5 }}>
          Belirtilen tarih aralığında connector verisi olan ancak performance
          index'i eksik olan günler için otomatik index oluşturulacak.
        </p>

        <label style={{
          display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20,
          padding: '10px 14px', borderRadius: 8, cursor: 'pointer',
          background: force ? 'rgba(239,68,68,0.08)' : 'rgba(255,255,255,0.03)',
          border: `1px solid ${force ? 'rgba(239,68,68,0.35)' : '#2d3748'}`,
          transition: 'all 0.15s',
        }}>
          <input
            type="checkbox"
            checked={force}
            onChange={e => setForce(e.target.checked)}
            style={{ width: 15, height: 15, accentColor: '#ef4444', cursor: 'pointer' }}
          />
          <div>
            <div style={{ color: force ? '#ef4444' : '#94a3b8', fontSize: 13, fontWeight: 600 }}>
              Mevcut indexleri yeniden build et
            </div>
            <div style={{ color: '#475569', fontSize: 11, marginTop: 2 }}>
              İşaretlenirse var olan indexler silinip yeni mantıkla yeniden oluşturulur
            </div>
          </div>
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 24 }}>
          <div>
            <label style={{ display: 'block', color: '#94a3b8', fontSize: 12, marginBottom: 6 }}>
              Başlangıç Tarihi
            </label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              style={{
                width: '100%',
                padding: '8px 12px',
                background: '#0f172a',
                border: '1px solid #2d3748',
                borderRadius: 6,
                color: '#e2e8f0',
                fontSize: 14,
              }}
            />
          </div>

          <div>
            <label style={{ display: 'block', color: '#94a3b8', fontSize: 12, marginBottom: 6 }}>
              Bitiş Tarihi
            </label>
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              style={{
                width: '100%',
                padding: '8px 12px',
                background: '#0f172a',
                border: '1px solid #2d3748',
                borderRadius: 6,
                color: '#e2e8f0',
                fontSize: 14,
              }}
            />
          </div>

          <div>
            <label style={{ display: 'block', color: '#94a3b8', fontSize: 12, marginBottom: 6 }}>
              Radius (km)
            </label>
            <input
              type="number"
              step="0.1"
              min="0.1"
              max="50"
              value={radius}
              onChange={(e) => setRadius(parseFloat(e.target.value))}
              style={{
                width: '100%',
                padding: '8px 12px',
                background: '#0f172a',
                border: '1px solid #2d3748',
                borderRadius: 6,
                color: '#e2e8f0',
                fontSize: 14,
              }}
            />
          </div>
        </div>

        <div style={{ display: 'flex', gap: 12, marginBottom: 24 }}>
          <button
            onClick={handleBackfill}
            disabled={loading}
            style={{
              flex: 1,
              padding: '10px 20px',
              background: loading ? '#334155' : '#10b981',
              color: '#fff',
              border: 'none',
              borderRadius: 8,
              fontSize: 14,
              fontWeight: 600,
              cursor: loading ? 'not-allowed' : 'pointer',
              transition: 'all 0.2s',
            }}
          >
            {loading ? 'İşlem devam ediyor...' : 'Senkronize Et'}
          </button>

          <button
            onClick={onClose}
            style={{
              padding: '10px 20px',
              background: 'transparent',
              color: '#94a3b8',
              border: '1px solid #2d3748',
              borderRadius: 8,
              fontSize: 14,
              fontWeight: 600,
              cursor: 'pointer',
              transition: 'all 0.2s',
            }}
          >
            Kapat
          </button>
        </div>

        {result && (
          <div style={{
            background: '#0f172a',
            borderRadius: 8,
            padding: 20,
            border: '1px solid #1e293b',
          }}>
            <h3 style={{ color: '#e2e8f0', fontSize: 15, marginBottom: 12 }}>
              Sonuç
            </h3>

            <div style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(4, 1fr)',
              gap: 12,
              marginBottom: 16,
            }}>
              <StatBox label="Toplam Gün" value={result.total_dates} color="#64748b" />
              <StatBox label="Atlandı" value={result.skipped} color="#f59e0b" />
              <StatBox label="Zaten Var" value={result.existing} color="#3b82f6" />
              <StatBox label="Oluşturuldu" value={result.built} color="#10b981" />
            </div>

            <div style={{ maxHeight: 240, overflowY: 'auto' }}>
              <table style={{ width: '100%', fontSize: 12 }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #1e293b' }}>
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'left', fontSize: 11 }}>Tarih</th>
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'left', fontSize: 11 }}>Durum</th>
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'right', fontSize: 11 }}>İstasyon</th>
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'left', fontSize: 11 }}>Not</th>
                  </tr>
                </thead>
                <tbody>
                  {result.details.map((detail, idx) => (
                    <tr key={idx} style={{ borderBottom: '1px solid #1e293b50' }}>
                      <td style={{ padding: '6px 4px', color: '#94a3b8' }}>{detail.date}</td>
                      <td style={{ padding: '6px 4px' }}>
                        <StatusBadge status={detail.status} />
                      </td>
                      <td style={{ padding: '6px 4px', color: '#e2e8f0', textAlign: 'right' }}>
                        {detail.stations || '—'}
                      </td>
                      <td style={{ padding: '6px 4px', color: '#64748b', fontSize: 11 }}>
                        {detail.reason || detail.error || '✓'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function StatBox({ label, value, color }) {
  return (
    <div style={{
      background: '#1a202c',
      borderRadius: 6,
      padding: 12,
      textAlign: 'center',
    }}>
      <div style={{ color, fontSize: 24, fontWeight: 700, marginBottom: 4 }}>
        {value}
      </div>
      <div style={{ color: '#64748b', fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
        {label}
      </div>
    </div>
  );
}

function StatusBadge({ status }) {
  const config = {
    BUILT:    { color: '#10b981', label: 'BUILT' },
    REBUILT:  { color: '#ef4444', label: 'REBUILT' },
    EXISTING: { color: '#3b82f6', label: 'EXISTS' },
    SKIPPED:  { color: '#f59e0b', label: 'SKIP' },
    FAILED:   { color: '#ef4444', label: 'FAIL' },
  };

  const { color, label } = config[status] || { color: '#64748b', label: status };

  return (
    <span style={{
      display: 'inline-block',
      padding: '2px 8px',
      borderRadius: 4,
      background: color + '22',
      color,
      fontSize: 10,
      fontWeight: 700,
      letterSpacing: '0.05em',
    }}>
      {label}
    </span>
  );
}
