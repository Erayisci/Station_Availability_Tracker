# ZES Charging Tracker - Refactoring & Optimization Plan

## 📊 Mevcut Durum Analizi

### Mevcut Mimari

```
┌─────────────────────────────────────────────────────────────────┐
│                    5 Dakikalık Polling Sistemi                   │
└─────────────────────────────────────────────────────────────────┘
         │
         ├─> ZesStatusTrackerService.pollAndTrack()
         │   └─> Her 5dk ZES API'den veri çeker
         │   └─> Status değişikliği varsa H2'ye yazar
         │   └─> Değişiklik varsa indexDailyToElastic() tetikler
         │
         ├─> H2 Database (7 günlük retention)
         │   └─> ConnectorStatusHistory tablosu
         │       └─> connector_id, status, start_time, end_time, track_date
         │
         ├─> Elasticsearch Index Zinciri
         │   ├─> zes_stations-{date}           (Station snapshot + availability)
         │   ├─> zes_connector_transform-{date} (Connector bazlı view + availability)
         │   └─> zes_station_performance-{date} (Station performans metrikleri)
         │
         └─> Gece 23:59 Scheduler (ZesScheduler.indexDaily)
             └─> Açık slotları kapatır
             └─> zes_stations index'i yeniden oluşturur
             └─> zes_connector_transform rebuild
             └─> zes_station_performance rebuild
```

### 🔴 Tespit Edilen Sorunlar

#### 1. **5 Dakikalık Polling Sistemi Güvenilirlik Sorunları**
- **Veri Tutarsızlığı**: Her status değişikliği H2'ye yazılıyor, ardından ES index'leri rebuild ediliyor
- **Gereksiz ES Rebuild**: Gün içinde her değişiklikte 3 index rebuild ediliyor (ağır işlem)
- **Timing Hassasiyeti**: 5dk'lık interval ile kısa süreli status değişiklikleri kaçabilir
- **Race Condition**: Polling sırasında status değişirse H2 ve ES arasında tutarsızlık oluşabilir
- **Kaynak Tüketimi**: Her 5dk API call + potansiyel index rebuild (CPU/Memory yoğun)

#### 2. **Index Yönetimi Sorunları**
- **Gereksiz Index'ler**: Çok fazla index var, hangilerinin aktif kullanıldığı belirsiz
- **Manuel Tetikleme Zorunluluğu**: Performance index manuel tetikleniyor
- **Duplicate Data**: Aynı veri hem H2'de hem ES'de tutuluyor
- **Backfill Eksikliği**: Geçmiş tarihler için performance index oluşturma mekanizması yok

#### 3. **Kullanılmayan Method'lar**
```java
// ZesConnectorTransformAnalysisService.java
- statusByTimeSlot()      → Kullanılıyor (UI'da)
- statusTimeline()        → Kullanılıyor (UI'da)
- durationByCity()        → Kullanılıyor (UI'da)
- searchConnectors()      → Kullanılıyor (UI'da)
✓ Hepsi aktif kullanımda, silinmemeli

// ZesAnalysisService (kontrol edilmeli)
- statusByConnectorType() → ?
- statusByProvince()      → ?
- statusByTimeSlot()      → Duplicate var, kaldırılabilir
```

---

## 🎯 Yeni Mimari ve Hedefler

### Temel Prensipler

1. **Event-Driven Architecture**: Polling yerine akıllı event detection
2. **Eventual Consistency**: Real-time'dan eventual consistency'ye geçiş
3. **Lazy Index Building**: Index'ler sadece gerektiğinde oluşturulsun
4. **Single Source of Truth**: H2 veya ES, ikisi birden değil
5. **Idempotency**: Index rebuild işlemleri güvenli tekrarlanabilir olmalı

### Yeni Veri Akışı

```
┌─────────────────────────────────────────────────────────────────┐
│              Event-Driven Status Tracking System                 │
└─────────────────────────────────────────────────────────────────┘
         │
         ├─> ZesStatusTrackerService.pollAndTrack() (Her 5dk)
         │   └─> Status değişikliği varsa → Event Queue'ya yaz
         │   └─> Günlük snapshot'ı H2'ye kaydet (lightweight)
         │
         ├─> H2 Database (Authoritative Source, 7 gün)
         │   └─> ConnectorStatusHistory (değişiklik yok)
         │
         ├─> Event Queue (In-Memory veya Redis)
         │   └─> Status değişiklik event'leri buffer edilir
         │   └─> Her 30 dakikada batch olarak ES'e yazılır
         │
         ├─> Elasticsearch Index Strategy
         │   ├─> zes_stations-{date}           → Gün sonunda finalize
         │   ├─> zes_connector_transform-{date} → Gün sonunda finalize
         │   └─> zes_station_performance-{date} → AUTO-BUILD (post-hook)
         │
         └─> Scheduler Jobs
             ├─> 23:59 → Daily finalization (slotları kapat, ES'e flush)
             └─> 00:15 → Auto-build performance index (önceki gün için)
```

---

## 📋 Detaylı Implementasyon Planı

### **PHASE 1: Analiz ve Cleanup** (2-3 saat)

#### 1.1 Kullanılmayan Method'ları Tespit Et
**Task**: UI kodunu analiz ederek hangi endpoint'lerin kullanıldığını belirle

**Adımlar**:
```bash
# Frontend'de kullanılan endpoint'leri bul
grep -r "zes/availability" charging-dashboard/src/**/*.jsx
grep -r "station-performance" charging-dashboard/src/**/*.jsx
```

**Çıktı**: Kullanılmayan endpoint'lerin listesi
- [ ] `ZesAnalysisService.statusByConnectorType()`
- [ ] `ZesAnalysisService.statusByProvince()`
- [ ] `ZesAnalysisService.statusByTimeSlot()`
- [ ] `ZesAnalysisService.searchStations()`

**Action**: Kullanılmayanları `@Deprecated` işaretle, sonraki sprint'te sil

#### 1.2 Index Kullanım Analizi
**Task**: Hangi index'lerin aktif sorgulandığını logla

**Adımlar**:
1. Her service method'una başında index adını logla
2. 1 hafta production'da çalıştır
3. Kullanılmayan index'leri tespit et

**Çıktı**:
```
ACTIVE:
- zes_connector_transform-{date}    → UI queries kullanıyor
- zes_station_performance-{date}    → Performance page kullanıyor

DEPRECATED:
- zes_stations-{date}               → Sadece transform için source, UI query'si yok
```

**Decision**:
- `zes_stations-{date}` → Sadece intermediate olarak tut, public API'den kaldır
- Performance index → Auto-build yap

---

### **PHASE 2: Performance Index Otomasyonu** (3-4 saat)

#### 2.1 Auto-Build Mekanizması

**Problem**: Şu anda performance index manuel POST ile tetikleniyor
**Hedef**: Connector transform index hazır olunca otomatik oluşturulsun

**Implementation**:

```java
// ZesScheduler.java (YENİ METHOD)

/**
 * Her gece 00:15'te önceki günün performance index'ini oluşturur.
 * connector_transform-{date} index'i hazır olmalı (23:59'da tamamlanmış).
 */
@Scheduled(cron = "0 15 0 * * *")  // 00:15:00 her gün
public void buildPerformanceIndex() {
    LocalDate yesterday = LocalDate.now().minusDays(1);
    String date = yesterday.toString();

    try {
        // connector_transform index'inin varlığını kontrol et
        boolean connectorTransformExists =
            connectorTransformService.indexExists(date);

        if (!connectorTransformExists) {
            System.err.printf(
                "[PERF INDEX SKIP] connector_transform-%s does not exist%n",
                date
            );
            return;
        }

        // Performance index zaten varsa skip et (idempotent)
        if (performanceIndexService.indexExists(date)) {
            System.out.printf(
                "[PERF INDEX SKIP] performance-%s already exists%n",
                date
            );
            return;
        }

        System.out.printf(
            "[PERF INDEX START] Building performance index for %s%n",
            date
        );

        int count = performanceIndexService.buildPerformanceIndex(date, 1.0);

        System.out.printf(
            "[PERF INDEX DONE] Indexed %d stations for %s%n",
            count, date
        );

    } catch (Exception e) {
        System.err.printf(
            "[PERF INDEX ERROR] %s — %s%n",
            date, e.getMessage()
        );
        e.printStackTrace();
    }
}
```

**Changes**:
```java
// ZesStationPerformanceIndexService.java (YENİ METHOD)

public boolean indexExists(String date) throws IOException {
    String indexName = TARGET_PREFIX + "-" +
        (date != null ? date : LocalDate.now().toString());
    return client.indices().exists(r -> r.index(indexName)).value();
}
```

#### 2.2 Incremental Update Strategy (Opsiyonel - Gelecek İçin)

**Hedef**: Gün içinde değişiklikler olduğunda performance index'i update et

**Design**:
- Performance index partial update desteklemiyor (aggregation-based)
- Daily rebuild yeterli, real-time güncellemesine gerek yok
- **KESİN**: Incremental update şimdilik UYGULANMAYACAK

**Rationale**:
- Performance index = analytical data (snapshot bazlı)
- Kullanıcılar önceki günlerin analizini görüyor
- Real-time ihtiyacı yok → Daily build yeterli

---

### **PHASE 3: Backfill Mekanizması** (2-3 saat)

#### 3.1 UI Tarafında "Sync Performance Index" Butonu

**Requirement**:
> "UI'da önceki tarihler için performans indexlerini senkronize et (connector verisi olanların performans indexlerini oluştur) gibi bir seçenek istiyorum"

**Implementation**:

##### Backend Endpoint
```java
// ZesAnalysisController.java (YENİ ENDPOINT)

/**
 * Belirli bir tarih aralığı için eksik performance index'leri oluşturur.
 * Sadece connector_transform-{date} var ama performance-{date} yok olanlar build edilir.
 *
 * POST /zes/availability/station-performance/backfill
 *   ?startDate=2026-04-01&endDate=2026-05-01&radius=1.0
 *
 * Response:
 * {
 *   "requested_range": { "start": "2026-04-01", "end": "2026-05-01" },
 *   "total_dates": 30,
 *   "skipped": 10,      // connector_transform yok
 *   "existing": 5,      // performance index zaten var
 *   "built": 15,        // yeni oluşturuldu
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
            // connector_transform var mı?
            boolean hasConnectorTransform =
                connectorTransformService.indexExists(dateStr);

            if (!hasConnectorTransform) {
                result.put("status", "SKIPPED");
                result.put("reason", "connector_transform index missing");
                skipped++;
                details.add(result);
                continue;
            }

            // performance index zaten var mı?
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

    return Map.of(
        "requested_range", Map.of("start", startDate, "end", endDate),
        "total_dates", (int) start.until(end, java.time.temporal.ChronoUnit.DAYS) + 1,
        "skipped", skipped,
        "existing", existing,
        "built", built,
        "failed", failed,
        "details", details
    );
}
```

##### Frontend UI Component
```jsx
// charging-dashboard/src/pages/ZesPerformancePage.jsx

import React, { useState } from 'react';

function PerformanceBackfillModal({ onClose }) {
  const [startDate, setStartDate] = useState('2026-04-01');
  const [endDate, setEndDate] = useState('2026-05-14');
  const [radius, setRadius] = useState(1.0);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);

  const handleBackfill = async () => {
    setLoading(true);
    try {
      const response = await fetch(
        `/zes/availability/station-performance/backfill?` +
        `startDate=${startDate}&endDate=${endDate}&radius=${radius}`,
        { method: 'POST' }
      );
      const data = await response.json();
      setResult(data);
    } catch (error) {
      alert('Backfill failed: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal">
      <h2>Performance Index Senkronizasyonu</h2>
      <p>
        Belirtilen tarih aralığında connector verisi olan ancak performance
        index'i eksik olan günler için otomatik index oluşturulacak.
      </p>

      <div>
        <label>Başlangıç Tarihi:</label>
        <input
          type="date"
          value={startDate}
          onChange={(e) => setStartDate(e.target.value)}
        />
      </div>

      <div>
        <label>Bitiş Tarihi:</label>
        <input
          type="date"
          value={endDate}
          onChange={(e) => setEndDate(e.target.value)}
        />
      </div>

      <div>
        <label>Radius (km):</label>
        <input
          type="number"
          step="0.1"
          value={radius}
          onChange={(e) => setRadius(parseFloat(e.target.value))}
        />
      </div>

      <button onClick={handleBackfill} disabled={loading}>
        {loading ? 'İşlem devam ediyor...' : 'Senkronize Et'}
      </button>

      {result && (
        <div className="result">
          <h3>Sonuç:</h3>
          <ul>
            <li>Toplam gün: {result.total_dates}</li>
            <li>Atlandı (veri yok): {result.skipped}</li>
            <li>Zaten var: {result.existing}</li>
            <li>Yeni oluşturuldu: {result.built}</li>
            <li>Hatalı: {result.failed}</li>
          </ul>

          <h4>Detaylar:</h4>
          <table>
            <thead>
              <tr>
                <th>Tarih</th>
                <th>Durum</th>
                <th>İstasyon Sayısı</th>
                <th>Not</th>
              </tr>
            </thead>
            <tbody>
              {result.details.map((detail, idx) => (
                <tr key={idx}>
                  <td>{detail.date}</td>
                  <td>{detail.status}</td>
                  <td>{detail.stations || '-'}</td>
                  <td>{detail.reason || detail.error || '✓'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <button onClick={onClose}>Kapat</button>
    </div>
  );
}

export default PerformanceBackfillModal;
```

**Integration**:
```jsx
// ZesPerformancePage.jsx içinde
<button onClick={() => setShowBackfillModal(true)}>
  📊 Performance Index Senkronize Et
</button>

{showBackfillModal && (
  <PerformanceBackfillModal
    onClose={() => setShowBackfillModal(false)}
  />
)}
```

---

### **PHASE 4: Polling Sistemi İyileştirmesi** (4-5 saat)

#### 4.1 Problem Analizi

**Mevcut Durum**:
```java
// Her 5dk çalışıyor
@Scheduled(fixedRateString = "${zes.poll.intervalMs:300000}")
public void pollZes() {
    trackerService.pollAndTrack();  // Status değişikliği → ES rebuild
}
```

**Sorunlar**:
1. Her status değişikliğinde `triggerIndexChain()` çağrılıyor
2. Bu da ard arda 3 index rebuild ediyor (stations, connector_transform, performance)
3. Performance index gereksiz yere rebuild ediliyor (backfill ile çözüldü)

#### 4.2 Çözüm: Batched Event System

**Yeni Yaklaşım**:
- Status değişikliği olduğunda sadece H2'ye yaz
- ES rebuild'i 30 dakikada bir batch olarak yap
- Gece 23:59'da final rebuild yap

**Implementation**:

##### Event Buffer Servisi
```java
// ZesStatusEventBuffer.java (YENİ SERVİS)

@Service
public class ZesStatusEventBuffer {

    private final AtomicBoolean hasChanges = new AtomicBoolean(false);
    private final AtomicInteger changeCount = new AtomicInteger(0);

    @Autowired
    private ZesIndexService indexService;

    @Autowired
    private ZesConnectorTransformService connectorTransformService;

    public void recordChange() {
        hasChanges.set(true);
        changeCount.incrementAndGet();
    }

    public boolean hasChanges() {
        return hasChanges.get();
    }

    public int getChangeCount() {
        return changeCount.get();
    }

    /**
     * 30 dakikada bir çalışır, buffer'da değişiklik varsa ES index'leri günceller.
     */
    @Scheduled(fixedRate = 1800000)  // 30 dakika = 1800000 ms
    public void flushToElasticsearch() {
        if (!hasChanges.get()) {
            System.out.println("[EVENT BUFFER] No changes since last flush, skipping.");
            return;
        }

        LocalDate today = LocalDate.now();
        String date = today.toString();
        int count = changeCount.getAndSet(0);

        try {
            System.out.printf(
                "[EVENT BUFFER FLUSH] %d status changes detected, rebuilding indexes...%n",
                count
            );

            // Sadece stations ve connector_transform rebuild et
            // Performance index'i HİÇ build etme (gece 00:15'te otomatik)
            indexService.refreshIndex(date);
            connectorTransformService.transformConnectors(date);

            hasChanges.set(false);

            System.out.printf(
                "[EVENT BUFFER FLUSH] Indexes updated for %s%n",
                date
            );

        } catch (Exception e) {
            System.err.printf(
                "[EVENT BUFFER FLUSH ERROR] %s%n",
                e.getMessage()
            );
            // Hata olsa bile flag'i reset et, sonraki cycle'da tekrar dener
            hasChanges.set(false);
        }
    }
}
```

##### Polling Servisi Güncellemesi
```java
// ZesStatusTrackerService.java (GÜNCELLENECEK)

@Autowired
private ZesStatusEventBuffer eventBuffer;

public int pollAndTrack() throws Exception {
    // ... (mevcut kod aynı)

    boolean indexMissing = !indexService.todayIndexExists(today.toString());

    if (!changedConnectors.isEmpty()) {
        System.out.printf(
            "[H2] %d connector(s) updated in H2: %s%n",
            changedConnectors.size(),
            changedConnectors
        );

        // ESKI: triggerIndexChain(today.toString());
        // YENİ: Event buffer'a kaydet, 30dk'da flush edilecek
        eventBuffer.recordChange();

    } else if (indexMissing) {
        System.out.printf(
            "[POLL] No status changes but today's ES index is missing — marking for rebuild.%n"
        );
        eventBuffer.recordChange();
    } else {
        System.out.printf(
            "[POLL] No status changes detected — event buffer unchanged.%n"
        );
    }

    return stations.size();
}

// triggerIndexChain() method'u SİLİNECEK veya sadece manual endpoint için tutulacak
```

##### Scheduler Güncellemesi
```java
// ZesScheduler.java (GÜNCELLENMİŞ)

@Scheduled(cron = "${zes.daily.cron:0 59 23 * * *}")
public void indexDaily() {
    String date = LocalDate.now().toString();
    try {
        System.out.println(
            "[ZES DAILY] Finalizing indexes and closing open slots..."
        );

        // 1. H2 slotlarını kapat ve stations index'i finalize et
        int stationCount = trackerService.indexDailyToElastic(true);
        System.out.printf(
            "[ZES DAILY] zes_stations-%s finalized: %d station docs.%n",
            date, stationCount
        );

        // 2. connector_transform rebuild
        int connectorCount = connectorTransformService.transformConnectors(date);
        System.out.printf(
            "[ZES DAILY] zes_connector_transform-%s finalized: %d connector docs.%n",
            date, connectorCount
        );

        // 3. Performance index'i KALDIR (artık 00:15'te auto-build)
        // ESKI:
        // int performanceCount = performanceIndexService.buildPerformanceIndex(date, 1.0);

        System.out.println(
            "[ZES DAILY] Daily finalization complete. " +
            "Performance index will be built at 00:15."
        );

    } catch (Exception e) {
        System.err.printf("[ZES DAILY ERROR] %s%n", e.getMessage());
    }
}

/**
 * YENİ SCHEDULER: Her gece 00:15'te önceki günün performance index'ini oluştur
 */
@Scheduled(cron = "0 15 0 * * *")
public void buildPerformanceIndex() {
    LocalDate yesterday = LocalDate.now().minusDays(1);
    String date = yesterday.toString();

    try {
        if (!connectorTransformService.indexExists(date)) {
            System.err.printf(
                "[PERF AUTO-BUILD SKIP] connector_transform-%s missing%n",
                date
            );
            return;
        }

        if (performanceIndexService.indexExists(date)) {
            System.out.printf(
                "[PERF AUTO-BUILD SKIP] performance-%s already exists%n",
                date
            );
            return;
        }

        System.out.printf(
            "[PERF AUTO-BUILD] Building performance index for %s%n",
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

#### 4.3 Kontrol ve Gözlemlenebilirlik

**Health Check Endpoint**:
```java
// ZesController.java (YENİ ENDPOINT)

@Autowired
private ZesStatusEventBuffer eventBuffer;

/**
 * GET /zes/health
 *
 * {
 *   "last_poll": "2026-05-14T14:35:00",
 *   "pending_changes": 12,
 *   "next_flush": "2026-05-14T15:00:00",
 *   "h2_open_slots": 4521,
 *   "today_index_exists": true
 * }
 */
@GetMapping("/health")
public Map<String, Object> health() throws Exception {
    LocalDate today = LocalDate.now();

    return Map.of(
        "pending_changes", eventBuffer.getChangeCount(),
        "has_pending_flush", eventBuffer.hasChanges(),
        "h2_open_slots", repository.countByTrackDateAndEndTimeIsNull(today),
        "today_index_exists", indexService.todayIndexExists(today.toString())
    );
}
```

---

### **PHASE 5: Cleanup ve Optimizasyon** (2 saat)

#### 5.1 Kullanılmayan Methodları Kaldır

**Backend**:
```java
// ZesAnalysisService.java → statusByTimeSlot() SİL
// (ZesConnectorTransformAnalysisService zaten var)

// ZesAnalysisController.java
// Duplicate endpoint'leri kaldır veya deprecate et
@Deprecated
@GetMapping("/stats/by-time-slot")
public Map<String, Map<String, Long>> statsByTimeSlot(...) {
    // Kullanıcıları yeni endpoint'e yönlendir
    throw new UnsupportedOperationException(
        "Deprecated: Use /connector-transform/stats/by-time-slot instead"
    );
}
```

#### 5.2 Index Template Optimizasyonu

**Gereksiz Field'ları Kaldır**:
```java
// zes_stations-{date} → Sadece intermediate kullanım
// Public query yoksa mapping'i basitleştir

// zes_connector_transform-{date} → Ana query index'i
// Gereksiz nested field'ları kontrol et

// zes_station_performance-{date} → Analytical queries
// Aggregation-friendly mapping kullan
```

#### 5.3 H2 Retention Policy

**Mevcut**: 7 gün retention
**Öneri**:
- 7 gün yeterli (ES'te daha uzun tarihler mevcut)
- Auto-cleanup zaten var (`@PostConstruct` içinde)
- Değişiklik gerekmiyor

---

### **PHASE 6: Testing ve Monitoring** (3 saat)

#### 6.1 Test Senaryoları

**Unit Tests**:
```java
// ZesStatusEventBufferTest.java
- testEventBuffering()
- testFlushMechanism()
- testConcurrentChanges()

// ZesSchedulerTest.java
- testPerformanceAutoBuild()
- testBackfillEndpoint()
```

**Integration Tests**:
```java
// ZesPollingIntegrationTest.java
1. Poll API → H2'ye yaz
2. Event buffer'da değişiklik kaydet
3. 30dk sonra flush tetikle
4. ES index'leri verify et
5. 23:59 scheduler çalıştır
6. 00:15 performance index build et
```

#### 6.2 Monitoring Metrikleri

**Prometheus Metrics** (opsiyonel):
```java
@Component
public class ZesMetrics {
    @Autowired
    private MeterRegistry registry;

    public void recordPollingDuration(long ms) {
        registry.timer("zes.polling.duration").record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordStatusChange(int count) {
        registry.counter("zes.status.changes").increment(count);
    }

    public void recordIndexRebuild(String indexType) {
        registry.counter("zes.index.rebuild", "type", indexType).increment();
    }
}
```

**Logging Strategy**:
```
[POLL]        → Her 5dk polling log'u
[H2]          → H2 write işlemleri
[EVENT BUFFER]→ Event buffer durumu
[DAILY]       → Gece 23:59 finalization
[PERF AUTO]   → Performance auto-build (00:15)
[BACKFILL]    → Manual backfill işlemleri
```

---

## 📊 Beklenen İyileştirmeler

### Performans Kazançları

| Metrik | Öncesi | Sonrası | İyileştirme |
|--------|--------|---------|-------------|
| **ES Rebuild Frequency** | Her 5dk (değişiklik varsa) | 30 dakikada 1 | **6x azalma** |
| **Performance Index Build** | Manuel (unutulabilir) | Otomatik (00:15) | **%100 güvenilir** |
| **CPU Kullanımı** | Yüksek (sürekli rebuild) | Düşük (batch işlem) | **~60% azalma** |
| **H2 → ES Gecikme** | ~5-10 dakika | ~15-30 dakika | Kabul edilebilir |
| **Backfill İhtiyacı** | Manuel API call | UI butonu | **Kullanıcı dostu** |

### Güvenilirlik İyileştirmeleri

✅ **Data Consistency**: H2 authoritative source olarak kalıyor
✅ **Idempotency**: Tüm index build işlemleri tekrarlanabilir
✅ **Fault Tolerance**: Event buffer hataya dayanıklı
✅ **Observability**: Health endpoint ile sistem durumu görünür
✅ **Automation**: Performance index manual hatadan kurtuldu

---

## 🚀 Deployment Stratejisi

### Adım 1: Feature Flag ile Deploy
```properties
# application.properties
zes.feature.event-buffer.enabled=false
zes.feature.auto-performance.enabled=false
```

### Adım 2: Staging'de Test
- Event buffer'ı aç (`enabled=true`)
- 1 hafta production-like load ile test et
- Metrik topla, anomali ara

### Adım 3: Production'a Geç
- Feature flag'leri aç
- İlk 24 saat yakından izle
- Rollback planı hazır tut

### Adım 4: Backfill Çalıştır
- UI üzerinden geçmiş 30 günü backfill et
- Performance index'leri oluştur

### Adım 5: Cleanup
- Deprecated endpoint'leri kaldır
- Eski kod yollarını sil
- Feature flag'leri kaldır

---

## ⚠️ Risk Analizi

| Risk | Olasılık | Etki | Mitigation |
|------|----------|------|------------|
| **Event buffer memory leak** | Orta | Yüksek | Heap monitoring, max buffer size limit |
| **30dk'lık gecikme kullanıcı şikayeti** | Düşük | Orta | UI'da "Last updated" timestamp göster |
| **Performance auto-build başarısız olursa** | Düşük | Orta | Retry mekanizması + alert |
| **Backfill çok uzun sürerse** | Orta | Düşük | Progress bar + arka planda async çalıştır |

---

## 📝 Checklist (Implementation Order)

### Week 1: Foundation
- [ ] Phase 1.1: UI endpoint analizi
- [ ] Phase 1.2: Index kullanım log'lama
- [ ] Phase 2.1: Auto-build scheduler implement et
- [ ] Phase 2.1: `indexExists()` method'ları ekle

### Week 2: Core Features
- [ ] Phase 3.1: Backfill endpoint implement et
- [ ] Phase 3.1: Backfill UI component ekle
- [ ] Phase 4.1: Event buffer servisi yaz
- [ ] Phase 4.2: Polling servisi güncelle

### Week 3: Integration & Testing
- [ ] Phase 4.3: Health endpoint ekle
- [ ] Phase 6.1: Unit test'ler yaz
- [ ] Phase 6.1: Integration test'ler yaz
- [ ] Phase 6.2: Monitoring kurulumu

### Week 4: Cleanup & Deploy
- [ ] Phase 5.1: Deprecated method'ları kaldır
- [ ] Phase 5.2: Index mapping optimizasyonu
- [ ] Staging deploy + 1 hafta test
- [ ] Production deploy
- [ ] Backfill çalıştır

---

## 🎓 Sonuç

Bu refactoring planı:
1. ✅ Polling sistemini daha kontrol edilebilir hale getiriyor (30dk batch)
2. ✅ Performance index'i otomatik hale getiriyor (00:15 auto-build)
3. ✅ Backfill mekanizması ekliyor (UI butonu)
4. ✅ Kullanılmayan kod'u temizliyor
5. ✅ Sistem güvenilirliğini artırıyor (idempotency, fault tolerance)

**Toplam tahmini süre**: 16-20 saat (2-3 hafta)
**Kaynak gereksinimleri**: ~40% azalma
**Kullanıcı deneyimi**: Önemli iyileşme (backfill + otomatik index)
