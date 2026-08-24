import { useState } from 'react';
import ZesStatsPage       from './pages/analytics/ZesStatsPage';
import ZesSearchPage      from './pages/analytics/ZesSearchPage';
import ZesPerformancePage from './pages/analytics/ZesPerformancePage';
import ZesCityPage        from './pages/analytics/ZesCityPage';
import ZesOpportunityPage from './pages/intelligence/ZesOpportunityPage';
import ZesPredictionPage  from './pages/intelligence/ZesPredictionPage';
import ZesBacktestPage    from './pages/intelligence/ZesBacktestPage';
import ZesPricingPage     from './pages/intelligence/ZesPricingPage';

function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

/* Tabs are ordered by pipeline layer: analytics (L3) first, then intelligence (L4). */
const TABS = [
  { id: 'stats',       label: 'Analitikler',          layer: 'analytics'    },
  { id: 'search',      label: 'Soket Ara',            layer: 'analytics'    },
  { id: 'performance', label: 'İstasyon Performansı', layer: 'analytics'    },
  { id: 'city',        label: 'Şehir Analizi',        layer: 'analytics'    },
  { id: 'opportunity', label: 'İstasyon Profili',     layer: 'intelligence' },
  { id: 'prediction',  label: 'Tahmin',               layer: 'intelligence' },
  { id: 'pricing',     label: 'Dinamik Fiyat',        layer: 'intelligence' },
  { id: 'backtest',    label: 'Doğrulama',            layer: 'intelligence' },
];

const PAGES = {
  stats:       ZesStatsPage,
  search:      ZesSearchPage,
  performance: ZesPerformancePage,
  city:        ZesCityPage,
  opportunity: ZesOpportunityPage,
  prediction:  ZesPredictionPage,
  pricing:     ZesPricingPage,
  backtest:    ZesBacktestPage,
};

export default function App() {
  const [tab,   setTab]   = useState('stats');
  const [date,  setDate]  = useState(todayStr());
  const [theme, setTheme] = useState('dark');

  const Page = PAGES[tab];

  function toggleTheme() {
    const next = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    document.documentElement.setAttribute('data-theme', next === 'light' ? 'light' : '');
  }

  return (
    <div className="app-shell">
      {/* ── Header ──────────────────────────────────────────────── */}
      <header className="app-header">
        <div className="brand">
          <div className="brand-mark">Z</div>
          <div>
            <div className="brand-label">ZES</div>
            <div className="brand-sub">Şarj Ağı Analiz Paneli</div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {/* Theme toggle */}
          <button
            onClick={toggleTheme}
            className="theme-toggle"
            title={theme === 'dark' ? 'Açık tema' : 'Koyu tema'}
          >
            {theme === 'dark' ? '☀️' : '🌙'}
          </button>
        </div>
      </header>

      {/* ── Tab Nav ─────────────────────────────────────────────── */}
      <nav className="tab-nav">
        {TABS.map(t => (
          <button
            key={t.id}
            className={`tab-btn${tab === t.id ? ' active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {/* ── Page ────────────────────────────────────────────────── */}
      <div className="page-content">
        <Page date={date} onDateChange={setDate} />
      </div>
    </div>
  );
}
