/**
 * Talep tepkisi + gelir motoru — `intelligence/pricing/demand_model.py` ve
 * `elasticity.py`'nin tarayıcı portu. Saf fonksiyonlar; sunucu yok. Kullanıcı
 * indirim bandlarını değiştirdikçe gelir burada ANLIK hesaplanır.
 *
 * Tüm ekonomik sabitler (taban fiyat, η tabanları, U_CAP, çarpanlar) backend'in
 * /pricing/config çıktısından (`cfg`) gelir — varsayımlar sunucu-otoriter kalır,
 * burada yalnızca formül uygulanır. Python ile birebir tutulmalıdır.
 *
 * Doygunluklu bağ (latent → served):
 *   saturate(x)     = U_CAP · (1 − e^(−x/U_CAP))
 *   inv_saturate(u) = −U_CAP · ln(1 − u/U_CAP)
 *   x0              = inv_saturate(u0)            // gözlenenden latent'i çöz
 *   x(d)            = x0 · (1 − d)^(−η)           // indirimle latent talep
 *   u_served(d)     = saturate(x(d))             // ⇒ u_served(0) = u0
 *   revenue(d)      = (u_served/100)·konnektör·güç·fiyat·(1 − d)
 */

const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));

// ── Doygunluklu bağ ────────────────────────────────────────────────────────
export function saturate(x, uCap) {
  return uCap * (1 - Math.exp(-x / uCap));
}

export function invSaturate(u, uCap) {
  const uu = clamp(u, 0, uCap - 1e-6);
  return -uCap * Math.log(1 - uu / uCap);
}

/** İndirim d altında servis edilen (kapasite-sınırlı) doluluk. u_served(0)=u0. */
export function uServed(u0, d, eta, uCap) {
  const x0 = invSaturate(u0, uCap);
  const xd = x0 * Math.pow(clamp(1 - d, 1e-9, 1), -eta);
  return saturate(xd, uCap);
}

/** Bir (istasyon, segment, saat) hücresinin geliri (₺), 1 saat için. */
export function cellRevenue(u0, d, eta, connectors, powerKw, basePrice, uCap) {
  const served = uServed(u0, d, eta, uCap);
  const energyKwh = (served / 100) * connectors * powerKw;
  return energyKwh * basePrice * (1 - d);
}

// ── Elastikiyet (elasticity.py effective_eta) ──────────────────────────────
// İstasyonun peak_hour'ı bilinmiyor → 17–21 fallback (Python tarafıyla aynı).
export function isPeakHour(hour, cfg) {
  return (cfg.peak_hours || [17, 18, 19, 20, 21]).includes(hour);
}

export function isUrban(nearbyCount, cfg) {
  if (nearbyCount == null) return false;
  return Number(nearbyCount) >= (cfg.urban_nearby_threshold ?? 3);
}

/** Bir (segment, saat, istasyon) hücresi için etkin elastikiyet büyüklüğü. */
export function effectiveEta(segment, hour, nearbyCount, scenario, cfg) {
  const base = (cfg.eta_base?.[segment] ?? cfg.eta_base?.AC)?.[scenario] ?? 0.5;
  const mPeak = isPeakHour(hour, cfg) ? (cfg.peak_mult ?? 0.7) : (cfg.offpeak_mult ?? 1.3);
  const mGeo = isUrban(nearbyCount, cfg) ? (cfg.urban_mult ?? 1.15) : (cfg.rural_mult ?? 0.85);
  const eta = base * mPeak * mGeo;
  return clamp(eta, cfg.eta_min ?? 0.1, cfg.eta_max ?? 2.0);
}

/** Bir segmentin 24 saatlik η dizisi (verilen senaryo için). */
export function etaProfile(segment, nearbyCount, scenario, cfg) {
  return Array.from({ length: 24 }, (_, h) => effectiveEta(segment, h, nearbyCount, scenario, cfg));
}

// ── Gelir simülasyonu ──────────────────────────────────────────────────────
/**
 * Bir segment-istasyon için, verilen 24-saatlik indirim dizisi `discounts[24]`
 * (kesir) altında günlük geliri üç senaryoda hesaplar ve baz (d=0) ile karşılaştırır.
 *
 * cell = { hourly: number[24], segment, connector_count, avg_power_kw, nearby_stations_count }
 * returns {
 *   baseline,                              // ₺ (d=0, elastikiyetten bağımsız)
 *   policy: { low, central, high },        // ₺ indirim altında
 *   uplift_pct: { low, central, high },    // % baz'a göre
 *   served: { low, central, high },        // her senaryo için indirim altı doluluk[24] (overlay)
 * }
 */
export function simulate(cell, discounts, cfg) {
  const seg = cell.segment;
  const conn = Number(cell.connector_count) || 0;
  const power = Number(cell.avg_power_kw) || (cfg.fallback_power_kw?.[seg] ?? 22);
  const price = cfg.base_price?.[seg] ?? cfg.base_price?.AC ?? 9.99;
  const uCap = cfg.u_cap ?? 95;
  const nearby = cell.nearby_stations_count;
  const u0 = cell.hourly || [];

  const scenarios = cfg.scenarios || ['low', 'central', 'high'];
  const etaByScn = Object.fromEntries(scenarios.map((s) => [s, etaProfile(seg, nearby, s, cfg)]));

  let baseline = 0;
  const policy = Object.fromEntries(scenarios.map((s) => [s, 0]));
  const served = Object.fromEntries(scenarios.map((s) => [s, new Array(24).fill(0)]));

  for (let h = 0; h < 24; h++) {
    const u = Number(u0[h]) || 0;
    const d = clamp(Number(discounts[h]) || 0, 0, 0.95);
    // baz: indirim yok (u_served(0)=u0, η'dan bağımsız)
    baseline += cellRevenue(u, 0, etaByScn.central[h], conn, power, price, uCap);
    for (const s of scenarios) {
      const sv = uServed(u, d, etaByScn[s][h], uCap);
      served[s][h] = sv;
      policy[s] += (sv / 100) * conn * power * price * (1 - d);
    }
  }

  const pct = (v) => (baseline > 0 ? ((v - baseline) / baseline) * 100 : 0);
  return {
    baseline,
    policy,
    uplift_pct: Object.fromEntries(scenarios.map((s) => [s, pct(policy[s])])),
    served,
  };
}

// ── Saat-bandları → 24-saatlik indirim dizisi ──────────────────────────────
/**
 * Bandlar: [{ start, end, discount }] (end dışlayıcı, gece-aşan band end<=start
 * ile sarılır). Her saate o saati içeren bandın indirimini atar; örtüşmede son
 * band kazanır. discount kesir (0.12 = %12).
 */
export function bandsToDiscounts(bands) {
  const d = new Array(24).fill(0);
  for (const b of bands || []) {
    const disc = Number(b.discount) || 0;
    for (let h = 0; h < 24; h++) {
      const inBand = b.start <= b.end
        ? h >= b.start && h < b.end
        : h >= b.start || h < b.end; // gece-aşan
      if (inBand) d[h] = disc;
    }
  }
  return d;
}
