package com.charging.zes.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.charging.zes.client.ZesApiClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * zes_connector_transform-{date} → zes_station_performance-{date}
 *
 * Her istasyon için türetilen alanlar:
 *   connector_count            — bu istasyondaki toplam soket sayısı
 *   total_charging_minutes     — günlük toplam Charging süresi (tüm soketler)
 *   total_available_minutes    — günlük toplam Available süresi (tüm soketler)
 *   charging_utilization_pct   — total_charging_minutes / (connector_count * 1440) * 100
 *   nearby_stations_count      — verilen radius'ta API'den dönen istasyon sayısı
 *   nearby_connectors_count    — aynı istasyonlardaki toplam soket sayısı
 *   demand_supply_ratio        — charging_utilization_pct / max(nearby_connectors_count, 1)
 *                                (yüksek = talep çok, arz az → genişlet)
 *   demand_tier                — HIGH / MEDIUM / LOW
 *   supply_tier                — FEW / MODERATE / MANY
 *   recommendation_code        — EXPAND / INCREASE_CAPACITY / OPTIMIZE / …
 *   recommendation             — Türkçe açıklama
 */
@Service
public class ZesStationPerformanceIndexService {

    private static final String SOURCE_PREFIX = "zes_connector_transform";
    private static final String TARGET_PREFIX = "zes_station_performance";
    private static final String TEMPLATE_NAME = "zes_station_performance_template";
    private static final int    MINUTES_IN_DAY = 1440;

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    @Autowired
    private ZesApiClient zesApiClient;

    public ZesStationPerformanceIndexService() {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    @PostConstruct
    public void ensureTemplate() throws IOException {
        client.indices().putIndexTemplate(t -> t
                .name(TEMPLATE_NAME)
                .indexPatterns(TARGET_PREFIX + "-*")
                .template(tmpl -> tmpl
                        .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                        .mappings(m -> m
                                .properties("station_id",               p -> p.keyword(k -> k))
                                .properties("station_name",             p -> p.keyword(k -> k))
                                .properties("city_name",                p -> p.keyword(k -> k))
                                .properties("province_name",            p -> p.keyword(k -> k))
                                .properties("address",                  p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("lat",                      p -> p.float_(f -> f))
                                .properties("lng",                      p -> p.float_(f -> f))
                                .properties("location",                 p -> p.geoPoint(g -> g))
                                .properties("connector_count",          p -> p.integer(i -> i))
                                .properties("total_charging_minutes",   p -> p.float_(f -> f))
                                .properties("total_available_minutes",  p -> p.float_(f -> f))
                                .properties("charging_utilization_pct", p -> p.float_(f -> f))
                                .properties("nearby_stations_count",    p -> p.integer(i -> i))
                                .properties("nearby_connectors_count",  p -> p.integer(i -> i))
                                .properties("demand_supply_ratio",      p -> p.float_(f -> f))
                                .properties("demand_tier",              p -> p.keyword(k -> k))
                                .properties("supply_tier",              p -> p.keyword(k -> k))
                                .properties("recommendation_code",      p -> p.keyword(k -> k))
                                .properties("recommendation",           p -> p.text(tx -> tx))
                                .properties("nearby_api_error",         p -> p.keyword(k -> k))
                                .properties("analysis_date",            p -> p.date(d -> d))
                        )
                )
        );
        System.out.println("[ES] Template '" + TEMPLATE_NAME + "' upserted.");
    }

    /**
     * zes_connector_transform-{date} → zes_station_performance-{date}
     *
     * @param date   Kaynak tarih (null = bugün)
     * @param radius Yakın istasyon API yarıçapı (km)
     * @return İndexlenen istasyon sayısı
     */
    public int buildPerformanceIndex(String date, double radius) throws Exception {
        String resolvedDate = date != null ? date : LocalDate.now().toString();
        String sourceIndex  = SOURCE_PREFIX + "-" + resolvedDate;
        String targetIndex  = TARGET_PREFIX  + "-" + resolvedDate;

        long sourceCount = client.count(c -> c.index(sourceIndex)).count();
        System.out.printf("[PERF INDEX] %s (%d connector docs) → %s%n", sourceIndex, sourceCount, targetIndex);

        // Hedef index'i sıfırdan oluştur
        if (client.indices().exists(r -> r.index(targetIndex)).value()) {
            client.indices().delete(r -> r.index(targetIndex));
        }
        client.indices().create(r -> r.index(targetIndex));

        // ── 1. ES Aggregation: station bazında metrikleri topla ─────────────
        var aggResponse = client.search(s -> s
                .index(sourceIndex)
                .size(0)
                .aggregations("by_station", a -> a
                        .terms(t -> t.field("station_id").size(2000))
                        .aggregations("station_name_top", aa -> aa
                                .terms(t -> t.field("station_name").size(1)))
                        .aggregations("city_top", aa -> aa
                                .terms(t -> t.field("city_name").size(1)))
                        .aggregations("province_top", aa -> aa
                                .terms(t -> t.field("province_name").size(1)))
                        .aggregations("address_top", aa -> aa
                                .terms(t -> t.field("address.keyword").size(1)))
                        .aggregations("lat_avg", aa -> aa
                                .avg(avg -> avg.field("lat")))
                        .aggregations("lng_avg", aa -> aa
                                .avg(avg -> avg.field("lng")))
                        .aggregations("connector_count", aa -> aa
                                .valueCount(vc -> vc.field("connector_id")))
                        .aggregations("by_status", aa -> aa
                                .nested(n -> n.path("availability"))
                                .aggregations("per_status", aaa -> aaa
                                        .terms(t -> t.field("availability.status").size(10))
                                        .aggregations("total_minutes", aaaa -> aaaa
                                                .sum(sum -> sum.field("availability.duration_minutes")))
                                )
                        )
                ),
                Void.class
        );

        var stationBuckets = aggResponse.aggregations().get("by_station").sterms().buckets().array();
        System.out.printf("[PERF INDEX] Aggregated %d unique stations%n", stationBuckets.size());

        // ── 2. ES metriklerini parse et (hızlı, API call yok) ────────────────
        List<Map<String, Object>> partialDocs = new ArrayList<>(stationBuckets.size());

        for (var bucket : stationBuckets) {
            String stationId    = bucket.key().stringValue();
            String stationName  = termTop(bucket.aggregations().get("station_name_top"), stationId);
            String cityName     = termTop(bucket.aggregations().get("city_top"), "");
            String provinceName = termTop(bucket.aggregations().get("province_top"), "");
            String address      = termTop(bucket.aggregations().get("address_top"), "");
            double lat          = bucket.aggregations().get("lat_avg").avg().value();
            double lng          = bucket.aggregations().get("lng_avg").avg().value();
            long   connCount    = (long) bucket.aggregations().get("connector_count").valueCount().value();

            double chargingMins  = 0;
            double availableMins = 0;
            var nestedAgg = bucket.aggregations().get("by_status").nested();
            for (var sb : nestedAgg.aggregations().get("per_status").sterms().buckets().array()) {
                double mins = sb.aggregations().get("total_minutes").sum().value();
                switch (sb.key().stringValue()) {
                    case "Charging"  -> chargingMins  += mins;
                    case "Available" -> availableMins += mins;
                }
            }

            double capacityMins   = connCount * MINUTES_IN_DAY;
            double utilizationPct = capacityMins > 0
                    ? Math.min(100.0, (chargingMins / capacityMins) * 100.0) : 0.0;

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("station_id",               stationId);
            doc.put("station_name",             stationName);
            doc.put("city_name",                cityName);
            doc.put("province_name",            provinceName);
            doc.put("address",                  address);
            doc.put("lat",                      lat);
            doc.put("lng",                      lng);
            doc.put("location",                 Map.of("lat", lat, "lon", lng));
            doc.put("connector_count",          (int) connCount);
            doc.put("total_charging_minutes",   round1(chargingMins));
            doc.put("total_available_minutes",  round1(availableMins));
            doc.put("charging_utilization_pct", round1(utilizationPct));
            doc.put("analysis_date",            resolvedDate);
            // nearby alanları sonraki aşamada doldurulacak
            partialDocs.add(doc);
        }

        // ── 3. Nearby API call'larını paralel çalıştır ───────────────────────
        // 20 thread: 1818 istasyon / 20 ≈ 26 sn  (sequential: ~8.7 dak)
        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<CompletableFuture<Void>> futures = partialDocs.stream()
                    .map(doc -> CompletableFuture.runAsync(() -> enrichWithNearby(doc, radius), executor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        System.out.printf("[PERF INDEX] Nearby API calls completed for %d stations%n", partialDocs.size());

        // ── 4. Türetilmiş tier/tavsiye alanlarını hesapla + bulk index ───────
        int totalIndexed = 0;
        List<Map<String, Object>> batch = new ArrayList<>();

        for (Map<String, Object> doc : partialDocs) {
            double utilizationPct   = (Double) doc.get("charging_utilization_pct");
            int    nearbyStations   = (int)    doc.get("nearby_stations_count");
            int    nearbyConnectors = (int)    doc.get("nearby_connectors_count");

            double demandSupplyRatio = nearbyConnectors > 0
                    ? utilizationPct / nearbyConnectors : utilizationPct;

            String demandTier = classifyDemand(utilizationPct);
            String supplyTier = classifySupply(nearbyStations);
            String recCode    = recommendationCode(demandTier, supplyTier);

            doc.put("demand_supply_ratio",  round2(demandSupplyRatio));
            doc.put("demand_tier",          demandTier);
            doc.put("supply_tier",          supplyTier);
            doc.put("recommendation_code",  recCode);
            doc.put("recommendation",       recommendationText(recCode));

            batch.add(doc);
            if (batch.size() >= 200) {
                bulkIndex(targetIndex, batch, String.valueOf(doc.get("station_id")));
                totalIndexed += batch.size();
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            bulkIndex(targetIndex, batch, "final");
            totalIndexed += batch.size();
        }

        System.out.printf("[PERF INDEX] Done: %d station docs in %s%n", totalIndexed, targetIndex);
        return totalIndexed;
    }

    // ── Nearby API enrichment (thread-safe: her doc kendi Map'ini günceller) ─

    private void enrichWithNearby(Map<String, Object> doc, double radius) {
        double lat = (Double) doc.get("lat");
        double lng = (Double) doc.get("lng");

        int    nearbyStations   = -1;
        int    nearbyConnectors = -1;
        String apiError         = null;

        try {
            Map<String, Object> nearbyResp = zesApiClient.fetchNearbyStations(lat, lng, "", radius);
            Object dataObj = nearbyResp.get("data");
            if (dataObj instanceof List<?> list) {
                nearbyStations   = list.size();
                nearbyConnectors = list.stream()
                        .filter(st -> st instanceof Map)
                        .mapToInt(st -> {
                            Object evses = ((Map<?, ?>) st).get("evses");
                            if (!(evses instanceof List<?> evseList)) return 0;
                            return evseList.stream()
                                    .filter(ev -> ev instanceof Map)
                                    .mapToInt(ev -> {
                                        Object conns = ((Map<?, ?>) ev).get("connectors");
                                        return conns instanceof List<?> cl ? cl.size() : 0;
                                    })
                                    .sum();
                        })
                        .sum();
            }
        } catch (Exception e) {
            apiError = e.getMessage();
        }

        // Atomic değil ama her thread sadece kendi doc'unu yazıyor
        doc.put("nearby_stations_count",   nearbyStations);
        doc.put("nearby_connectors_count", nearbyConnectors);
        if (apiError != null) doc.put("nearby_api_error", apiError);
    }

    // ── Bulk index ───────────────────────────────────────────────────────────

    private void bulkIndex(String index, List<Map<String, Object>> docs, String batchLabel) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> doc : docs) {
            String id = String.valueOf(doc.get("station_id"));
            bulk.operations(op -> op.index(idx -> idx.index(index).id(id).document(doc)));
        }
        BulkResponse resp = client.bulk(bulk.build());
        if (resp.errors()) {
            System.err.printf("[PERF INDEX WARN] Batch '%s': some docs failed to index%n", batchLabel);
        }
    }

    // ── Aggregation yardımcıları ─────────────────────────────────────────────

    private String termTop(co.elastic.clients.elasticsearch._types.aggregations.Aggregate agg, String fallback) {
        try {
            var buckets = agg.sterms().buckets().array();
            return buckets.isEmpty() ? fallback : buckets.get(0).key().stringValue();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ── Tier sınıflandırması ────────────────────────────────────────────────

    private String classifyDemand(double utilizationPct) {
        if (utilizationPct >= 40) return "HIGH";
        if (utilizationPct >= 15) return "MEDIUM";
        return "LOW";
    }

    private String classifySupply(int nearbyCount) {
        if (nearbyCount < 0)  return "UNKNOWN";
        if (nearbyCount <= 2) return "FEW";
        if (nearbyCount <= 5) return "MODERATE";
        return "MANY";
    }

    // ── Tavsiye matrisi ─────────────────────────────────────────────────────

    private String recommendationCode(String demand, String supply) {
        if ("UNKNOWN".equals(supply)) return "INSUFFICIENT_DATA";
        return switch (demand) {
            case "HIGH" -> switch (supply) {
                case "FEW"      -> "EXPAND";
                case "MODERATE" -> "INCREASE_CAPACITY";
                default         -> "OPTIMIZE";
            };
            case "MEDIUM" -> switch (supply) {
                case "FEW"      -> "MONITOR_GROWTH";
                case "MODERATE" -> "MAINTAIN";
                default         -> "IMPROVE_EFFICIENCY";
            };
            default -> switch (supply) {
                case "FEW"      -> "MONITOR";
                case "MODERATE" -> "REVIEW";
                default         -> "RELOCATE";
            };
        };
    }

    private String recommendationText(String code) {
        return switch (code) {
            case "EXPAND"             -> "Yüksek talep, düşük arz: Bu bölgeye yeni istasyon inşa edilmeli.";
            case "INCREASE_CAPACITY"  -> "Yüksek talep, orta arz: Mevcut istasyonlara ek soket eklenmeli.";
            case "OPTIMIZE"           -> "Yüksek talep, yüksek rekabet: Şarj hızı ve fiyat optimizasyonu yapılmalı.";
            case "MONITOR_GROWTH"     -> "Orta talep, düşük arz: Büyüme potansiyeli var, yakından izlenmeli.";
            case "MAINTAIN"           -> "Orta talep, orta arz: Mevcut durum dengeli, koruma yeterli.";
            case "IMPROVE_EFFICIENCY" -> "Orta talep, yüksek rekabet: Kullanıcı deneyimi ve verimlilik artırılmalı.";
            case "MONITOR"            -> "Düşük talep, düşük arz: Bölge gelişimine göre yeniden değerlendir.";
            case "REVIEW"             -> "Düşük talep, orta arz: Strateji gözden geçirilmeli.";
            case "RELOCATE"           -> "Düşük talep, yüksek arz: İstasyonun farklı bir bölgeye taşınması düşünülmeli.";
            default                   -> "Yakın istasyon verisi alınamadı, manuel inceleme gerekli.";
        };
    }

    // ── Yuvarlama ────────────────────────────────────────────────────────────

    private double round1(double v) { return Math.round(v * 10.0)  / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
