package com.charging.zes.elastic;

import com.charging.elastic.EsConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * İstasyon-bazlı dinamik fiyatlandırma için VERİ servisi.
 *
 * Tasarım: fiyatlandırma kararı artık şehir-düzeyinde bir optimizer tarafından
 * verilmez. Kullanıcı, istasyonun geçmiş doluluk paternine bakıp saat-bantları
 * için indirim belirler; gelir TARAYICIDA (frontend pricingModel.js) anlık
 * hesaplanır. Bu servis sadece üç şeyi sunar:
 *
 *   /pricing/config            — ekonomik varsayımlar (taban fiyat, η, U_CAP …)
 *                                pricing_config.py + elasticity.py'nin aynası
 *   /pricing/station-forecast  — bir istasyonun hedef tarih saatlik forecast'ı
 *                                (zes_station_hourly_forecast-{date})
 *   /pricing/station-history   — bir istasyonun son N günlük GÖZLENEN doluluğu
 *                                (zes_station_hourly_performance-{date})
 *   /pricing/available-dates   — forecast'ı olan tarihler
 *
 * Saf reader; hiçbir şey hesaplamaz (η/gelir frontend'de). Şehir-düzeyi pricing
 * index'i (zes_station_pricing) ve optimizer kaldırılmıştır.
 */
@Service
public class ZesPricingService {

    private static final String FORECAST_PREFIX = "zes_station_hourly_forecast";
    private static final String PERF_PREFIX     = "zes_station_hourly_performance";

    private static final int DEFAULT_HISTORY_DAYS = 14;

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesPricingService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    // ── /pricing/config ──────────────────────────────────────────────────────
    /**
     * Ekonomik varsayımlar — frontend bunları hem gösterir hem de η/gelir
     * hesabında kullanır. pricing_config.py ve elasticity.py ile BİREBİR aynı
     * olmalı; orada değişirse burası da güncellenmeli.
     */
    public Map<String, Object> config() {
        Map<String, Object> cfg = new LinkedHashMap<>();

        Map<String, Object> basePrice = new LinkedHashMap<>();
        basePrice.put("AC", 9.99);
        basePrice.put("DC", 12.99);
        cfg.put("base_price", basePrice);

        Map<String, Object> fallbackPower = new LinkedHashMap<>();
        fallbackPower.put("AC", 22.0);
        fallbackPower.put("DC", 120.0);
        cfg.put("fallback_power_kw", fallbackPower);

        cfg.put("discount_tiers", List.of(0.0, 0.05, 0.10, 0.15, 0.20));
        cfg.put("d_max", 0.20);
        cfg.put("u_cap", 95.0);
        cfg.put("busy_threshold", 80.0);
        cfg.put("urban_nearby_threshold", 3);

        // Elasticity (assumed, never fitted) — elasticity.py ETA_BASE
        Map<String, Object> etaBase = new LinkedHashMap<>();
        etaBase.put("DC", Map.of("low", 0.6, "central", 0.9, "high", 1.2));
        etaBase.put("AC", Map.of("low", 0.3, "central", 0.5, "high", 0.7));
        cfg.put("eta_base", etaBase);

        cfg.put("peak_mult", 0.7);
        cfg.put("offpeak_mult", 1.3);
        cfg.put("urban_mult", 1.15);
        cfg.put("rural_mult", 0.85);
        cfg.put("eta_min", 0.1);
        cfg.put("eta_max", 2.0);
        // is_peak_hour fallback: 17 <= h <= 21 (station peak_hour unknown here)
        cfg.put("peak_hours", List.of(17, 18, 19, 20, 21));
        cfg.put("scenarios", List.of("low", "central", "high"));

        cfg.put("note", "Elastikiyet varsayımdır (veride fiyat varyasyonu yok). "
                + "Gelir bir simülasyondur; low/high bandı ile yorumlayın.");
        return cfg;
    }

    // ── /pricing/station-forecast ────────────────────────────────────────────
    /**
     * Bir istasyonun hedef tarih için saatlik forecast'ı (her segment için bir
     * doküman). Frontend bunu hem doluluk grafiğinin "tahmin" eğrisi hem de
     * gelir simülasyonunun u0 tabanı olarak kullanır.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> stationForecast(String date, String stationId) throws IOException {
        String idx = FORECAST_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
        if (!client.indices().exists(e -> e.index(idx)).value()) return List.of();

        var resp = client.search(s -> s
                .index(idx)
                .size(4)
                .source(src -> src.filter(f -> f.includes(List.of(
                        "station_id", "station_name", "city_name", "segment",
                        "hourly_predicted_pct", "daily_predicted_pct",
                        "connector_count", "avg_power_kw", "nearby_stations_count",
                        "lat", "lng", "profile_source", "forecast_for_date", "source_date"))))
                .query(q -> q.term(t -> t.field("station_id").value(stationId))),
                Map.class
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (var hit : resp.hits().hits()) {
            if (hit.source() != null) out.add((Map<String, Object>) hit.source());
        }
        return out;
    }

    // ── /pricing/station-history ─────────────────────────────────────────────
    /**
     * Bir istasyonun son {days} güne ait GÖZLENEN saatlik doluluğu. Kullanıcı bu
     * paternlere bakarak saat-bantlarını (tepe/tepe-dışı) konumlandırır.
     * Dönen: [{ date, segment, hourly_utilization_pct:[24] } ...] tarih DESC.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> stationHistory(String stationId, Integer days) throws IOException {
        int n = (days != null && days > 0) ? days : DEFAULT_HISTORY_DAYS;
        List<String> dates = listDatesDesc(PERF_PREFIX);
        List<Map<String, Object>> out = new ArrayList<>();

        for (String d : dates) {
            if (out.size() >= n * 2) break;  // ~2 segment per date
            String idx = PERF_PREFIX + "-" + d;
            var resp = client.search(s -> s
                    .index(idx)
                    .size(4)
                    .source(src -> src.filter(f -> f.includes(List.of(
                            "segment", "hourly_utilization_pct", "daily_utilization_pct"))))
                    .query(q -> q.term(t -> t.field("station_id").value(stationId))),
                    Map.class
            );
            for (var hit : resp.hits().hits()) {
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", d);
                row.put("segment", src.get("segment"));
                row.put("hourly_utilization_pct", src.get("hourly_utilization_pct"));
                row.put("daily_utilization_pct", src.get("daily_utilization_pct"));
                out.add(row);
            }
        }
        return out;
    }

    // ── /pricing/available-dates ─────────────────────────────────────────────
    public List<String> availableDates() throws IOException {
        return listDatesDesc(FORECAST_PREFIX);
    }

    // ── helpers ──
    private List<String> listDatesDesc(String prefix) throws IOException {
        var response = client.cat().indices(c -> c.index(prefix + "-*"));
        List<String> dates = new ArrayList<>();
        for (var rec : response.valueBody()) {
            String idx = rec.index();
            if (idx != null && idx.startsWith(prefix + "-")) {
                dates.add(idx.substring(prefix.length() + 1));
            }
        }
        dates.sort((a, b) -> b.compareTo(a));
        return dates;
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
