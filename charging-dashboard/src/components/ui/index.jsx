/* ─────────────────────────────────────────────────────────────────
   ZES UI Primitives — Card, Badge, Button, SegmentedControl,
   FilterChip, ChartCard, EmptyState, MetricCard, Tooltip styles
   ───────────────────────────────────────────────────────────────── */
import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';

/* ══ Card ══════════════════════════════════════════════════════════ */
export function Card({ children, className = '', style }) {
  return (
    <div className={`card ${className}`} style={style}>
      {children}
    </div>
  );
}

export function CardHeader({ title, subtitle, actions, className = '' }) {
  return (
    <div className={`card-header ${className}`}>
      <div>
        <div className="card-title">{title}</div>
        {subtitle && <div className="card-subtitle">{subtitle}</div>}
      </div>
      {actions && <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>{actions}</div>}
    </div>
  );
}

export function CardBody({ children, style }) {
  return <div className="card-body" style={style}>{children}</div>;
}

/* ══ Badge ═════════════════════════════════════════════════════════ */
const BADGE_PRESETS = {
  /* Status */
  Available:    { bg: 'var(--clr-success-dim)',  color: 'var(--clr-success-text)',  border: 'rgba(16,185,129,0.25)' },
  Charging:     { bg: 'var(--clr-info-dim)',     color: 'var(--clr-info-text)',     border: 'rgba(59,130,246,0.25)' },
  Unavailable:  { bg: 'rgba(100,116,139,0.12)',  color: '#94a3b8',                  border: 'rgba(100,116,139,0.25)' },
  Faulted:      { bg: 'var(--clr-danger-dim)',   color: 'var(--clr-danger-text)',   border: 'rgba(239,68,68,0.25)' },
  Preparing:    { bg: 'var(--clr-info-dim)',     color: 'var(--clr-info-text)',     border: 'rgba(59,130,246,0.25)' },
  Finishing:    { bg: 'var(--clr-warning-dim)',  color: 'var(--clr-warning-text)', border: 'rgba(245,158,11,0.25)' },
  Reserved:     { bg: 'var(--clr-purple-dim)',   color: 'var(--clr-purple-text)',   border: 'rgba(139,92,246,0.25)' },
  SuspendedEV:  { bg: 'var(--clr-orange-dim)',   color: 'var(--clr-orange-text)',   border: 'rgba(249,115,22,0.25)' },
  SuspendedEVSE:{ bg: 'var(--clr-purple-dim)',   color: 'var(--clr-purple-text)',   border: 'rgba(139,92,246,0.25)' },
  /* Demand tier */
  HIGH:         { bg: 'var(--clr-danger-dim)',   color: 'var(--clr-danger-text)',   border: 'rgba(239,68,68,0.25)' },
  MEDIUM:       { bg: 'var(--clr-warning-dim)',  color: 'var(--clr-warning-text)', border: 'rgba(245,158,11,0.25)' },
  LOW:          { bg: 'var(--clr-success-dim)',  color: 'var(--clr-success-text)',  border: 'rgba(16,185,129,0.25)' },
  /* Supply tier */
  FEW:          { bg: 'var(--clr-danger-dim)',   color: 'var(--clr-danger-text)',   border: 'rgba(239,68,68,0.25)' },
  MODERATE:     { bg: 'var(--clr-warning-dim)',  color: 'var(--clr-warning-text)', border: 'rgba(245,158,11,0.25)' },
  MANY:         { bg: 'var(--clr-success-dim)',  color: 'var(--clr-success-text)',  border: 'rgba(16,185,129,0.25)' },
  /* Recommendation codes */
  EXPAND:           { bg: 'var(--clr-danger-dim)',   color: 'var(--clr-danger-text)',   border: 'rgba(239,68,68,0.25)' },
  INCREASE_CAPACITY:{ bg: 'var(--clr-orange-dim)',   color: 'var(--clr-orange-text)',   border: 'rgba(249,115,22,0.25)' },
  OPTIMIZE:         { bg: 'var(--clr-warning-dim)',  color: 'var(--clr-warning-text)', border: 'rgba(245,158,11,0.25)' },
  MONITOR_GROWTH:   { bg: 'rgba(100,116,139,0.12)', color: '#94a3b8',                  border: 'rgba(100,116,139,0.25)' },
  MAINTAIN:         { bg: 'var(--clr-success-dim)',  color: 'var(--clr-success-text)',  border: 'rgba(16,185,129,0.25)' },
  IMPROVE_EFFICIENCY:{ bg: 'var(--clr-info-dim)',    color: 'var(--clr-info-text)',     border: 'rgba(59,130,246,0.25)' },
  MONITOR:          { bg: 'rgba(100,116,139,0.12)', color: '#64748b',                  border: 'rgba(100,116,139,0.2)' },
  REVIEW:           { bg: 'var(--clr-purple-dim)',   color: 'var(--clr-purple-text)',   border: 'rgba(139,92,246,0.25)' },
  RELOCATE:         { bg: 'var(--clr-danger-dim)',   color: 'var(--clr-danger-text)',   border: 'rgba(239,68,68,0.25)' },
  INSUFFICIENT_DATA:{ bg: 'rgba(30,45,64,0.6)',      color: '#475569',                  border: 'rgba(71,85,105,0.2)' },
  /* Env type */
  RETAIL:       { bg: 'var(--clr-danger-dim)',   color: 'var(--clr-danger-text)',   border: 'rgba(239,68,68,0.25)' },
  COMMERCIAL:   { bg: 'var(--clr-info-dim)',     color: 'var(--clr-info-text)',     border: 'rgba(59,130,246,0.25)' },
  OFFICE:       { bg: 'var(--clr-warning-dim)',  color: 'var(--clr-warning-text)', border: 'rgba(245,158,11,0.25)' },
  RESIDENTIAL:  { bg: 'var(--clr-success-dim)',  color: 'var(--clr-success-text)',  border: 'rgba(16,185,129,0.25)' },
  UNKNOWN:      { bg: 'rgba(100,116,139,0.12)', color: '#64748b',                  border: 'rgba(100,116,139,0.2)' },
  /* Connector */
  DC_CCS:         { bg: 'var(--clr-info-dim)',    color: 'var(--clr-info-text)',    border: 'rgba(59,130,246,0.25)' },
  DC_CHAdeMO:     { bg: 'var(--clr-purple-dim)',  color: 'var(--clr-purple-text)',  border: 'rgba(139,92,246,0.25)' },
  AC_Type_2:      { bg: 'var(--clr-success-dim)', color: 'var(--clr-success-text)', border: 'rgba(16,185,129,0.25)' },
  AC_Type_2_Plug: { bg: 'var(--clr-orange-dim)',  color: 'var(--clr-orange-text)',  border: 'rgba(249,115,22,0.25)' },
  /* Astor */
  available: { bg: 'var(--clr-success-dim)',  color: 'var(--clr-success-text)',  border: 'rgba(16,185,129,0.25)' },
  inUse:     { bg: 'var(--clr-info-dim)',     color: 'var(--clr-info-text)',     border: 'rgba(59,130,246,0.25)' },
  faulted:   { bg: 'var(--clr-danger-dim)',   color: 'var(--clr-danger-text)',   border: 'rgba(239,68,68,0.25)' },
  offline:   { bg: 'rgba(100,116,139,0.12)', color: '#64748b',                  border: 'rgba(100,116,139,0.2)' },
  astor:     { bg: 'var(--clr-orange-dim)',   color: 'var(--clr-orange-text)',   border: 'rgba(249,115,22,0.25)' },
  beefull:   { bg: 'var(--clr-purple-dim)',   color: 'var(--clr-purple-text)',   border: 'rgba(139,92,246,0.25)' },
  ac:        { bg: 'var(--clr-success-dim)',  color: 'var(--clr-success-text)',  border: 'rgba(16,185,129,0.25)' },
  dc:        { bg: 'var(--clr-info-dim)',     color: 'var(--clr-info-text)',     border: 'rgba(59,130,246,0.25)' },
};

const FALLBACK = { bg: 'rgba(100,116,139,0.12)', color: '#94a3b8', border: 'rgba(100,116,139,0.2)' };

export function Badge({ value, label, size = 'sm' }) {
  const preset = BADGE_PRESETS[value] ?? FALLBACK;
  const display = label ?? value ?? '—';
  const pad = size === 'lg' ? '4px 12px' : '2px 9px';
  const fs  = size === 'lg' ? '12px' : '11px';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: pad, borderRadius: 'var(--rx-full)',
      background: preset.bg, color: preset.color,
      border: `1px solid ${preset.border}`,
      fontSize: fs, fontWeight: 600, letterSpacing: '0.01em',
      whiteSpace: 'nowrap', lineHeight: 1.4,
    }}>
      {display}
    </span>
  );
}

/* Convenience aliases kept for old code */
export function TierBadge({ value }) { return <Badge value={value} />; }
export function RecBadge({ value })  { return <Badge value={value} />; }

/* ══ Button ════════════════════════════════════════════════════════ */
export function Button({
  children, onClick, disabled, variant = 'secondary', size = 'md',
  icon, className = '', style, title,
}) {
  const sizes = {
    sm: { height: 28, padding: '0 10px', fontSize: '12px' },
    md: { height: 34, padding: '0 14px', fontSize: '13px' },
    lg: { height: 40, padding: '0 20px', fontSize: '14px' },
  };
  const variants = {
    primary: {
      background: 'var(--accent)',
      color: '#fff',
      border: '1px solid transparent',
      boxShadow: '0 1px 3px rgba(99,102,241,0.4)',
    },
    secondary: {
      background: 'var(--bg-elevated)',
      color: 'var(--tx-secondary)',
      border: '1px solid var(--bd-default)',
    },
    ghost: {
      background: 'transparent',
      color: 'var(--tx-secondary)',
      border: '1px solid transparent',
    },
    danger: {
      background: 'var(--clr-danger-dim)',
      color: 'var(--clr-danger-text)',
      border: '1px solid rgba(239,68,68,0.25)',
    },
    success: {
      background: 'var(--clr-success-dim)',
      color: 'var(--clr-success-text)',
      border: '1px solid rgba(16,185,129,0.25)',
    },
  };
  const { height, padding, fontSize } = sizes[size] ?? sizes.md;
  const v = variants[variant] ?? variants.secondary;

  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={className}
      style={{
        height, padding, fontSize,
        display: 'inline-flex', alignItems: 'center', gap: 6,
        ...v,
        borderRadius: 'var(--rx-md)',
        fontFamily: 'var(--font-ui)',
        fontWeight: 500,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.45 : 1,
        transition: 'all var(--tr)',
        whiteSpace: 'nowrap',
        outline: 'none',
        ...style,
      }}
    >
      {icon && <span style={{ fontSize: 13 }}>{icon}</span>}
      {children}
    </button>
  );
}

/* ══ SegmentedControl ══════════════════════════════════════════════ */
export function SegmentedControl({ options, value, onChange }) {
  return (
    <div style={{
      display: 'flex',
      background: 'var(--bg-elevated)',
      border: '1px solid var(--bd-default)',
      borderRadius: 'var(--rx-md)',
      padding: 3,
      gap: 2,
    }}>
      {options.map(opt => {
        const active = value === opt.value;
        return (
          <button
            key={opt.value}
            onClick={() => onChange(opt.value)}
            style={{
              flex: 1,
              padding: '5px 12px',
              borderRadius: 'var(--rx-sm)',
              border: 'none',
              background: active ? 'var(--bg-surface)' : 'transparent',
              color: active ? 'var(--tx-primary)' : 'var(--tx-tertiary)',
              fontSize: '12px',
              fontWeight: active ? 600 : 400,
              fontFamily: 'var(--font-ui)',
              cursor: 'pointer',
              transition: 'all var(--tr)',
              whiteSpace: 'nowrap',
              boxShadow: active ? 'var(--sh-sm)' : 'none',
            }}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

/* ══ FilterChip (colored tile button) ══════════════════════════════ */
export function FilterChip({ label, color, active, onClick }) {
  return (
    <button
      onClick={onClick}
      style={{
        display: 'inline-flex', alignItems: 'center',
        padding: '4px 12px',
        borderRadius: 'var(--rx-full)',
        border: active ? `1px solid ${color}50` : '1px solid var(--bd-default)',
        background: active ? `${color}18` : 'var(--bg-elevated)',
        color: active ? color : 'var(--tx-tertiary)',
        fontSize: '12px',
        fontWeight: active ? 600 : 400,
        fontFamily: 'var(--font-ui)',
        cursor: 'pointer',
        transition: 'all var(--tr)',
        whiteSpace: 'nowrap',
      }}
    >
      {label}
    </button>
  );
}

/* ══ ChartCard ═════════════════════════════════════════════════════ */
export function ChartCard({
  title, subtitle, counter, total, onPrev, onNext,
  controls, children, dots, activeIndex, onDotClick, minHeight = 360,
}) {
  return (
    <div className="chart-panel">
      <div className="chart-panel-header">
        {/* Nav row */}
        <div className="chart-nav-row">
          {onPrev && (
            <button onClick={onPrev} className="panel-arrow">&#8592;</button>
          )}
          <div className="panel-title-area">
            {counter != null && (
              <span className="panel-counter">
                {String(counter + 1).padStart(2, '0')} / {String(total).padStart(2, '0')}
              </span>
            )}
            <h2 className="panel-title">{title}</h2>
            {subtitle && (
              <span style={{ fontSize: 11, color: 'var(--tx-tertiary)', marginTop: 2 }}>
                {subtitle}
              </span>
            )}
          </div>
          {onNext && (
            <button onClick={onNext} className="panel-arrow">&#8594;</button>
          )}
        </div>
        {/* Controls strip */}
        {controls && (
          <div className="panel-controls">{controls}</div>
        )}
      </div>

      <div className="chart-body" style={{ minHeight }}>
        {children}
      </div>

      {dots != null && (
        <div className="chart-dots">
          {Array.from({ length: dots }, (_, i) => (
            <button
              key={i}
              onClick={() => onDotClick?.(i)}
              className={`dot${i === activeIndex ? ' active' : ''}`}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/* ══ EmptyState ════════════════════════════════════════════════════ */
export function EmptyState({ icon = '📊', title = 'Veri yok', description, action }) {
  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', gap: 12,
      minHeight: 240, textAlign: 'center', padding: '32px 24px',
    }}>
      <span style={{ fontSize: 36, opacity: 0.5 }}>{icon}</span>
      <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--tx-secondary)' }}>{title}</div>
      {description && (
        <div style={{ fontSize: '13px', color: 'var(--tx-tertiary)', maxWidth: 320 }}>{description}</div>
      )}
      {action}
    </div>
  );
}

/* ══ MetricCard (inline stat) ══════════════════════════════════════ */
export function MetricCard({ label, value, sub, color = 'var(--tx-primary)' }) {
  return (
    <div style={{
      background: 'var(--bg-elevated)', border: '1px solid var(--bd-subtle)',
      borderRadius: 'var(--rx-md)', padding: '12px 16px',
      display: 'flex', flexDirection: 'column', gap: 4,
    }}>
      <div style={{ fontSize: 10, color: 'var(--tx-tertiary)', textTransform: 'uppercase', letterSpacing: '0.06em', fontWeight: 600 }}>
        {label}
      </div>
      <div style={{ fontSize: '1.4rem', fontFamily: 'var(--font-mono)', fontWeight: 600, color, letterSpacing: '-0.02em', lineHeight: 1 }}>
        {value}
      </div>
      {sub && <div style={{ fontSize: 11, color: 'var(--tx-tertiary)' }}>{sub}</div>}
    </div>
  );
}

/* ══ UtilBar (percentage bar) ══════════════════════════════════════ */
export function UtilBar({ pct }) {
  const val  = pct != null ? Number(pct).toFixed(1) : null;
  const fill = pct >= 40 ? 'var(--clr-danger-text)' : pct >= 15 ? 'var(--clr-warning-text)' : 'var(--clr-success-text)';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 130 }}>
      <div style={{ flex: 1, height: 4, background: 'var(--bg-elevated)', borderRadius: 2, overflow: 'hidden' }}>
        <div style={{ width: `${Math.min(pct ?? 0, 100)}%`, height: '100%', background: fill, borderRadius: 2, transition: 'width 0.3s' }} />
      </div>
      <span style={{ color: fill, fontSize: 11, fontFamily: 'var(--font-mono)', fontWeight: 600, minWidth: 40, textAlign: 'right' }}>
        {val != null ? `${val}%` : '—'}
      </span>
    </div>
  );
}

/* ══ InfoTooltip — rich formula popup on hover ═════════════════════ */
export function InfoTooltip({ children, width = 360 }) {
  const [visible, setVisible] = useState(false);
  const [pos, setPos]         = useState({ x: 0, y: 0 });
  const triggerRef            = useRef(null);
  const timer                 = useRef(null);

  const show = () => {
    clearTimeout(timer.current);
    if (triggerRef.current) {
      const r = triggerRef.current.getBoundingClientRect();
      setPos({ x: r.left + r.width / 2, y: r.bottom + 8 });
    }
    setVisible(true);
  };
  const hide = () => { timer.current = setTimeout(() => setVisible(false), 200); };

  useEffect(() => {
    if (!visible) return;
    const close = () => setVisible(false);
    window.addEventListener('scroll', close, true);
    return () => window.removeEventListener('scroll', close, true);
  }, [visible]);

  const xClamped = pos.x
    ? Math.max(width / 2 + 8, Math.min(pos.x, window.innerWidth - width / 2 - 8))
    : pos.x;

  return (
    <>
      <span
        ref={triggerRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        style={{
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          width: 14, height: 14, borderRadius: '50%',
          background: 'rgba(99,102,241,0.10)', border: '1px solid rgba(99,102,241,0.28)',
          color: '#818cf8', fontSize: 9, fontWeight: 700,
          cursor: 'help', marginLeft: 4, flexShrink: 0,
          userSelect: 'none', verticalAlign: 'middle',
        }}
      >?</span>

      {visible && createPortal(
        <div
          onMouseEnter={() => clearTimeout(timer.current)}
          onMouseLeave={hide}
          style={{
            position: 'fixed', left: xClamped, top: pos.y,
            transform: 'translateX(-50%)',
            width, zIndex: 9999,
            background: '#0d1b2a',
            border: '1px solid rgba(99,102,241,0.3)',
            borderRadius: 10,
            boxShadow: '0 16px 48px rgba(0,0,0,0.7), 0 0 0 1px rgba(99,102,241,0.08)',
            padding: '14px 16px',
          }}
        >
          {children}
        </div>,
        document.body,
      )}
    </>
  );
}

/* ══ Recharts tooltip style object (shared) ════════════════════════ */
export const TOOLTIP_STYLE = {
  contentStyle: {
    background: 'var(--bg-elevated)',
    border: '1px solid var(--bd-default)',
    borderRadius: 8,
    boxShadow: '0 8px 24px rgba(0,0,0,0.4)',
    fontSize: 12,
    fontFamily: 'var(--font-ui)',
    padding: '8px 12px',
  },
  labelStyle:  { color: 'var(--tx-primary)',   fontWeight: 600, marginBottom: 4 },
  itemStyle:   { color: 'var(--tx-secondary)', padding: '1px 0' },
  cursor:      { fill: 'rgba(99,102,241,0.06)' },
};

/* ══ Section divider with label ════════════════════════════════════ */
export function SectionLabel({ children }) {
  return (
    <div style={{
      fontSize: 11, fontWeight: 600, color: 'var(--tx-tertiary)',
      textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 12,
    }}>
      {children}
    </div>
  );
}
