import { useState, useEffect } from 'react';

const DAY_LABELS = ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz'];
const MONTHS = [
  'Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran',
  'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık',
];

function toYMD(y, m, d) {
  return `${y}-${String(m + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

function buildGrid(year, month) {
  const firstDow  = new Date(year, month, 1).getDay(); // 0=Sun
  const offset    = (firstDow + 6) % 7;                // Monday=0
  const daysCount = new Date(year, month + 1, 0).getDate();
  const cells     = [];
  for (let i = 0; i < offset; i++) cells.push(null);
  for (let d = 1; d <= daysCount; d++) cells.push(d);
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
}

export default function DateCalendar({ selectedDate, onSelect, fetchDates, label }) {
  const today   = new Date().toISOString().slice(0, 10);
  const initial = selectedDate || today;

  const [viewYear,  setViewYear]  = useState(parseInt(initial.slice(0, 4)));
  const [viewMonth, setViewMonth] = useState(parseInt(initial.slice(5, 7)) - 1);
  const [available, setAvailable] = useState(null); // null=loading
  const [fetchErr,  setFetchErr]  = useState(false);

  useEffect(() => {
    setAvailable(null);
    setFetchErr(false);
    fetchDates()
      .then(dates => setAvailable(new Set(dates)))
      .catch(() => { setFetchErr(true); setAvailable(new Set()); });
  }, [fetchDates]);

  /* When available dates load, jump to the most recent month that has data */
  useEffect(() => {
    if (!available || available.size === 0 || selectedDate) return;
    const sorted = [...available].sort().reverse();
    if (sorted[0]) {
      const [y, m] = sorted[0].split('-').map(Number);
      setViewYear(y);
      setViewMonth(m - 1);
    }
  }, [available]);

  function prevMonth() {
    if (viewMonth === 0) { setViewMonth(11); setViewYear(y => y - 1); }
    else setViewMonth(m => m - 1);
  }
  function nextMonth() {
    if (viewMonth === 11) { setViewMonth(0); setViewYear(y => y + 1); }
    else setViewMonth(m => m + 1);
  }

  const grid        = buildGrid(viewYear, viewMonth);
  const isLoading   = available === null;
  const totalAvail  = available ? available.size : 0;

  return (
    <div style={{
      background: 'var(--bg-surface)',
      border: '1px solid var(--bd-subtle)',
      borderRadius: 'var(--rx-lg)',
      boxShadow: 'var(--sh-sm)',
      marginBottom: 20,
      overflow: 'hidden',
    }}>
      {/* ── Header ─────────────────────────────────────────────── */}
      <div style={{
        padding: '16px 20px 12px',
        borderBottom: '1px solid var(--bd-subtle)',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        gap: 16,
        background: 'var(--sh-inner)',
      }}>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--tx-primary)', letterSpacing: '-0.01em' }}>
            {label || 'Tarih Seç'}
          </div>
          <div style={{ fontSize: 12, color: 'var(--tx-tertiary)', marginTop: 2 }}>
            {isLoading
              ? 'Mevcut tarihler yükleniyor…'
              : fetchErr
                ? 'Tarih listesi alınamadı'
                : `${totalAvail} tarihte veri mevcut`}
          </div>
        </div>

        {selectedDate && (
          <div style={{
            background: 'var(--clr-success-dim)',
            border: '1px solid var(--clr-success-ring)',
            borderRadius: 'var(--rx-md)',
            padding: '6px 12px',
            textAlign: 'right',
            flexShrink: 0,
          }}>
            <div style={{ fontSize: 10, color: 'var(--clr-success-text)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Seçili</div>
            <div style={{ fontSize: 13, color: 'var(--clr-success-text)', fontWeight: 700, fontFamily: 'var(--font-mono)', letterSpacing: '0.02em' }}>
              {selectedDate}
            </div>
          </div>
        )}
      </div>

      {/* ── Month nav + grid ───────────────────────────────────── */}
      <div style={{ padding: '16px 20px 20px' }}>
        {/* Month navigation */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
          <button onClick={prevMonth} style={navBtn}>
            &#8592;
          </button>
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--tx-primary)' }}>
            {MONTHS[viewMonth]} {viewYear}
          </span>
          <button onClick={nextMonth} style={navBtn}>
            &#8594;
          </button>
        </div>

        {/* Weekday labels */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 3, marginBottom: 6 }}>
          {DAY_LABELS.map(d => (
            <div key={d} style={{
              textAlign: 'center', fontSize: 10, fontWeight: 600,
              color: 'var(--tx-disabled)', letterSpacing: '0.04em',
              paddingBottom: 4,
            }}>
              {d}
            </div>
          ))}
        </div>

        {/* Day cells */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 3 }}>
          {grid.map((day, i) => {
            if (!day) return <div key={i} />;
            const dateStr  = toYMD(viewYear, viewMonth, day);
            const isAvail  = available ? available.has(dateStr) : false;
            const isSel    = dateStr === selectedDate;
            const isToday  = dateStr === today;
            const canClick = isAvail && !isLoading;

            return (
              <button
                key={i}
                onClick={() => canClick && onSelect(dateStr)}
                disabled={!canClick}
                title={isAvail ? `Veri mevcut — ${dateStr}` : dateStr}
                style={cellStyle(isAvail, isSel, isToday, isLoading)}
              >
                {day}
                {isAvail && !isSel && (
                  <span style={{
                    position: 'absolute', bottom: 3, left: '50%',
                    transform: 'translateX(-50%)',
                    width: 3, height: 3, borderRadius: '50%',
                    background: 'var(--clr-success)',
                    opacity: 0.7,
                  }} />
                )}
              </button>
            );
          })}
        </div>

        {/* Legend */}
        <div style={{ display: 'flex', gap: 16, marginTop: 14, flexWrap: 'wrap' }}>
          <LegendItem
            color="var(--clr-success)"
            label="Veri mevcut"
            dotFill="rgba(16,185,129,0.15)"
          />
          <LegendItem
            color="var(--accent)"
            label="Seçili"
            dotFill="var(--accent)"
          />
          {fetchErr && (
            <span style={{ fontSize: 11, color: 'var(--clr-warning-text)', marginLeft: 'auto' }}>
              ⚠ Tarih listesi alınamadı — elle tarih girilebilir
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

function LegendItem({ color, label, dotFill }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <span style={{
        width: 8, height: 8, borderRadius: '50%',
        background: dotFill, border: `1px solid ${color}60`,
        display: 'inline-block', flexShrink: 0,
      }} />
      <span style={{ fontSize: 11, color: 'var(--tx-tertiary)' }}>{label}</span>
    </div>
  );
}

const navBtn = {
  width: 28, height: 28,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  border: '1px solid var(--bd-default)',
  borderRadius: 'var(--rx-md)',
  background: 'var(--bg-elevated)',
  color: 'var(--tx-secondary)',
  fontSize: 13, cursor: 'pointer',
  transition: 'all 150ms', padding: 0,
};

function cellStyle(isAvail, isSel, isToday, isLoading) {
  const base = {
    position: 'relative',
    height: 34,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    borderRadius: 'var(--rx-sm)',
    fontSize: '12px',
    fontWeight: isSel ? 700 : 400,
    transition: 'all 120ms',
    padding: 0, outline: 'none',
  };

  if (isSel) return {
    ...base,
    background: 'var(--accent)',
    color: '#fff',
    border: '1px solid var(--accent)',
    cursor: 'pointer',
    boxShadow: '0 2px 8px rgba(99,102,241,0.4)',
  };
  if (isAvail) return {
    ...base,
    background: 'rgba(16,185,129,0.08)',
    color: 'var(--clr-success-text)',
    border: '1px solid rgba(16,185,129,0.18)',
    cursor: 'pointer',
    fontWeight: 500,
  };
  if (isToday) return {
    ...base,
    background: 'transparent',
    color: 'var(--tx-primary)',
    border: '1px solid var(--bd-default)',
    cursor: 'default',
  };
  return {
    ...base,
    background: 'transparent',
    color: isLoading ? 'var(--tx-disabled)' : 'var(--tx-disabled)',
    border: '1px solid transparent',
    cursor: 'default',
    opacity: isLoading ? 0.5 : 0.3,
  };
}
