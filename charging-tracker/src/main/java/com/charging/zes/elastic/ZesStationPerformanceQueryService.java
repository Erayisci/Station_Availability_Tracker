package com.charging.zes.elastic;

import com.charging.elastic.EsConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * zes_station_performance-{date} index'i üzerinde sorgular.
 *
 * Ana kullanım senaryoları:
 *  1. overutilized()  — yüksek talep + düşük arz (genişleme önceliği)
 *  2. underutilized() — düşük talep + yüksek arz (gözden geçirme/taşıma)
 *  3. search()        — esnek filtre + sıralama
 */
@Service
public class ZesStationPerformanceQueryService {

    private static final String INDEX_PREFIX = "zes_station_performance";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesStationPerformanceQueryService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }

    private String indexForDate(String date) {
        return INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    // ── Yüksek talep + düşük arz (EXPAND adayları) ─────────────────────────

    /**
     * demand_supply_ratio'ya göre descending sıralı, HIGH demand + FEW supply öncelikli.
     * "Bu bölgelere yeni istasyon inşa edilmeli" listesi.
     */
    public List<Map<String, Object>> overutilized(String date, int size) throws IOException {
        return search(date, "HIGH", "FEW", null, null, null,
                null, null, "demand_supply_ratio", "desc", 0, size);
    }

    // ── Düşük talep + yüksek arz (RELOCATE adayları) ───────────────────────

    /**
     * charging_utilization_pct'ye göre ascending sıralı, LOW demand + MANY supply öncelikli.
     * "Strateji gözden geçirilmeli / taşınmalı" listesi.
     */
    public List<Map<String, Object>> underutilized(String date, int size) throws IOException {
        return search(date, "LOW", "MANY", null, null, null,
                null, null, "charging_utilization_pct", "asc", 0, size);
    }

    // ── Esnek arama ─────────────────────────────────────────────────────────

    /**
     * @param date              Analiz tarihi (null = bugün)
     * @param demandTier        HIGH / MEDIUM / LOW (null = tümü)
     * @param supplyTier        FEW / MODERATE / MANY (null = tümü)
     * @param recommendationCode EXPAND / INCREASE_CAPACITY / … (null = tümü)
     * @param cityName          Şehir filtresi (null = tümü)
     * @param minUtilizationPct Minimum charging_utilization_pct (null = yok)
     * @param maxUtilizationPct Maximum charging_utilization_pct (null = yok)
     * @param sortField         Sıralama alanı (null = demand_supply_ratio)
     * @param sortOrder         "asc" | "desc" (null = desc)
     */
    public List<Map<String, Object>> search(
            String date,
            String demandTier,
            String supplyTier,
            String recommendationCode,
            String cityName,
            String stationName,
            Double minUtilizationPct,
            Double maxUtilizationPct,
            String sortField,
            String sortOrder,
            int from,
            int size) throws IOException {

        String resolvedSort  = sortField  != null ? sortField  : "demand_supply_ratio";
        SortOrder resolvedOrder = "asc".equalsIgnoreCase(sortOrder) ? SortOrder.Asc : SortOrder.Desc;

        var response = client.search(s -> {
            s.index(indexForDate(date)).from(from).size(size);

            s.query(q -> q.bool(b -> {
                if (demandTier != null)
                    b.filter(f -> f.term(t -> t.field("demand_tier").value(demandTier)));
                if (supplyTier != null)
                    b.filter(f -> f.term(t -> t.field("supply_tier").value(supplyTier)));
                if (recommendationCode != null)
                    b.filter(f -> f.term(t -> t.field("recommendation_code").value(recommendationCode)));
                if (cityName != null)
                    b.filter(f -> f.term(t -> t.field("city_name").value(cityName)));
                if (stationName != null)
                    b.filter(f -> f.wildcard(w -> w
                            .field("station_name")
                            .wildcard("*" + stationName + "*")
                            .caseInsensitive(true)));

                if (minUtilizationPct != null || maxUtilizationPct != null) {
                    b.filter(f -> f.range(r -> {
                        r.field("charging_utilization_pct");
                        if (minUtilizationPct != null) r.gte(JsonData.of(minUtilizationPct));
                        if (maxUtilizationPct != null) r.lte(JsonData.of(maxUtilizationPct));
                        return r;
                    }));
                }
                return b;
            }));

            s.sort(so -> so.field(f -> f.field(resolvedSort).order(resolvedOrder)));
            return s;
        }, Map.class);

        return response.hits().hits().stream()
                .map(hit -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) hit.source();
                    return src;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ── Özet istatistikler ───────────────────────────────────────────────────

    /**
     * Tüm recommendation_code'ların dağılımını döner.
     * { "EXPAND": 12, "MAINTAIN": 45, … }
     */
    public Map<String, Long> recommendationDistribution(String date) throws IOException {
        var response = client.search(s -> s
                        .index(indexForDate(date))
                        .size(0)
                        .aggregations("by_rec", a -> a
                                .terms(t -> t.field("recommendation_code").size(20))),
                Void.class);

        Map<String, Long> result = new LinkedHashMap<>();
        for (var bucket : response.aggregations().get("by_rec").sterms().buckets().array()) {
            result.put(bucket.key().stringValue(), bucket.docCount());
        }
        return result;
    }

    /**
     * demand_tier × supply_tier dağılım matrisi.
     * { "HIGH": { "FEW": 5, "MODERATE": 3, … }, … }
     */
    public Map<String, Map<String, Long>> demandSupplyMatrix(String date) throws IOException {
        var response = client.search(s -> s
                        .index(indexForDate(date))
                        .size(0)
                        .aggregations("by_demand", a -> a
                                .terms(t -> t.field("demand_tier").size(5))
                                .aggregations("by_supply", aa -> aa
                                        .terms(t -> t.field("supply_tier").size(5)))),
                Void.class);

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (var demandBucket : response.aggregations().get("by_demand").sterms().buckets().array()) {
            Map<String, Long> supplyMap = new LinkedHashMap<>();
            for (var supplyBucket : demandBucket.aggregations().get("by_supply").sterms().buckets().array()) {
                supplyMap.put(supplyBucket.key().stringValue(), supplyBucket.docCount());
            }
            result.put(demandBucket.key().stringValue(), supplyMap);
        }
        return result;
    }

    public List<String> availableDates() throws IOException {
        var response = client.indices().get(r -> r.index(INDEX_PREFIX + "-*"));
        return response.result().keySet().stream()
                .map(name -> name.replace(INDEX_PREFIX + "-", ""))
                .filter(d -> d.matches("\\d{4}-\\d{2}-\\d{2}"))
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
    }
}
