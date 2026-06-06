package com.charging.zes.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * Şehir bazlı özet ve detay analizleri.
 *
 * /city/summary   → 81 il için tek istekte özet (Türkiye haritasını boyamak için)
 * /city/detail    → tek şehir için zengin detay (sağ paneldeki istatistikler için)
 */
@Service
public class ZesCityAnalysisService {

    private static final String PERF_PREFIX      = "zes_station_performance";
    private static final String CONNECTOR_PREFIX = "zes_connector_transform";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesCityAnalysisService() {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    private String perfIndex(String date) {
        return PERF_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    private String connectorIndex(String date) {
        return CONNECTOR_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    /**
     * Her şehir için özet metrikler döner. Türkiye haritası choropleth için kullanılır.
     *
     * Çıktı: {
     *   "Ankara": {
     *     "station_count": 142,
     *     "connector_count": 580,
     *     "avg_utilization_pct": 34.2,
     *     "avg_demand_supply_ratio": 28.5,
     *     "top_recommendation": "MAINTAIN",
     *     "lat": 39.9,
     *     "lng": 32.85
     *   },
     *   ...
     * }
     */
    public Map<String, Map<String, Object>> summarizeCities(String date) throws IOException {
        var response = client.search(s -> s
                .index(perfIndex(date))
                .size(0)
                .aggregations("by_city", a -> a
                        .terms(t -> t.field("city_name").size(100))
                        .aggregations("connectors_sum", aa -> aa
                                .sum(sum -> sum.field("connector_count")))
                        .aggregations("util_avg", aa -> aa
                                .avg(avg -> avg.field("charging_utilization_pct")))
                        .aggregations("ratio_avg", aa -> aa
                                .avg(avg -> avg.field("demand_supply_ratio")))
                        .aggregations("lat_avg", aa -> aa
                                .avg(avg -> avg.field("lat")))
                        .aggregations("lng_avg", aa -> aa
                                .avg(avg -> avg.field("lng")))
                        .aggregations("top_rec", aa -> aa
                                .terms(t -> t.field("recommendation_code").size(1)))
                ),
                Void.class
        );

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var bucket : response.aggregations().get("by_city").sterms().buckets().array()) {
            String city = bucket.key().stringValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("station_count",          bucket.docCount());
            row.put("connector_count",        (long) bucket.aggregations().get("connectors_sum").sum().value());
            row.put("avg_utilization_pct",    round1(bucket.aggregations().get("util_avg").avg().value()));
            row.put("avg_demand_supply_ratio",round1(bucket.aggregations().get("ratio_avg").avg().value()));
            row.put("lat",                    round4(bucket.aggregations().get("lat_avg").avg().value()));
            row.put("lng",                    round4(bucket.aggregations().get("lng_avg").avg().value()));
            var recBuckets = bucket.aggregations().get("top_rec").sterms().buckets().array();
            row.put("top_recommendation", recBuckets.isEmpty() ? null : recBuckets.get(0).key().stringValue());
            result.put(city, row);
        }
        return result;
    }

    /**
     * Tek şehir için zengin detay.
     *
     * Çıktı: {
     *   "city": "Ankara",
     *   "station_count": 142,
     *   "connector_count": 580,
     *   "avg_utilization_pct": 34.2,
     *   "avg_demand_supply_ratio": 28.5,
     *   "demand_tier_distribution":   { "HIGH": 12, "MEDIUM": 80, "LOW": 50 },
     *   "supply_tier_distribution":   { "FEW": 5, "MODERATE": 70, "MANY": 67 },
     *   "recommendation_distribution":{ "EXPAND": 8, "MAINTAIN": 90, ... },
     *   "connector_type_distribution":{ "DC_CCS": 230, "AC_Type_2": 250, ... },
     *   "top_overutilized":   [ { station_name, demand_supply_ratio, ... }, ... ],
     *   "top_underutilized":  [ { station_name, charging_utilization_pct, ... }, ... ]
     * }
     */
    public Map<String, Object> cityDetail(String date, String city) throws IOException {
        // ── 1. zes_station_performance üzerinde şehir özeti + dağılımlar ──────
        var perfResponse = client.search(s -> s
                .index(perfIndex(date))
                .size(0)
                .query(q -> q.term(t -> t.field("city_name").value(city)))
                .aggregations("station_count", a -> a.valueCount(vc -> vc.field("station_id")))
                .aggregations("connectors_sum", a -> a.sum(sum -> sum.field("connector_count")))
                .aggregations("util_avg",  a -> a.avg(avg -> avg.field("charging_utilization_pct")))
                .aggregations("ratio_avg", a -> a.avg(avg -> avg.field("demand_supply_ratio")))
                .aggregations("by_demand_tier", a -> a.terms(t -> t.field("demand_tier").size(5)))
                .aggregations("by_supply_tier", a -> a.terms(t -> t.field("supply_tier").size(5)))
                .aggregations("by_rec",         a -> a.terms(t -> t.field("recommendation_code").size(20)))
                .aggregations("top_overutilized", a -> a
                        .topHits(th -> th
                                .size(5)
                                .sort(so -> so.field(f -> f.field("demand_supply_ratio").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                                .source(src -> src.filter(f -> f.includes(List.of(
                                        "station_id","station_name","demand_supply_ratio",
                                        "charging_utilization_pct","connector_count",
                                        "demand_tier","supply_tier","recommendation_code"))))))
                .aggregations("top_underutilized", a -> a
                        .topHits(th -> th
                                .size(5)
                                .sort(so -> so.field(f -> f.field("charging_utilization_pct").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)))
                                .source(src -> src.filter(f -> f.includes(List.of(
                                        "station_id","station_name","demand_supply_ratio",
                                        "charging_utilization_pct","connector_count",
                                        "demand_tier","supply_tier","recommendation_code")))))),
                Map.class
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("station_count",          (long) perfResponse.aggregations().get("station_count").valueCount().value());
        result.put("connector_count",        (long) perfResponse.aggregations().get("connectors_sum").sum().value());
        result.put("avg_utilization_pct",    round1(perfResponse.aggregations().get("util_avg").avg().value()));
        result.put("avg_demand_supply_ratio",round1(perfResponse.aggregations().get("ratio_avg").avg().value()));
        result.put("demand_tier_distribution",      bucketsToMap(perfResponse.aggregations().get("by_demand_tier").sterms().buckets().array()));
        result.put("supply_tier_distribution",      bucketsToMap(perfResponse.aggregations().get("by_supply_tier").sterms().buckets().array()));
        result.put("recommendation_distribution",   bucketsToMap(perfResponse.aggregations().get("by_rec").sterms().buckets().array()));

        List<Map<String, Object>> topOver  = new ArrayList<>();
        for (var hit : perfResponse.aggregations().get("top_overutilized").topHits().hits().hits()) {
            topOver.add(extractSource(hit.source()));
        }
        List<Map<String, Object>> topUnder = new ArrayList<>();
        for (var hit : perfResponse.aggregations().get("top_underutilized").topHits().hits().hits()) {
            topUnder.add(extractSource(hit.source()));
        }
        result.put("top_overutilized",  topOver);
        result.put("top_underutilized", topUnder);

        // ── 2. zes_connector_transform üzerinde connector_type dağılımı ───────
        try {
            var connResponse = client.search(s -> s
                    .index(connectorIndex(date))
                    .size(0)
                    .query(q -> q.term(t -> t.field("city_name").value(city)))
                    .aggregations("by_type", a -> a.terms(t -> t.field("connector_type").size(20))),
                    Void.class
            );
            result.put("connector_type_distribution",
                    bucketsToMap(connResponse.aggregations().get("by_type").sterms().buckets().array()));
        } catch (Exception e) {
            result.put("connector_type_distribution", Collections.emptyMap());
        }

        return result;
    }

    private Map<String, Long> bucketsToMap(
            List<? extends co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket> buckets) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (var b : buckets) {
            map.put(b.key().stringValue(), b.docCount());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSource(co.elastic.clients.json.JsonData source) {
        if (source == null) return Collections.emptyMap();
        try {
            return source.to(Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
