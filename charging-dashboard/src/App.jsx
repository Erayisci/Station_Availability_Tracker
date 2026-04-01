import { useState } from 'react';
import ZesStatsPage  from './pages/ZesStatsPage';
import ZesSearchPage from './pages/ZesSearchPage';

function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

const TABS = [
  { id: 'zes-stats',  label: 'Analitikler' },
  { id: 'zes-search', label: 'Soket Ara'   },
];

export default function App() {
  const [tab,  setTab]  = useState('zes-stats');
  const [date, setDate] = useState(todayStr());

  return (
    <div className="dashboard">
      <div className="dashboard-header">
        <div className="brand">
          <span className="brand-logo">Z</span>
          <div>
            <span className="brand-name">ZES</span>
            <span className="brand-sub">Şarj Ağı Analiz Paneli</span>
          </div>
        </div>
      </div>

      <nav className="tab-nav">
        {TABS.map(t => (
          <button
            key={t.id}
            className={`tab-btn ${tab === t.id ? 'active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <div className="page-content">
        {tab === 'zes-stats'  && <ZesStatsPage  date={date} onDateChange={setDate} />}
        {tab === 'zes-search' && <ZesSearchPage date={date} onDateChange={setDate} />}
      </div>
    </div>
  );
}
