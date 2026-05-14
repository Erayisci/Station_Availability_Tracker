import { useState } from 'react';
import ZesStatsPage       from './pages/ZesStatsPage';
import ZesSearchPage      from './pages/ZesSearchPage';
import ZesPerformancePage from './pages/ZesPerformancePage';
import AstorStatsPage     from './pages/AstorStatsPage';
import AstorSearchPage    from './pages/AstorSearchPage';

function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

const BRANDS = [
  { id: 'zes',   label: 'ZES' },
  { id: 'astor', label: 'Beefull / ASTOR' },
];

const ZES_TABS = [
  { id: 'zes-stats',       label: 'Analitikler',          icon: '📈' },
  { id: 'zes-search',      label: 'Soket Ara',            icon: '🔍' },
  { id: 'zes-performance', label: 'İstasyon Performansı', icon: '⚡' },
];

const ASTOR_TABS = [
  { id: 'astor-stats',  label: 'Analitikler',  icon: '📈' },
  { id: 'astor-search', label: 'İstasyon Ara', icon: '🔍' },
];

export default function App() {
  const [brand,    setBrand]    = useState('zes');
  const [zesTab,   setZesTab]   = useState('zes-stats');
  const [astorTab, setAstorTab] = useState('astor-stats');
  const [date,     setDate]     = useState(todayStr());
  const [theme,    setTheme]    = useState('dark');

  const tabs      = brand === 'zes' ? ZES_TABS   : ASTOR_TABS;
  const activeTab = brand === 'zes' ? zesTab      : astorTab;
  const setTab    = brand === 'zes' ? setZesTab   : setAstorTab;

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
          {/* Brand switcher */}
          <div className="brand-switcher">
            {BRANDS.map(b => (
              <button
                key={b.id}
                onClick={() => setBrand(b.id)}
                className={`brand-btn${brand === b.id ? ' active' : ''}`}
              >
                {b.label}
              </button>
            ))}
          </div>

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
        {tabs.map(t => (
          <button
            key={t.id}
            className={`tab-btn${activeTab === t.id ? ' active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {/* ── Page ────────────────────────────────────────────────── */}
      <div className="page-content">
        {brand === 'zes'   && activeTab === 'zes-stats'       && <ZesStatsPage       date={date} onDateChange={setDate} />}
        {brand === 'zes'   && activeTab === 'zes-search'      && <ZesSearchPage      date={date} onDateChange={setDate} />}
        {brand === 'zes'   && activeTab === 'zes-performance' && <ZesPerformancePage date={date} onDateChange={setDate} />}
        {brand === 'astor' && activeTab === 'astor-stats'     && <AstorStatsPage     date={date} onDateChange={setDate} />}
        {brand === 'astor' && activeTab === 'astor-search'    && <AstorSearchPage    date={date} onDateChange={setDate} />}
      </div>
    </div>
  );
}
