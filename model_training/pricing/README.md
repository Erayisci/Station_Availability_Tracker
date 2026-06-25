# Dinamik Fiyatlandırma (`pricing/`)

ZES şarj ağı için **indirim-tabanlı dinamik fiyatlandırma** motoru. Amaç: her
istasyonun her saati için, EPDK kurallarına uyan bir **indirim kademesi** önermek
ve bunu hem **geliri artıracak** hem de **günün talebini daha düz dağıtacak**
şekilde seçmek.

> **Tek cümlede:** Gözlemlenen doluluk → saat/şehir kırılımına indir → talebin
> indirime tepkisini modelle → geliri maksimize eden indirimi seç → sonucu
> sapma bandıyla birlikte raporla.

---

## 1. Temel kısıt: neden sadece "indirim"?

EPDK, ₺/kWh **taban fiyatını** serbest bırakıyor ama istasyonlar bu tabanın
**üstüne çıkamıyor** — sadece indirim yapabiliyor. Dolayısıyla kararımız tek
yönlü ve sınırlı:

```
d ∈ {0, 0.05, 0.10, 0.15, 0.20}      (d = indirim oranı, D_MAX = %20)
```

Bu kademeler ve tüm ekonomik varsayımlar tek bir dosyada toplanmıştır:
[`pricing_config.py`](pricing_config.py) — **paketin kavramsal olarak en riskli
dosyası**, çünkü hiçbiri veriden öğrenilmedi (veride fiyat değişimi yok), hepsi
politika girdisi / literatür önsavı. Değiştirince `PRICING_VERSION` artırılır.

---

## 2. Veri akışı (pipeline)

Her şey Elasticsearch index'leri üzerinden akar. **Tüm hesaplanan index'ler
Python tarafından yazılır; Java (`ZesPricingService`) sadece okur.** Sıra:

```
zes_connector_transform-{D}              ← ham slot verisi (kaynak)
        │
        ├─[hourly_data_builder.py]→ zes_station_hourly_performance-{D}   (GÖZLENEN saatlik doluluk)
        │
        │   (ayrı koldan: günlük tahmin)
        │   zes_station_forecast-{D}     ← forecast/ fazından gelen günlük tahmin
        │        │
        ├─[profile.py]──────────────→ saat-şekli profilleri (mean=1)
        │        │
        ├─[hourly_predict.py]───────→ zes_station_hourly_forecast-{D}    (TAHMİN: günlük × saat-şekli)
        │        │
        ├─[city_predict.py]─────────→ zes_city_forecast-{D}             (şehir×segment'e toplulaştır)
        │        │
        └─[optimize.py]─────────────→ zes_station_pricing-{D}           (KARAR: saatlik indirimler)

[backtest.py] → reports/{D}/pricing_backtest.{json,md,png}             (simülasyon doğrulaması)
```

Karar birimi **(şehir, segment, saat)** hücresidir. ~150 şehir-segment × 24 saat
ölçeğinde çalışır; istasyonlar şehirlerinin indirimini "miras alır" (drill-down).

---

## 3. Modül modül

| Dosya | Görevi | Üretim |
|---|---|---|
| [`pricing_config.py`](pricing_config.py) | Tüm varsayımlar: taban fiyat, indirim kademeleri, kapasite tavanı, meşgul eşiği, γ | — |
| [`elasticity.py`](elasticity.py) | Talebin fiyat esnekliği η (segment/saat/konuma göre) — **varsayım** | — |
| [`demand_model.py`](demand_model.py) | İndirimin doluluğa ve gelire etkisi (doygunluklu model) | — |
| [`hourly_data_builder.py`](hourly_data_builder.py) | Ham slot'lardan **gözlenen** saatlik doluluk | `zes_station_hourly_performance` |
| [`profile.py`](profile.py) | Geçmişten saat-şekli profilleri (mean=1) | — (bellek) |
| [`hourly_predict.py`](hourly_predict.py) | Günlük tahmini saatlik+segment kırılımına dağıt | `zes_station_hourly_forecast` |
| [`city_predict.py`](city_predict.py) | İstasyonları şehir×segment'e toplulaştır | `zes_city_forecast` |
| [`optimize.py`](optimize.py) | İndirim politikasını çöz + yaz | `zes_station_pricing` |
| [`backtest.py`](backtest.py) | Politikayı simüle et, gelir/düzlük bandını raporla | `reports/{D}/...` |
| [`test_demand_model.py`](test_demand_model.py) | Talep modelinin birim testleri | — |

---

## 4. Ekonomik modelin kalbi

İki soru var: **(a)** indirim talebi ne kadar artırır, **(b)** artan hacim
fiyat erozyonunu telafi eder mi?

### (a) Esneklik — `elasticity.py`

η, "fiyatı %1 düşürünce talep yüzde kaç artar"ın büyüklüğü. Veride fiyat
oynaması olmadığından **fit edilemez**, varsayılır:

```
talep çarpanı = (1 - d) ** (-η)
```

| Segment | η (central) | Mantık |
|---|---|---|
| DC (hızlı) | 0.9 [0.6–1.2] | Rota seçimi, zaman hassas → daha esnek |
| AC (yavaş) | 0.5 [0.3–0.7] | Park süresine bağlı, mahkûm → daha az esnek |

Çarpanlar: tepe saat ×0.7 (ihtiyaç odaklı, az esnek), tepe-dışı ×1.3, şehir
×1.15 (ikame bol), kırsal ×0.85. Her şey `[0.1, 2.0]` aralığına kırpılır.

### (b) Doygunluklu talep modeli — `demand_model.py`

Gözlenen `u0` doluluğu zaten **kapasiteyle sınırlı (served)** bir değer. Model
`u_served(d=0) == u0` eşitliğini tam tutmak zorunda. Bunun için doygunluk
bağını tersine çevirip gizli (latent) talebi geri çıkarır, indirimle ölçekler,
yeniden doygunluğa sokar:

```
saturate(x)      = U_CAP · (1 − e^(−x/U_CAP))        # latent → served
x0               = inv_saturate(u0)                  # gözlenenden latent'i çöz
x(d)             = x0 · (1 − d)^(−η)                 # indirimle latent talep
u_served(d)      = saturate(x(d))                    # ⇒ u_served(0)=u0 ✓
revenue(d)       = (u_served/100)·konnektör·güç·fiyat·(1 − d)
```

**Neden doygunluk?** `u0 → U_CAP` (kapasite) yaklaştıkça `du_served/dd → 0` olur:
dolu istasyona indirim hacim eklemez. Bu sayede **boş hücreler indirime
duyarlı, dolu hücreler duyarsız** olur ve gelir eğrisi köşe yerine **iç
optimuma** sahip olabilir. `(1 − d)` çarpanı fiyat erozyonu; hacim kazancı
`u_served` içinde — çarpımları d'ye göre tek-tepeli.

---

## 5. Optimizasyon — `optimize.py`

### Amaç fonksiyonu

```
maksimize:  Σ_hücre  revenue(d)  +  γ · Σ_(şehir,segment) (PAR_baz − PAR_politika)
```

- **PAR** = `max_saat(doluluk) / ortalama_saat(doluluk)` — günlük profilin
  tepe/ortalama oranı. 1.0 = dümdüz. PAR düşerse profil **düzleşmiş** demektir.
- **Neden varyans değil PAR?** İndirim sadece talep *ekleyebilir*, tepeyi
  *törpüleyemez*. Tepe saatler az esnek (×0.7) + dolu hücreler `d=0`'a
  sabitlendiği için gelir-optimal indirim doğal olarak **tepe-dışı çukurları**
  doldurur → ortalama yükselir, tepe sabit kalır → profil düzleşir. Bu
  "dürüst" ve ulaşılabilir düzlük ölçüsüdür. **`γ=0` ⇒ saf gelir** (varsayılan).

### Kısıtlar
1. `d ∈ DISCOUNT_TIERS` (ayrık kademeler)
2. **Meşgul koruması:** `u0 ≥ BUSY_THRESHOLD (%80)` olan hücre `d=0`'a sabit —
   dolu istasyona asla indirim yok.
3. Komşu saatler arası **yumuşaklık cezası** (`SMOOTHNESS_PENALTY·(Δd)²`).

### Yöntem
1. **Greedy tohum** ([`optimize.py:175`](optimize.py#L175)): her hücreye, tek
   başına en yüksek gelirli kademe.
2. **Koordinat-yükselişi taramaları** ([`optimize.py:183`](optimize.py#L183)):
   her hücreyi tek tek dolaşıp, PAR ve yumuşaklık etkisini de hesaba katarak
   en iyi kademeyi seçer; değişiklik durana kadar (en çok `sweeps` tur) tekrarlar.

Politika **central η** altında karar verilir; gelir sonra **low/central/high**
ile yeniden değerlendirilip **sapma bandı** üretilir.

---

## 6. Backtest — `backtest.py`

> ⚠️ **Bu bir ölçüm değil, simülasyondur.** İndirimli karşı-olgu (counterfactual)
> veride hiç gözlenmedi; gerçek dünyada hiçbir indirim uygulanmadı.

Backtest yalnızca **iç tutarlılığı**, **kapasite güvenliğini** (served hiç
`U_CAP`'i aşmamalı) ve **gelir↔düzlük dengesini** doğrular; varsayılan
esnekliğin doğruluğunu **kanıtlamaz**. Bu yüzden manşet her zaman tek sayı değil
**low–high bandıdır**. *low* senaryosunun negatif çıkması = "talep
sandığımızdan az esnekse, central politikası geliri hafif aşındırır" — bandın
açığa çıkardığı asıl risk budur.

---

## 7. Neden indirimlerin çoğu %0 veya %20'de toplanıyor? (SSS)

Histogramda ara kademeler (5/10/15) neredeyse hiç seçilmez; bu **beklenen** bir
durumdur, hata değil. `γ=0` altında optimizer her hücreye en yüksek gelirli
kademeyi verir. Doygunluktan **uzak** hücrelerde (çoğu hücre) gelir şuna
yaklaşır:

```
revenue(d) ∝ (1 − d)^(1 − η)        →  d'ye göre MONOTONİK
```

- **η < 1** → gelir d ile düşer → optimum **%0**
- **η > 1** → gelir d ile artar → optimum **%20 (D_MAX)**

Yani optimum bir **köşede**. Ara kademe ancak hücre kapasiteye yakınken
(doygunluk eğriyi konkav yapınca) kazanır — onlar da çoğunlukla `BUSY_THRESHOLD`
ile zaten `%0`'a sabit. Pratikte: **DC tepe-dışı + şehir içi** hücreler
(η≈1.35) → %20; **AC her yer + DC tepe** (η<1) → %0. Dağılımı yumuşatmak
istersen kaldıraç optimizer'da değil, [`elasticity.py`](elasticity.py)'deki
DC tepe-dışı esnekliğindedir.

---

## 8. Çalıştırma

Sırayı bozmadan (her adım bir öncekinin index'ini okur):

```bash
# 0) (ön koşul) forecast fazı zes_station_forecast-{D} üretmiş olmalı
python -m pricing.hourly_data_builder 2026-06-24     # gözlenen saatlik doluluk
python -m pricing.hourly_predict      2026-06-25     # saatlik+segment tahmini
python -m pricing.city_predict        2026-06-25     # şehir×segment'e topla
python -m pricing.optimize            2026-06-25     # indirim politikası (γ arg.2 ile)
python -m pricing.optimize            2026-06-25 250 # γ=250 ile düzlük ağırlıklı

# Backtest (gözlenen günler üzerinde simülasyon + rapor)
python -m pricing.backtest                           # tüm günler, rapor yazar
python -m pricing.backtest --no-report               # sadece JSON özet
```

Tüm `__main__` blokları argüman verilmezse en güncel uygun tarihi otomatik seçer.

---

## 9. Akılda tutulacak varsayımlar

- **Esneklik fit edilmedi, varsayıldı** — veride fiyat varyasyonu yok. Her çıktı
  low/central/high bandıyla gelir; tek sayıya güvenme.
- **Taban fiyatlar placeholder** ([`pricing_config.py`](pricing_config.py)) —
  manşet KPI (göreli gelir artışı %) mutlak ₺ seviyesine duyarsız; bu değerler
  yalnızca örnek ₺ rakamlarını ölçekler.
- **Gözlenen doluluk zaten kapasite-sınırlı** — model bunu `u_served(0)=u0` ile
  korur; doygunluk iç optimumu ve meşgul-duyarsızlığı bundan doğar.
- **Java sadece okur** — bu paket tek yazar (single writer); index şemaları her
  modülün `INDEX_MAPPING`'inde tanımlı.
