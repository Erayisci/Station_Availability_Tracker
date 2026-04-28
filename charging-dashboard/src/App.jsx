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
  { id: 'zes',   label: 'ZES',             logo: 'Z', accent: '#38bdf8' },
  { id: 'astor', label: 'Beefull / ASTOR',  logo: 'B', accent: '#f97316' },
];

const ZES_TABS   = [
  { id: 'zes-stats',       label: 'Analitikler'         },
  { id: 'zes-search',      label: 'Soket Ara'           },
  { id: 'zes-performance', label: 'İstasyon Performansı' },
];

const ASTOR_TABS = [
  { id: 'astor-stats',  label: 'Analitikler'  },
  { id: 'astor-search', label: 'İstasyon Ara' },
];

export default function App() {
  const [brand,    setBrand]    = useState('zes');
  const [zesTab,   setZesTab]   = useState('zes-stats');
  const [astorTab, setAstorTab] = useState('astor-stats');
  const [date,     setDate]     = useState(todayStr());

  const activeBrand = BRANDS.find(b => b.id === brand);
  const tabs        = brand === 'zes' ? ZES_TABS : ASTOR_TABS;
  const activeTab   = brand === 'zes' ? zesTab   : astorTab;
  const setTab      = brand === 'zes' ? setZesTab : setAstorTab;

  return (
    <div className="dashboard">
      <div className="dashboard-header">
        <div className="brand">
          <span className="brand-logo" style={{ background: activeBrand.accent + '22', color: activeBrand.accent }}>
            {activeBrand.logo}
          </span>
          <div>
            <span className="brand-name" style={{ color: activeBrand.accent }}>{activeBrand.label}</span>
            <span className="brand-sub">Şarj Ağı Analiz Paneli</span>
          </div>
        </div>

        {/* Brand switcher */}
        <div style={{ display: 'flex', gap: 8 }}>
          {BRANDS.map(b => (
            <button
              key={b.id}
              onClick={() => setBrand(b.id)}
              style={{
                padding: '6px 18px',
                borderRadius: 8,
                border: `1px solid ${brand === b.id ? b.accent : '#334155'}`,
                background: brand === b.id ? b.accent + '22' : 'transparent',
                color: brand === b.id ? b.accent : '#64748b',
                fontWeight: 700,
                fontSize: 13,
                cursor: 'pointer',
                transition: 'all 0.15s',
              }}
            >
              {b.label}
            </button>
          ))}
        </div>
      </div>

      <nav className="tab-nav">
        {tabs.map(t => (
          <button
            key={t.id}
            className={`tab-btn ${activeTab === t.id ? 'active' : ''}`}
            style={activeTab === t.id ? { borderBottomColor: activeBrand.accent, color: activeBrand.accent } : {}}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <div className="page-content">
        {brand === 'zes' && activeTab === 'zes-stats'       && <ZesStatsPage       date={date} onDateChange={setDate} />}
        {brand === 'zes' && activeTab === 'zes-search'      && <ZesSearchPage      date={date} onDateChange={setDate} />}
        {brand === 'zes' && activeTab === 'zes-performance' && <ZesPerformancePage date={date} onDateChange={setDate} />}
        {brand === 'astor' && activeTab === 'astor-stats'  && <AstorStatsPage  date={date} onDateChange={setDate} />}
        {brand === 'astor' && activeTab === 'astor-search' && <AstorSearchPage date={date} onDateChange={setDate} />}
      </div>
    </div>
  );
}
