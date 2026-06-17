# EV Charging Station Tracker & Analytics Platform

Türkiye'deki ZES ve ASTOR elektrikli araç şarj istasyonu ağlarını gerçek zamanlı olarak izleyen, performans-utilization analitiği çıkaran ve operasyonel/stratejik kararlara yönelik tahminler üreten uçtan uca bir platform.

Sistem üç katmandan oluşur: **canlı veri toplama** (Spring Boot + H2), **analitik depolama ve sorgulama** (Elasticsearch), ve **ML pipeline'ı** (Python / FastAPI). Bir React tabanlı dashboard tüm bu katmanları tek bir operatör arayüzü altında birleştirir.

---

## Platformun Kapsamı

- **Canlı durum takibi** — ZES & ASTOR public API'lerinden 5 dakikada bir polling; konnektör/istasyon durum geçişleri zaman damgalı olarak H2'ye yazılır (7 günlük retention).
- **Günlük finalizasyon** — Her gece 23:59'da açık slot'lar kapatılır ve Elasticsearch index zinciri (`{provider}_stations-{date}` → `zes_connector_transform-{date}` → `zes_station_performance-{date}`) yeniden inşa edilir.
- **İstasyon performans analitiği** — Charging utilization, peak/off-peak ayrımı, çevredeki rakip istasyon yoğunluğu, effective supply ve normalleştirilmiş öncelik skoru.
- **1-gün ileri utilization tahmini** — Python tarafında eğitilen ağaç tabanlı bir model (gradient boosted), `zes_station_features-{date}` snapshot'ı + son utilization sinyali ile hedef gün için her istasyona tahmin üretir. Sonuçlar `zes_station_forecast-{date}` index'inde tutulur.
- **Forecast backtest** — Tahmin günü için gerçek `charging_utilization_pct` veriyle eşleştirilir; MAE, hata dağılımı ve gap-day kırılımları raporlanır.
- **Dinamik fiyatlandırma (EPDK indirim modeli)** — Saatlik talep eğrileri ve varsayılan elastisite üzerinden EPDK üst limitine göre indirim bandları hesaplanır; PAR (peak-to-average ratio) düzleştirilir.
- **Opportunity / büyüme potansiyeli skoru** — Talep sinyalleri (utilization, peakiness) + arz sinyalleri (yakındaki istasyon yoğunluğu) tek bir 0–100 skora yansıtılır; yeni yatırım önceliklendirmesi için.
- **Şehir bazlı analiz** — İl bazında aggregated kullanım, fiyat-utilization ilişkisi, harita görselleştirmesi (Turkey provinces GeoJSON).
- **EPDK entegrasyonu** — EPDK kamuya açık şarj noktası listesi ile ZES envanteri karşılaştırılır; kapsama eksikleri raporlanır.

---

## Mimari

```
┌──────────────────────────────────────────────────────────────────┐
│                    Polling & Status Tracking                      │
└──────────────────────────────────────────────────────────────────┘
       │
       ├─> ZES / ASTOR Public APIs            (her 5 dakika)
       │
       ├─> H2 Database                        (authoritative — 7 gün retention)
       │   └─> ConnectorStatusHistory / AstorStationStatusHistory
       │
       ├─> Elasticsearch Index Chain          (gece finalizasyonu)
       │   ├─> {provider}_stations-{date}             (snapshot)
       │   ├─> zes_connector_transform-{date}         (konnektör seviyesi)
       │   ├─> zes_station_performance-{date}         (utilization metrikleri)
       │   ├─> zes_station_features-{date}            (ML feature snapshot)
       │   ├─> zes_station_forecast-{date}            (1-gün ileri tahmin)
       │   ├─> zes_station_opportunity-{date}         (büyüme skoru)
       │   ├─> zes_pricing_hourly-{date}              (saatlik fiyat indirim)
       │   └─> epdk_connector_snapshot-{date}         (EPDK karşılaştırma)
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

**Backend (charging-tracker)**
- Java 17, Spring Boot 3.x
- Elasticsearch 8.x — analitik veri deposu
- H2 — kısa vadeli durum geçmişi
- Maven (`./mvnw`)

**ML Pipeline (model_training)**
- Python 3.10
- FastAPI — pipeline servisi
- scikit-learn / LightGBM — utilization tahmini
- pandas, numpy — feature engineering
- joblib — model artefact

**Frontend (charging-dashboard)**
- React 18 + Vite
- Recharts — chart layer
- react-simple-maps / d3 — Türkiye il haritası
- Monokrom tasarım sistemi (IBM Plex Sans / Mono)

---

## Repository Layout

```
.
├── charging-tracker/          Spring Boot backend (REST + ES + scheduler)
│   └── src/main/java/com/charging/
│       ├── zes/               ZES tarafı: controller'lar + ES servisleri
│       └── astor/             ASTOR tarafı
│
├── charging-dashboard/        React + Vite operatör arayüzü
│   └── src/
│       ├── pages/             Her bir feature için sayfa
│       ├── components/        Paylaşılan UI (TurkeyMap, DateCalendar, ...)
│       └── api/zesApi.js      Backend client
│
├── model_training/            Python ML pipeline (FastAPI)
│   ├── app.py                 Servis girişi
│   ├── extract.py / features.py / train.py
│   ├── forecast/              Utilization tahmini
│   ├── pricing/               EPDK indirim modeli
│   └── opportunity.py         Büyüme potansiyeli skoru
│
└── scripts/                   Stand-alone Python ingestion scriptleri (EPDK)
```

---

## Geliştirme Ortamı

### Backend

```bash
cd charging-tracker

# Elasticsearch (single-node, yerel)
docker run -d -p 9200:9200 -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" elasticsearch:8.12.0

# Uygulamayı çalıştır
./mvnw spring-boot:run
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
cd charging-dashboard
npm install
npm run dev
```

### ML Pipeline

```bash
cd model_training
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

1. **Polling** — `ZesPoller` / `AstorPoller` 5 dakikada bir durum çeker, geçişleri H2'ye yazar.
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
