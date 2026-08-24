# Dinamik Fiyatlandırma (`pricing/`)

ZES şarj ağı için **istasyon-bazlı, kullanıcı-elinde** indirim fiyatlandırması.
Karar bir optimizer'da değil **kullanıcıdadır**: panelde bir istasyonun geçmiş
doluluk paternine bakıp saat-bantları için indirim belirler; gelir **tarayıcıda
anlık** simüle edilir. Bu Python paketi yalnızca sayfanın okuduğu **veriyi** ve
mirroladığı **ekonomik varsayımları** üretir.

> **Tek cümlede:** Gözlenen + tahmin edilen saatlik doluluğu istasyon başına
> hazırla → kullanıcı grafikte bantları sürükleyip indirim girsin → gelir
> tarayıcıdaki talep modeliyle anlık hesaplansın (low–high bandıyla).

> ℹ️ **2026-06-25 sadeleştirmesi:** Şehir-düzeyi optimizer (`optimize.py`), şehir
> toplulaştırma (`city_predict.py`) ve politika backtest'i (`backtest.py`)
> **kaldırıldı**. Karar artık kullanıcıda; gelir motoru tarayıcıda.

---

## 1. Temel kısıt: neden sadece "indirim"?

EPDK ₺/kWh **taban fiyatını** serbest bırakır ama istasyonlar tabanın üstüne
**çıkamaz** — sadece indirim. Tüm ekonomik varsayımlar tek dosyada toplanmıştır:
[`pricing_config.py`](pricing_config.py) + [`elasticity.py`](elasticity.py).
Hiçbiri veriden öğrenilmedi (veride fiyat varyasyonu yok); hepsi politika girdisi
/ literatür önsavı. Bu varsayımlar `/pricing/config` ile frontend'e servis edilir.

---

## 2. Veri akışı (pipeline)

Hesaplanan index'ler Python'dan yazılır; Java (`ZesPricingService`) **saf reader**.

```
zes_connector_transform-{D}                 ← ham slot verisi (kaynak)
        │
        ├─[hourly_data_builder.py]→ zes_station_hourly_performance-{D}  (GÖZLENEN saatlik doluluk)
        │                                                                → grafiğin "geçmiş günler" eğrileri
        │   zes_station_forecast-{D}        ← forecast fazından günlük tahmin
        │        │
        ├─[profile.py]──────────────→ saat-şekli profilleri (mean=1)
        │        │
        └─[hourly_predict.py]───────→ zes_station_hourly_forecast-{D}   (TAHMİN: günlük × saat-şekli)
                                                                         → grafiğin "tahmin" eğrisi + gelir tabanı

[demand_model.py + elasticity.py]  → ekonomik spec (JS portu + /pricing/config ile aynalanır)
```

Karar birimi artık **(istasyon, segment)**; toplulaştırma yok.

---

## 3. Modüller

| Dosya | Görevi | Üretim |
|---|---|---|
| [`hourly_data_builder.py`](hourly_data_builder.py) | Ham slot'lardan **gözlenen** saatlik doluluk | `zes_station_hourly_performance` |
| [`profile.py`](profile.py) | Geçmişten saat-şekli profilleri (mean=1) | — (bellek) |
| [`hourly_predict.py`](hourly_predict.py) | Günlük tahmini saatlik+segment kırılımına dağıt | `zes_station_hourly_forecast` |
| [`elasticity.py`](elasticity.py) | Talebin fiyat esnekliği η (segment/saat/konum) — **varsayım** | — |
| [`demand_model.py`](demand_model.py) | İndirimin doluluğa ve gelire etkisi (doygunluklu) | — |
| [`pricing_config.py`](pricing_config.py) | Taban fiyat, U_CAP, indirim kademeleri | — |
| [`test_demand_model.py`](test_demand_model.py) | Talep modelinin birim testleri | — |

`elasticity.py` + `demand_model.py` artık çalışan bir Python pipeline'ında
çağrılmaz; **kanonik spec** olarak kalırlar — `console/src/lib/pricingModel.js`
bunların birebir portudur ve gerçek gelir hesabı orada yapılır.

---

## 4. Ekonomik motor (artık tarayıcıda)

Talep modeli saf ve küçük olduğu için JS'e portlandı — kullanıcı bandı her
oynattığında sunucusuz, gecikmesiz güncelleme. Çekirdek:

```
saturate(x)   = U_CAP · (1 − e^(−x/U_CAP))     # latent → served
x0            = inv_saturate(u0)               # gözlenenden latent'i çöz
x(d)          = x0 · (1 − d)^(−η)              # indirimle latent talep
u_served(d)   = saturate(x(d))                # ⇒ u_served(0)=u0
revenue(d)    = (u_served/100)·konnektör·güç·fiyat·(1 − d)
```

**Esneklik** (η) varsayımdır; `(1−d)^(−η)` talep çarpanıdır. DC daha esnek
(η≈0.9), AC daha az (η≈0.5); tepe ×0.7, tepe-dışı ×1.3, şehir ×1.15, kırsal ×0.85.
Üç senaryo `{low, central, high}` → her gelir rakamı bir **bant**.

**Doygunluk** sayesinde `u0 → U_CAP` yaklaştıkça indirim hacim eklemez (dolu
istasyon duyarsız, boş istasyon duyarlı) ve gelir-d eğrisi iç optimuma sahip olur.

> Bu üç sabit kümesi (taban fiyat, η, çarpanlar, U_CAP) `/pricing/config` ile
> servis edilir; Java ve JS yalnızca formülü uygular — varsayım sunucu-otoriter.

---

## 5. Backend endpoint'leri (ince reader)

```
GET /pricing/config                          → ekonomik varsayımlar
GET /pricing/station-forecast?date=&stationId  → hedef tarih saatlik forecast (her segment)
GET /pricing/station-history?stationId=&days=N  → son N günlük gözlenen doluluk
GET /pricing/available-dates                 → forecast'ı olan tarihler
```

Hiçbiri hesaplama yapmaz; η/gelir frontend'dedir. Sayfa:
`console/src/pages/ZesPricingPage.jsx` (`zes-pricing` sekmesi),
motor: `.../src/lib/pricingModel.js`.

---

## 6. Çalıştırma

```bash
# (ön koşul) forecast fazı zes_station_forecast-{D} üretmiş olmalı
python -m pricing.hourly_data_builder 2026-06-24   # gözlenen geçmiş doluluk
python -m pricing.hourly_predict      2026-06-25   # saatlik+segment forecast

# FastAPI kısayolu (ikisini sırayla):
#   POST /pricing/run-all?date=2026-06-25
```

Argüman verilmezse `__main__` blokları en güncel uygun tarihi seçer.

---

## 7. Akılda tutulacak varsayımlar

- **Esneklik fit edilmedi, varsayıldı** — gelir bir simülasyondur; her zaman
  low/central/high bandıyla yorumlanmalı, tek sayıyla değil.
- **Taban fiyatlar placeholder** ([`pricing_config.py`](pricing_config.py)) —
  göreli gelir artışı % bunlara duyarsız; yalnızca örnek ₺ rakamlarını ölçekler.
- **Gözlenen doluluk zaten kapasite-sınırlı** — model `u_served(0)=u0` ile korur;
  doygunluk ve meşgul-duyarsızlığı bundan doğar.
- **Java/JS yalnızca okur/uygular** — ekonomik gerçek tek yerde (`pricing_config`
  + `elasticity`), `/pricing/config` ile aynalanır.
