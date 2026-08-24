/**
 * Recommendation vocabulary — the presentation-side mirror of the backend's
 * `recommendation_code` field.
 *
 * Codes are assigned in
 * `materialization/performance/StationPerformanceIndexService` from the
 * (demand_tier × supply_tier) matrix and stored in
 * `zes_station_performance-{date}`. This module holds only the display mapping:
 * Turkish label, the tier pair the code stands for, and the colour used
 * consistently across every page that renders a code.
 *
 * It lives in `lib/` rather than inside a page because two layers consume it —
 * the analytics performance table and the intelligence opportunity page. A page
 * importing another page's constant coupled those two layers together for no
 * reason; this is that shared vocabulary given its own home.
 *
 * Keep in sync with the backend: adding a code there without adding it here
 * degrades gracefully (`recLabel` falls back to the raw code) but loses the
 * label and colour.
 */
export const REC_META = {
  EXPAND:             { label: 'Yeni İstasyon Kur',      demand: 'Yüksek', supply: 'Az',   color: '#ef4444' },
  INCREASE_CAPACITY:  { label: 'Soket Ekle',             demand: 'Yüksek', supply: 'Orta', color: '#f97316' },
  OPTIMIZE:           { label: 'Hizmet Kalitesini Artır',demand: 'Yüksek', supply: 'Çok',  color: '#f59e0b' },
  MONITOR_GROWTH:     { label: 'Büyüme Fırsatı',         demand: 'Orta',   supply: 'Az',   color: '#94a3b8' },
  MAINTAIN:           { label: 'Dengeyi Koru',           demand: 'Orta',   supply: 'Orta', color: '#10b981' },
  IMPROVE_EFFICIENCY: { label: 'Kullanımı Artır',        demand: 'Orta',   supply: 'Çok',  color: '#3b82f6' },
  MONITOR:            { label: 'Gelişimi İzle',          demand: 'Düşük',  supply: 'Az',   color: '#64748b' },
  REVIEW:             { label: 'Yatırımı Gözden Geçir',  demand: 'Düşük',  supply: 'Orta', color: '#8b5cf6' },
  RELOCATE:           { label: 'İstasyonu Taşı',         demand: 'Düşük',  supply: 'Çok',  color: '#ef4444' },
};

export const recLabel = code => REC_META[code]?.label ?? code;
