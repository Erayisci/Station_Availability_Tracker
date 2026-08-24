# EV Charging Analytics Platform

Türkiye'deki ZES elektrikli araç şarj istasyonu ağını gerçek zamanlı olarak izleyen, performans-utilization analitiği çıkaran ve operasyonel/stratejik kararlara yönelik tahminler üreten uçtan uca bir platform.

Sistem üç katmandan oluşur: **canlı veri toplama** (Spring Boot + H2), **analitik depolama ve sorgulama** (Elasticsearch), ve **ML pipeline'ı** (Python / FastAPI). Bir React tabanlı dashboard tüm bu katmanları tek bir operatör arayüzü altında birleştirir.

---

## Platformun Kapsamı

- **Canlı durum takibi** — ZES public API'sinden 5 dakikada bir polling; konnektör/istasyon durum geçişleri zaman damgalı olarak H2'ye yazılır (7 günlük retention).
- **Günlük finalizasyon** — Her gece 23:59'da açık slot'lar kapatılır ve Elasticsearch index zinciri (`zes_stations-{date}` → `zes_connector_transform-{date}` → `zes_station_performance-{date}`) yeniden inşa edilir.
- **İstasyon performans analitiği** — Charging utilization, peak/off-peak ayrımı, 1 km yarıçaptaki istasyon yoğunluğu (ZES nearby API'si), effective supply ve normalleştirilmiş öncelik skoru.
- **1-gün ileri utilization tahmini** — Python tarafında eğitilen ağaç tabanlı bir model (gradient boosted), `zes_station_features-{date}` snapshot'ı + son utilization sinyali ile hedef gün için her istasyona tahmin üretir. Sonuçlar `zes_station_forecast-{date}` index'inde tutulur.
- **Forecast backtest** — Tahmin günü için gerçek `charging_utilization_pct` veriyle eşleştirilir; MAE, hata dağılımı ve gap-day kırılımları raporlanır.
- **Dinamik fiyatlandırma (EPDK indirim modeli)** — Saatlik talep eğrileri ve varsayılan elastisite üzerinden EPDK üst limitine göre indirim bandları hesaplanır; PAR (peak-to-average ratio) düzleştirilir.
- **Opportunity / büyüme potansiyeli skoru** — Talep sinyalleri (utilization, peakiness) + arz sinyalleri (yakındaki istasyon yoğunluğu) tek bir 0–100 skora yansıtılır; yeni yatırım önceliklendirmesi için.
- **Şehir bazlı analiz** — İl bazında aggregated kullanım, fiyat-utilization ilişkisi, harita görselleştirmesi (Turkey provinces GeoJSON).

---

## Mimari

```
┌──────────────────────────────────────────────────────────────────┐
│                    Polling & Status Tracking                      │
└──────────────────────────────────────────────────────────────────┘
       │
       ├─> ZES Public API                     (her 5 dakika)
       │
       ├─> H2 Database                        (authoritative — 7 gün retention)
       │   └─> ConnectorStatusHistory
       │
       ├─> Elasticsearch Index Chain          (gece finalizasyonu)
       │   ├─> zes_stations-{date}                   (snapshot)
       │   ├─> zes_connector_transform-{date}         (konnektör seviyesi)
       │   ├─> zes_station_performance-{date}         (utilization metrikleri)
       │   ├─> zes_station_features-{date}            (ML feature snapshot)
       │   ├─> zes_station_forecast-{date}            (1-gün ileri tahmin)
       │   ├─> zes_station_opportunity-{date}         (büyüme skoru)
       │   ├─> zes_station_hourly_performance-{date}  (gözlenen saatlik doluluk)
       │   └─> zes_station_hourly_forecast-{date}     (saatlik tahmin → fiyat tabanı)
       │
       ├─> Python ML Pipeline                 (FastAPI)
       │   ├─> /train      → modeli eğitir, model artefact yazar
       │   ├─> /predict    → hedef gün için forecast index yazar
       │   ├─> /backtest   → tahmin vs gerçek karşılaştırması
       │   ├─> /pricing/*  → indirim bantları üretir
       │   └─> /opportunity → istasyon başına 0-100 skor
       │
       └─> Scheduler Jobs
           ├─> 23:59 → Daily finalization
           └─> 00:15 → Auto-build performance index (önceki gün)
```

---

## Tech Stack

**Backend (core-api)**
- Java 17, Spring Boot 3.x
- Elasticsearch 8.x — analitik veri deposu
- H2 — kısa vadeli durum geçmişi
- Maven (`mvn`)

**ML Pipeline (intelligence)**
- Python 3.10
- FastAPI — pipeline servisi
- scikit-learn / LightGBM — utilization tahmini
- pandas, numpy — feature engineering
- joblib — model artefact

**Frontend (console)**
- React 18 + Vite
- Recharts — chart layer
- react-simple-maps / d3 — Türkiye il haritası
- Monokrom tasarım sistemi (IBM Plex Sans / Mono)

---

## Repository Layout

Kod, **pipeline katmanlarına** göre organize edilmiştir — sağlayıcıya göre değil. Katman modelinin tam açıklaması, hangi bileşenin tamamlandığı ve
hangisinin placeholder olduğu: **[ARCHITECTURE.md](ARCHITECTURE.md)**. Her
katmanın kodunun yanında ayrıca bir README bulunur.

```
.
├── ARCHITECTURE.md            Mimari blueprint — önce bunu okuyun
│
├── core-api/          Spring Boot backend
│   └── src/main/java/com/charging/
│       ├── kernel/            L0  ES config + IndexCatalog, JPA, admin
│       ├── ingestion/         L1  provider adapter'ları (spi/ + providers/)
│       ├── materialization/   L2  ES index zincirini üreten tek katman
│       ├── analytics/         L3  salt-okunur agregasyon
│       └── intelligence/      L4  Python'un yazdığı index'lerin okuyucusu
│
├── console/        L5  React + Vite operatör arayüzü
│   └── src/
│       ├── pages/analytics/       L3'ü tüketen sayfalar
│       ├── pages/intelligence/    L4'ü tüketen sayfalar
│       ├── components/            Paylaşılan UI (TurkeyMap, DateCalendar, ...)
│       ├── api/                   Backend client'ları
│       └── lib/pricingModel.js    Tarayıcıdaki gelir motoru
│
└── intelligence/          L4  Python ML pipeline (FastAPI)
    ├── kernel/index_catalog.py    Depolama isim kontratı (Java mirror'ı)
    ├── ingestion/                 ES okuyucuları
    ├── features/                  Feature engineering
    ├── forecast/                  Utilization tahmini
    ├── opportunity/               Büyüme potansiyeli skoru
    ├── pricing/                   EPDK tarife rejimi + indirim modeli
    └── descriptive/               🔄 Clustering / audit / anomaly (UI'sı yok)
```

**Entegrasyon kuralı:** Üç servis birbirini hiç çağırmaz; yalnızca
`{prefix}-{YYYY-MM-DD}` formatlı Elasticsearch index'leri üzerinden veri
alışverişi yapar. Index isimleri iki dosyada tanımlıdır ve birebir aynı olmak
zorundadır: `kernel/es/IndexCatalog.java` ve `kernel/index_catalog.py`. Her
aşamanın **tek bir yazarı** vardır; okuma dil sınırını serbestçe geçebilir.

> ⚠️ **Geçiş halinde:** [ADR 0001](docs/adr/0001-intelligence-storage.md), L4
> (intelligence) çıktılarını Elasticsearch'ten **Parquet lake + DuckDB**'ye
> taşımayı kabul eder; ES yalnızca arama/serving katmanı olarak kalır. Karar
> verildi, henüz uygulanmadı.

---

## Geliştirme Ortamı

### Backend

```bash
cd core-api

# Elasticsearch (single-node, yerel)
docker run -d -p 9200:9200 -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" elasticsearch:8.12.0

# Uygulamayı çalıştır
mvn spring-boot:run
```

Konfigürasyon (`application.properties`):
```properties
zes.poll.intervalMs=300000                  # 5 dk
zes.daily.cron=0 59 23 * * *                # 23:59 günlük finalizasyon
zes.history.keepDays=7                      # H2 retention
zes.performance.autoBuildCron=0 15 0 * * *  # 00:15 perf index auto-build
```

### Frontend

```bash
cd console
npm install
npm run dev
```

### ML Pipeline

```bash
cd intelligence
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt

# FastAPI servisi (port 8001)
uvicorn app:app --reload --port 8001
```

İlk kullanım sırası (tipik akış):
```bash
# 1. Feature snapshot üret
curl -X POST "http://localhost:8001/features/build?date=2026-06-15"

# 2. Forecast modelini eğit
curl -X POST "http://localhost:8001/forecast/train"

# 3. Bir gün için tahmin üret
curl -X POST "http://localhost:8001/forecast/predict?target_date=2026-06-16"
```

---

## Veri Akışı — Özet

1. **Polling** — `ZesStatusTrackerService` 5 dakikada bir durum çeker, geçişleri H2'ye yazar.
2. **Finalizasyon (23:59)** — H2'deki günlük geçmiş ES'e materyalize edilir.
3. **Performance build (00:15)** — Önceki günün `connector_transform` index'i üzerinden istasyon başına utilization metrikleri çıkarılır.
4. **Feature snapshot** — `zes_station_features-{date}` ML pipeline için statik feature'ları tutar (konum, çevredeki istasyon yoğunluğu, vb.).
5. **Forecast** — Python tarafı, en güncel perf index'i kaynak olarak alıp hedef gün için tahmin üretir; `zes_station_forecast-{date}` yazılır.
6. **Backtest** — `target_date` için gerçek perf verisi geldiğinde, forecast doc'larıyla eşleştirip hata metrikleri raporlanır.

---

## Lisans

Proprietary — internal use only.

## Katkı

Bu, dahili bir projedir. Soru ve önerileriniz için geliştirme ekibiyle iletişime geçin.
