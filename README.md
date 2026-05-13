# EV Charging Station Tracker & Analytics Platform

A comprehensive real-time electric vehicle charging station monitoring and analytics system for ZES and ASTOR networks. Provides performance insights, utilization tracking, and strategic recommendations for infrastructure optimization.

---

## 📊 System Overview

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                   Polling & Status Tracking                      │
└─────────────────────────────────────────────────────────────────┘
         │
         ├─> ZES/ASTOR API (every 5 minutes)
         │   └─> Status changes tracked in H2 (7-day retention)
         │
         ├─> H2 Database (Authoritative Source)
         │   └─> ConnectorStatusHistory / AstorStationStatusHistory
         │
         ├─> Elasticsearch Index Chain (Daily Finalization)
         │   ├─> {provider}_stations-{date}           (Station snapshots)
         │   ├─> zes_connector_transform-{date}       (Connector-level view)
         │   └─> zes_station_performance-{date}       (Performance metrics)
         │
         └─> Scheduler Jobs
             ├─> 23:59 → Daily finalization (close open slots, rebuild indexes)
             └─> 00:15 → Auto-build performance index (previous day)
```

### Tech Stack

**Backend**:
- Java 17 + Spring Boot 3.x
- Elasticsearch 8.x (analytics storage)
- H2 Database (real-time tracking, 7-day retention)
- RESTful API architecture

**Frontend**:
- React 18 + Vite
- Recharts (data visualization)
- Monochrome professional design system

---

## 🎯 Refactoring Goals & Implementation Plan

### Phase 1: Backend Cleanup & Optimization

#### 1.1 Remove Unused Analysis Methods

**Identified Unused Endpoints** (UI does NOT call these):

```java
// ❌ REMOVE: ZesAnalysisService.java
- statusByConnectorType(String date)    → Line 110-147
- statusByProvince(String date)         → Line 156-193
- statusByTimeSlot(...)                 → Line 207-286  (duplicate, use connector-transform version)

// ❌ REMOVE: ZesAnalysisController.java
- /zes/availability/stats/by-connector-type  → Line 34-38
- /zes/availability/stats/by-province        → Line 40-44
- /zes/availability/stats/by-time-slot       → Line 46-53
- /zes/availability/search                   → Line 197-208
```

**Identified ACTIVE Endpoints** (UI actively uses):

```java
// ✅ KEEP: ZesConnectorTransformAnalysisService.java
- statusByTimeSlot()       → Used by ZesStatsPage.jsx
- statusTimeline()         → Used by ZesStatsPage.jsx
- durationByCity()         → Used by ZesStatsPage.jsx
- searchConnectors()       → Used by ZesSearchPage.jsx

// ✅ KEEP: ZesEnvironmentAnalysisService.java
- envTypeDistribution()    → Used by ZesStatsPage.jsx
- statusByEnvAndTimeSlot() → Used by ZesStatsPage.jsx

// ✅ KEEP: ZesStationPerformanceIndexService.java + ZesStationPerformanceQueryService.java
- All methods              → Used by ZesPerformancePage.jsx

// ✅ KEEP: AstorAnalysisService.java
- statusByTimeSlot()       → Used by AstorStatsPage.jsx
- durationByLocation()     → Used by AstorStatsPage.jsx
```

**Action Items**:
- [x] Delete unused methods from `ZesAnalysisService.java`
- [x] Delete corresponding controller endpoints
- [x] Remove unused API functions from `zesApi.js`

---

### Phase 2: Focus on Charging Status Only

#### 2.1 Backend: Filter Non-Charging Statuses

**Current Problem**: UI shows all statuses (Available, Charging, Offline, Unknown, etc.) which creates noise and misleading visualizations.

**Solution**: Focus analytics on "Charging" status as the primary KPI.

**Implementation**:

```java
// ZesConnectorTransformAnalysisService.java (MODIFY)

public Map<String, Map<String, Long>> statusByTimeSlot(...) {
    // ... existing scroll code ...

    for (String slot : allSlots) {
        String status = slotStatus.getOrDefault(slot, "Unknown");

        // ✅ NEW: Only track Charging + one aggregate "Other" category
        String simplifiedStatus = "Charging".equals(status) ? "Charging" : "Other";
        result.get(slot).merge(simplifiedStatus, 1L, Long::sum);
    }

    return result;
}
```

**Alternative Approach** (more granular control via UI):

```java
// Keep backend as-is, but add optional `statusFilter` parameter
public Map<String, Map<String, Long>> statusByTimeSlot(
        String date, int intervalMinutes, String connectorType,
        String province, List<String> statusesToInclude) {

    // Default to Charging-only if not specified
    if (statusesToInclude == null || statusesToInclude.isEmpty()) {
        statusesToInclude = List.of("Charging");
    }

    // ... filter aggregation results to only include requested statuses ...
}
```

**Recommendation**: **Keep backend flexible**, implement filtering in **frontend** for better UX control.

---

#### 2.2 Frontend: Charging-Focused Visualizations

**Current State**: Charts show 5-6 different status colors creating visual clutter.

**New Design**: Monochrome + single accent color for Charging.

```jsx
// components/TimeSlotChart.jsx (REDESIGN)

const CHARGING_ONLY_COLORS = {
  Charging: '#10b981',    // Vibrant green (primary KPI)
  Other:    '#475569',    // Muted gray
};

function TimeSlotChart({ data, showOnlyCharging = true }) {
  // Filter data to show only Charging status
  const processedData = Object.entries(data).map(([slot, statuses]) => {
    if (showOnlyCharging) {
      return {
        time: slot,
        Charging: statuses.Charging || 0,
        // Optionally include "Total" for context
        Total: Object.values(statuses).reduce((a, b) => a + b, 0),
      };
    }
    return { time: slot, ...statuses };
  });

  return (
    <ResponsiveContainer width="100%" height={360}>
      <LineChart data={processedData}>
        <CartesianGrid strokeDasharray="3 3" stroke="#2d3748" />
        <XAxis
          dataKey="time"
          tick={{ fill: '#94a3b8', fontSize: 11 }}
          interval="preserveStartEnd"
        />
        <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} />
        <Tooltip
          contentStyle={{
            background: '#1a202c',
            border: '1px solid #2d3748',
            borderRadius: 8,
          }}
        />
        <Legend
          wrapperStyle={{ fontSize: 12, color: '#cbd5e1' }}
        />

        {/* Primary line: Charging connectors */}
        <Line
          type="monotone"
          dataKey="Charging"
          stroke="#10b981"
          strokeWidth={3}
          dot={{ fill: '#10b981', r: 3 }}
          activeDot={{ r: 6 }}
        />

        {/* Secondary line: Total (for context) */}
        <Line
          type="monotone"
          dataKey="Total"
          stroke="#475569"
          strokeWidth={1.5}
          strokeDasharray="5 5"
          dot={false}
        />
      </LineChart>
    </ResponsiveContainer>
  );
}
```

---

### Phase 3: Performance Index Auto-Build

#### 3.1 Automated Daily Performance Index

**Current Problem**: Performance index requires manual POST trigger, often forgotten.

**Solution**: Auto-build every night at 00:15 (after connector_transform finishes at 23:59).

**Implementation**:

```java
// ZesScheduler.java (ADD NEW SCHEDULER)

/**
 * Automatically builds performance index for previous day at 00:15.
 * Runs after daily finalization (23:59) ensures connector_transform is ready.
 */
@Scheduled(cron = "0 15 0 * * *")  // 00:15:00 daily
public void autoBuildPerformanceIndex() {
    LocalDate yesterday = LocalDate.now().minusDays(1);
    String date = yesterday.toString();

    try {
        // Check if connector_transform index exists
        if (!connectorTransformService.indexExists(date)) {
            System.err.printf(
                "[PERF AUTO-BUILD SKIP] connector_transform-%s does not exist%n",
                date
            );
            return;
        }

        // Check if performance index already exists (idempotent)
        if (performanceIndexService.indexExists(date)) {
            System.out.printf(
                "[PERF AUTO-BUILD SKIP] performance-%s already exists%n",
                date
            );
            return;
        }

        System.out.printf(
            "[PERF AUTO-BUILD START] Building performance index for %s%n",
            date
        );

        int count = performanceIndexService.buildPerformanceIndex(date, 1.0);

        System.out.printf(
            "[PERF AUTO-BUILD DONE] %d stations indexed for %s%n",
            count, date
        );

    } catch (Exception e) {
        System.err.printf(
            "[PERF AUTO-BUILD ERROR] %s — %s%n",
            date, e.getMessage()
        );
        e.printStackTrace();
    }
}
```

**Add helper method**:

```java
// ZesConnectorTransformService.java (ADD)
public boolean indexExists(String date) throws IOException {
    String indexName = TARGET_INDEX_PREFIX + "-" +
        (date != null ? date : LocalDate.now().toString());
    return client.indices().exists(r -> r.index(indexName)).value();
}

// ZesStationPerformanceIndexService.java (ADD)
public boolean indexExists(String date) throws IOException {
    String indexName = TARGET_PREFIX + "-" +
        (date != null ? date : LocalDate.now().toString());
    return client.indices().exists(r -> r.index(indexName)).value();
}
```

---

### Phase 4: Backfill Mechanism for Historical Data

#### 4.1 Backend: Batch Backfill Endpoint

**Requirement**: UI button to sync missing performance indexes for date ranges.

**Implementation**:

```java
// ZesAnalysisController.java (ADD NEW ENDPOINT)

/**
 * Backfills missing performance indexes for a date range.
 * Only creates indexes where connector_transform exists but performance is missing.
 *
 * POST /zes/availability/station-performance/backfill
 *   ?startDate=2026-04-01&endDate=2026-05-14&radius=1.0
 *
 * Response:
 * {
 *   "requested_range": { "start": "2026-04-01", "end": "2026-05-14" },
 *   "total_dates": 44,
 *   "skipped": 10,        // connector_transform missing
 *   "existing": 5,        // performance index already exists
 *   "built": 29,          // newly created
 *   "failed": 0,
 *   "details": [
 *     { "date": "2026-04-01", "status": "BUILT", "stations": 1818 },
 *     { "date": "2026-04-02", "status": "SKIPPED", "reason": "connector_transform missing" },
 *     ...
 *   ]
 * }
 */
@PostMapping("/station-performance/backfill")
public Map<String, Object> backfillPerformanceIndex(
        @RequestParam(name = "startDate") String startDate,
        @RequestParam(name = "endDate")   String endDate,
        @RequestParam(name = "radius", defaultValue = "1.0") double radius) {

    LocalDate start = LocalDate.parse(startDate);
    LocalDate end   = LocalDate.parse(endDate);

    List<Map<String, Object>> details = new ArrayList<>();
    int skipped = 0, existing = 0, built = 0, failed = 0;

    for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
        String dateStr = date.toString();
        Map<String, Object> result = new HashMap<>();
        result.put("date", dateStr);

        try {
            // Check if connector_transform exists
            boolean hasConnectorTransform =
                connectorTransformService.indexExists(dateStr);

            if (!hasConnectorTransform) {
                result.put("status", "SKIPPED");
                result.put("reason", "connector_transform index missing");
                skipped++;
                details.add(result);
                continue;
            }

            // Check if performance index already exists
            if (performanceIndexService.indexExists(dateStr)) {
                result.put("status", "EXISTING");
                existing++;
                details.add(result);
                continue;
            }

            // Build performance index
            int count = performanceIndexService.buildPerformanceIndex(dateStr, radius);
            result.put("status", "BUILT");
            result.put("stations", count);
            built++;
            details.add(result);

        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            failed++;
            details.add(result);
        }
    }

    long totalDates = ChronoUnit.DAYS.between(start, end) + 1;

    return Map.of(
        "requested_range", Map.of("start", startDate, "end", endDate),
        "total_dates", totalDates,
        "skipped", skipped,
        "existing", existing,
        "built", built,
        "failed", failed,
        "details", details
    );
}
```

---

#### 4.2 Frontend: Backfill UI Component

**Design**: Modal dialog with date pickers and progress display.

```jsx
// components/PerformanceBackfillModal.jsx (NEW FILE)

import React, { useState } from 'react';

export default function PerformanceBackfillModal({ onClose }) {
  const [startDate, setStartDate] = useState('2026-04-01');
  const [endDate, setEndDate] = useState(
    new Date().toISOString().split('T')[0]
  );
  const [radius, setRadius] = useState(1.0);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);

  const handleBackfill = async () => {
    setLoading(true);
    setResult(null);

    try {
      const response = await fetch(
        `/zes/availability/station-performance/backfill?` +
        `startDate=${startDate}&endDate=${endDate}&radius=${radius}`,
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
        <h2 style={{ color: '#e2e8f0', marginBottom: 8 }}>
          Performance Index Senkronizasyonu
        </h2>
        <p style={{ color: '#94a3b8', fontSize: 14, marginBottom: 24 }}>
          Belirtilen tarih aralığında connector verisi olan ancak performance
          index'i eksik olan günler için otomatik index oluşturulacak.
        </p>

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
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'left' }}>Tarih</th>
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'left' }}>Durum</th>
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'right' }}>İstasyon</th>
                    <th style={{ padding: '8px 4px', color: '#64748b', textAlign: 'left' }}>Not</th>
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
```

**Integration into ZesPerformancePage.jsx**:

```jsx
// pages/ZesPerformancePage.jsx (ADD BUTTON)

import PerformanceBackfillModal from '../components/PerformanceBackfillModal';

export default function ZesPerformancePage({ date, onDateChange }) {
  const [showBackfillModal, setShowBackfillModal] = useState(false);

  return (
    <>
      <div className="page-controls">
        <div className="controls">
          <label>Tarih</label>
          <input type="date" value={date} onChange={e => onDateChange(e.target.value)} />

          <button onClick={loadStats} disabled={statsLoading}>
            {statsLoading ? 'Yükleniyor…' : 'Yenile'}
          </button>

          {/* ✅ NEW: Backfill Button */}
          <button
            onClick={() => setShowBackfillModal(true)}
            style={{
              background: '#3b82f6',
              color: '#fff',
              border: 'none',
              padding: '8px 16px',
              borderRadius: 6,
              fontSize: 13,
              fontWeight: 600,
              cursor: 'pointer',
            }}
          >
            📊 Index Senkronize Et
          </button>
        </div>
      </div>

      {/* ... existing charts ... */}

      {showBackfillModal && (
        <PerformanceBackfillModal
          onClose={() => setShowBackfillModal(false)}
        />
      )}
    </>
  );
}
```

---

### Phase 5: Monochrome Professional UI Redesign

#### 5.1 Design System

**Color Palette**:

```css
/* Base Colors (Grayscale) */
--bg-primary:   #0f172a;    /* Dark slate */
--bg-secondary: #1a202c;    /* Slightly lighter */
--bg-tertiary:  #1e293b;    /* Cards */
--border:       #2d3748;    /* Subtle borders */

/* Text Colors */
--text-primary:   #e2e8f0;  /* High contrast white */
--text-secondary: #94a3b8;  /* Medium gray */
--text-tertiary:  #64748b;  /* Low contrast */

/* Accent Colors (Minimal Use) */
--accent-primary:  #10b981; /* Green (Charging) */
--accent-warning:  #f59e0b; /* Amber (Alerts) */
--accent-danger:   #ef4444; /* Red (Errors) */
--accent-info:     #3b82f6; /* Blue (Info) */
```

**Typography**:

```css
/* Font Stack */
--font-sans: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
--font-mono: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;

/* Font Sizes */
--text-xs:   11px;
--text-sm:   13px;
--text-base: 15px;
--text-lg:   18px;
--text-xl:   24px;
```

---

#### 5.2 Component Redesign Examples

**Global Styles** (index.css):

```css
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: var(--font-sans);
  background: var(--bg-primary);
  color: var(--text-primary);
  line-height: 1.6;
  -webkit-font-smoothing: antialiased;
}

.card {
  background: var(--bg-tertiary);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 16px;
}

.page-controls {
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border);
  padding: 16px 24px;
  margin-bottom: 16px;
}

.controls {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.controls label {
  font-size: var(--text-xs);
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 600;
}

.controls input,
.controls select {
  padding: 8px 12px;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  color: var(--text-primary);
  font-size: var(--text-sm);
}

.controls input:focus,
.controls select:focus {
  outline: none;
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.1);
}

.controls button {
  padding: 8px 16px;
  background: var(--accent-primary);
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.controls button:hover {
  background: #059669;
  transform: translateY(-1px);
}

.controls button:disabled {
  background: var(--border);
  cursor: not-allowed;
  transform: none;
}

/* Data Tables */
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.data-table thead th {
  padding: 12px 8px;
  text-align: left;
  color: var(--text-tertiary);
  font-size: var(--text-xs);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-weight: 600;
  border-bottom: 1px solid var(--border);
}

.data-table tbody td {
  padding: 10px 8px;
  border-bottom: 1px solid rgba(45, 55, 72, 0.3);
}

.data-table tbody tr:hover {
  background: var(--bg-secondary);
}

/* Loading & Error States */
.loading {
  text-align: center;
  color: var(--text-secondary);
  padding: 40px;
  font-size: var(--text-sm);
}

.error {
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: #fca5a5;
  padding: 12px 16px;
  border-radius: 8px;
  margin-bottom: 16px;
  font-size: var(--text-sm);
}

/* Modal Overlay */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
}
```

**Chart Theme Override**:

```jsx
// components/TimeSlotChart.jsx (REDESIGNED)

const CHART_THEME = {
  background: 'transparent',
  textColor: '#94a3b8',
  fontSize: 11,
  grid: {
    stroke: '#2d3748',
    strokeDasharray: '3 3',
  },
  tooltip: {
    background: '#1a202c',
    border: '1px solid #2d3748',
    borderRadius: 8,
    textColor: '#e2e8f0',
  },
};

function TimeSlotChart({ data }) {
  const processedData = Object.entries(data).map(([slot, statuses]) => ({
    time: slot,
    Charging: statuses.Charging || 0,
  }));

  return (
    <ResponsiveContainer width="100%" height={360}>
      <LineChart data={processedData} margin={{ top: 10, right: 20, left: 0, bottom: 20 }}>
        <defs>
          <linearGradient id="chargingGradient" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="#10b981" stopOpacity={0.3} />
            <stop offset="95%" stopColor="#10b981" stopOpacity={0} />
          </linearGradient>
        </defs>

        <CartesianGrid
          strokeDasharray={CHART_THEME.grid.strokeDasharray}
          stroke={CHART_THEME.grid.stroke}
        />

        <XAxis
          dataKey="time"
          tick={{ fill: CHART_THEME.textColor, fontSize: CHART_THEME.fontSize }}
          interval="preserveStartEnd"
          stroke={CHART_THEME.grid.stroke}
        />

        <YAxis
          tick={{ fill: CHART_THEME.textColor, fontSize: CHART_THEME.fontSize }}
          stroke={CHART_THEME.grid.stroke}
        />

        <Tooltip
          contentStyle={CHART_THEME.tooltip}
          labelStyle={{ color: CHART_THEME.tooltip.textColor, fontWeight: 600 }}
          itemStyle={{ color: '#10b981' }}
        />

        <Legend
          wrapperStyle={{ fontSize: 12, color: '#cbd5e1', paddingTop: 10 }}
        />

        <Area
          type="monotone"
          dataKey="Charging"
          stroke="#10b981"
          fill="url(#chargingGradient)"
          strokeWidth={0}
        />

        <Line
          type="monotone"
          dataKey="Charging"
          stroke="#10b981"
          strokeWidth={3}
          dot={{ fill: '#10b981', r: 3 }}
          activeDot={{ r: 6, stroke: '#0f172a', strokeWidth: 2 }}
        />
      </LineChart>
    </ResponsiveContainer>
  );
}
```

---

## 🚀 Implementation Checklist

### Week 1: Backend Cleanup
- [ ] Delete unused methods from `ZesAnalysisService.java` (statusByConnectorType, statusByProvince, statusByTimeSlot)
- [ ] Remove corresponding endpoints from `ZesAnalysisController.java`
- [ ] Add `indexExists()` helper methods to services
- [ ] Implement auto-build scheduler in `ZesScheduler.java`
- [ ] Test auto-build on staging environment

### Week 2: Backfill Mechanism
- [ ] Implement backfill endpoint in `ZesAnalysisController.java`
- [ ] Create `PerformanceBackfillModal.jsx` component
- [ ] Integrate backfill button into `ZesPerformancePage.jsx`
- [ ] Add loading states and error handling
- [ ] Test backfill for 30-day range

### Week 3: UI Redesign
- [ ] Update global styles (index.css) with monochrome design system
- [ ] Redesign `TimeSlotChart.jsx` (Charging-focused)
- [ ] Redesign `TimelineChart.jsx`
- [ ] Update `ZesStatsPage.jsx` layout
- [ ] Update `AstorStatsPage.jsx` layout
- [ ] Implement charging-only filter in all charts

### Week 4: Testing & Deployment
- [ ] Integration testing (polling → H2 → ES → UI)
- [ ] Performance testing (backfill for 90 days)
- [ ] UI/UX testing (responsive, dark mode)
- [ ] Staging deployment
- [ ] Production deployment
- [ ] Monitor auto-build logs for 7 days

---

## 📈 Expected Improvements

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| **Backend Codebase** | 3 analysis services, 15+ endpoints | 2 services, 8 essential endpoints | **-50% code** |
| **Performance Index** | Manual trigger (error-prone) | Auto-build at 00:15 | **100% reliable** |
| **Historical Backfill** | Manual API calls (tedious) | UI button (1-click) | **10x faster** |
| **UI Chart Clarity** | 5-6 colors (cluttered) | 2 colors (focused) | **80% clearer** |
| **Visual Design** | Colorful, inconsistent | Monochrome, professional | **Enterprise-grade** |

---

## 🔧 Development Setup

### Backend

```bash
cd charging-tracker

# Start Elasticsearch
docker run -d -p 9200:9200 -e "discovery.type=single-node" elasticsearch:8.12.0

# Run application
./mvnw spring-boot:run
```

### Frontend

```bash
cd charging-dashboard

# Install dependencies
npm install

# Start dev server
npm run dev
```

### Environment Variables

```properties
# application.properties
zes.poll.intervalMs=300000                # 5 minutes
zes.daily.cron=0 59 23 * * *              # 23:59 daily
zes.history.keepDays=7                    # H2 retention

# Performance auto-build (NEW)
zes.performance.autoBuildCron=0 15 0 * * *  # 00:15 daily
```

---

## 📚 API Documentation

### Backfill Endpoint

**POST** `/zes/availability/station-performance/backfill`

**Query Parameters**:
- `startDate` (required): YYYY-MM-DD format
- `endDate` (required): YYYY-MM-DD format
- `radius` (optional, default=1.0): Nearby station search radius in km

**Example**:
```bash
curl -X POST "http://localhost:8080/zes/availability/station-performance/backfill?startDate=2026-04-01&endDate=2026-05-14&radius=1.0"
```

**Response**:
```json
{
  "requested_range": { "start": "2026-04-01", "end": "2026-05-14" },
  "total_dates": 44,
  "skipped": 5,
  "existing": 10,
  "built": 29,
  "failed": 0,
  "details": [
    { "date": "2026-04-01", "status": "BUILT", "stations": 1818 },
    { "date": "2026-04-02", "status": "EXISTING" },
    { "date": "2026-04-03", "status": "SKIPPED", "reason": "connector_transform missing" }
  ]
}
```

---

## 🧪 Testing Strategy

### Backend Tests

```java
// ZesPerformanceBackfillTest.java

@Test
void testBackfill_successfullyBuildsMultipleDates() {
    // Given: connector_transform exists for 2026-04-01 to 2026-04-05
    // When: backfill called with that range
    // Then: 5 performance indexes created
}

@Test
void testBackfill_skipsIfConnectorTransformMissing() {
    // Given: connector_transform missing for 2026-04-03
    // When: backfill called with 2026-04-01 to 2026-04-05
    // Then: result shows 1 skipped, 4 built
}

@Test
void testBackfill_idempotent() {
    // Given: performance index already exists for 2026-04-01
    // When: backfill called again
    // Then: result shows "EXISTING", no duplicate created
}
```

### Frontend Tests

```jsx
// PerformanceBackfillModal.test.jsx

test('renders date pickers with default values', () => {
  render(<PerformanceBackfillModal />);
  expect(screen.getByLabelText(/Başlangıç Tarihi/)).toHaveValue('2026-04-01');
});

test('calls API with correct parameters on submit', async () => {
  const mockFetch = jest.fn();
  global.fetch = mockFetch;

  render(<PerformanceBackfillModal />);

  fireEvent.change(screen.getByLabelText(/Başlangıç/), { target: { value: '2026-04-01' } });
  fireEvent.click(screen.getByText(/Senkronize Et/));

  expect(mockFetch).toHaveBeenCalledWith(
    '/zes/availability/station-performance/backfill?startDate=2026-04-01&endDate=...',
    { method: 'POST' }
  );
});
```

---

## 🎨 UI Design Mockups

### Before vs After

**Before** (Current):
```
┌─────────────────────────────────────────┐
│ [Colorful status pie chart with 6 colors]│
│ - Available (green)                      │
│ - Charging (blue)                        │
│ - Offline (gray)                         │
│ - Unknown (yellow)                       │
│ - Faulted (red)                          │
│ - Reserved (purple)                      │
└─────────────────────────────────────────┘
```

**After** (New):
```
┌─────────────────────────────────────────┐
│ [Clean line chart with gradient fill]   │
│                                          │
│  Charging Connectors (green line)       │
│  ▁▂▃▅▇██████▇▅▃▂▁                       │
│                                          │
│  Focus: Charging utilization only       │
│  Background: Monochrome gray tones      │
└─────────────────────────────────────────┘
```

---

## 🔐 Security Considerations

- **No Authentication Required**: Internal dashboard (private network)
- **H2 Database**: In-memory, no sensitive data persistence
- **Elasticsearch**: Read-only queries from UI
- **API Rate Limiting**: TBD (add if needed)

---

## 📊 Monitoring & Alerts

### Key Metrics to Track

```java
// Custom Prometheus metrics (optional)

@Component
public class ZesMetrics {
    @Autowired
    private MeterRegistry registry;

    public void recordPerformanceIndexBuild(String date, int stationCount, long durationMs) {
        registry.timer("zes.performance.build.duration").record(durationMs, TimeUnit.MILLISECONDS);
        registry.gauge("zes.performance.build.stations", stationCount);
    }

    public void recordBackfillProgress(int built, int skipped, int failed) {
        registry.counter("zes.backfill.built").increment(built);
        registry.counter("zes.backfill.skipped").increment(skipped);
        registry.counter("zes.backfill.failed").increment(failed);
    }
}
```

### Alerts

- **Critical**: Performance index auto-build failed 2 consecutive days
- **Warning**: Backfill request took > 5 minutes
- **Info**: H2 database size > 500MB (retention cleanup needed)

---

## 🚦 Deployment Strategy

### Staging Deployment

1. Deploy backend changes
2. Run smoke tests (polling, daily finalization)
3. Test backfill for 7-day range
4. Deploy frontend changes
5. User acceptance testing (1 week)

### Production Deployment

1. **Pre-deployment**:
   - Backup Elasticsearch indexes
   - Schedule maintenance window (Sunday 02:00 AM)

2. **Deployment**:
   - Deploy backend (auto-build scheduler)
   - Wait for 00:15 auto-build (verify logs)
   - Deploy frontend (backfill UI)

3. **Post-deployment**:
   - Monitor logs for 48 hours
   - Run backfill for last 30 days
   - Verify all performance indexes created

### Rollback Plan

- Backend: Revert to previous JAR (disable auto-build scheduler)
- Frontend: Revert to previous build (remove backfill button)
- Data: Elasticsearch indexes are immutable (no rollback needed)

---

## 📞 Support & Maintenance

### Common Issues

**Q: Auto-build didn't run at 00:15**
A: Check scheduler logs. Ensure connector_transform finished before 00:15.

**Q: Backfill shows "FAILED" for some dates**
A: Check Elasticsearch cluster health. Verify connector_transform index exists for that date.

**Q: UI shows "No data" for today**
A: Normal before first polling cycle (5min). Check H2 database has records.

---

## 📄 License

Proprietary - Internal Use Only

---

## 🤝 Contributing

This is an internal project. For questions or suggestions, contact the development team.

---

**Last Updated**: 2026-05-14
**Version**: 2.0.0
**Maintainer**: Charging Infrastructure Team
