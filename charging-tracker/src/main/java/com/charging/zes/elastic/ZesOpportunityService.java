package com.charging.zes.elastic;

import com.charging.elastic.EsConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-station growth-potential / opportunity skoru için sorgular.
 * Kaynak indeks: zes_station_opportunity-{date}  (model_training/opportunity.py üretir)
 *
 * Endpoints:
 *   /opportunity/summary       — dağılımlar, mutabakat, histogram (panel başlığı için)
 *   /opportunity/top           — en yüksek skorlu istasyonlar + nested components
 *   /opportunity/station       — tek istasyonun tam kırılımı
 *   /opportunity/scatter       — score vs demand_supply_ratio (agreement renkli)
 *   /opportunity/available-dates
 */
@Service
public class ZesOpportunityService {

    private static final String INDEX_PREFIX = "zes_station_opportunity";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesOpportunityService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    private String index(String date) {
        return INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    // ── /opportunity/summary ─────────────────────────────────────────────
    /**
     * Panel başlığı / sanity-check için tek istek:
     *   - growth_potential dağılımı
     *   - agreement dağılımı
     *   - growth × recommendation_code matrisi
     *   - score histogramı (10'luk kovalar)
     *   - skor istatistikleri (count/min/max/avg/stddev)
     */
    public Map<String, Object> summary(String date) throws IOException {
        var response = client.search(s -> s
                .index(index(date))
                .size(0)
                .aggregations("by_growth", a -> a.terms(t -> t.field("growth_potential").size(10)))
                .aggregations("by_agree",  a -> a.terms(t -> t.field("agreement").size(10)))
                .aggregations("score_stats", a -> a.extendedStats(es -> es.field("opportunity_score")))
                .aggregations("score_hist", a -> a
                        .histogram(h -> h.field("opportunity_score").interval(10.0).minDocCount(0)))
                .aggregations("by_growth_x_rec", a -> a
                        .terms(t -> t.field("growth_potential").size(10))
                        .aggregations("rec", aa -> aa
                                .terms(t -> t.field("existing_recommendation_code").size(15)))),
                Void.class
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("growth_potential_counts",
                stringBucketsToMap(response.aggregations().get("by_growth").sterms().buckets().array()));
        result.put("agreement_counts",
                stringBucketsToMap(response.aggregations().get("by_agree").sterms().buckets().array()));

        var stats = response.aggregations().get("score_stats").extendedStats();
        Map<String, Object> scoreStats = new LinkedHashMap<>();
        scoreStats.put("count", stats.count());
        scoreStats.put("min",   round2(stats.min()));
        scoreStats.put("max",   round2(stats.max()));
        scoreStats.put("avg",   round2(stats.avg()));
        scoreStats.put("stddev", round2(stats.stdDeviation()));
        result.put("score_stats", scoreStats);

        List<Map<String, Object>> hist = new ArrayList<>();
        for (var b : response.aggregations().get("score_hist").histogram().buckets().array()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.key());
            row.put("count",  b.docCount());
            hist.add(row);
        }
        result.put("score_histogram", hist);

        Map<String, Map<String, Long>> crosstab = new LinkedHashMap<>();
        for (var b : response.aggregations().get("by_growth_x_rec").sterms().buckets().array()) {
            crosstab.put(b.key().stringValue(),
                    stringBucketsToMap(b.aggregations().get("rec").sterms().buckets().array()));
        }
        result.put("crosstab_growth_vs_recommendation", crosstab);

        return result;
    }

    // ── /opportunity/top ─────────────────────────────────────────────────
    /**
     * En yüksek skorlu istasyonlar. components nested alanı ile birlikte
     * tam doküman döner; UI bileşen kırılım çubuğunu bu listeden çizer.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> top(String date, Integer size, String city, String growth, String order) throws IOException {
        int sz = size != null ? size : 20;
        SortOrder sortOrder = "asc".equalsIgnoreCase(order) ? SortOrder.Asc : SortOrder.Desc;
        List<Query> filters = new ArrayList<>();
        if (city != null && !city.isBlank()) {
            filters.add(Query.of(q -> q.term(t -> t.field("city_name").value(city))));
        }
        if (growth != null && !growth.isBlank()) {
            filters.add(Query.of(q -> q.term(t -> t.field("growth_potential").value(growth))));
        }

        var response = client.search(s -> s
                .index(index(date))
                .size(sz)
                .query(q -> filters.isEmpty()
                        ? q.matchAll(m -> m)
                        : q.bool(b -> b.filter(filters)))
                .sort(so -> so.field(f -> f.field("opportunity_score").order(sortOrder))),
                Map.class
        );

        List<Map<String, Object>> out = new ArrayList<>();
        for (var hit : response.hits().hits()) {
            if (hit.source() != null) out.add((Map<String, Object>) hit.source());
        }
        return out;
    }

    // ── /opportunity/station ─────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public Map<String, Object> station(String date, String stationId) throws IOException {
        var response = client.search(s -> s
                .index(index(date))
                .size(1)
                .query(q -> q.term(t -> t.field("station_id").value(stationId))),
                Map.class
        );
        if (response.hits().hits().isEmpty()) return Map.of();
        return (Map<String, Object>) response.hits().hits().get(0).source();
    }

    // ── /opportunity/scatter ─────────────────────────────────────────────
    /**
     * Score vs existing_demand_supply_ratio scatter — agreement renkli.
     * Tüm istasyonları dönmemek için makul bir tavan (max 2000).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> scatter(String date, Integer size) throws IOException {
        int sz = Math.min(size != null ? size : 2000, 2000);
        var response = client.search(s -> s
                .index(index(date))
                .size(sz)
                .source(src -> src.filter(f -> f.includes(Arrays.asList(
                        "station_id", "station_name", "city_name",
                        "opportunity_score", "existing_demand_supply_ratio",
                        "agreement", "growth_potential",
                        "existing_recommendation_code"))))
                .query(q -> q.matchAll(m -> m)),
                Map.class
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (var hit : response.hits().hits()) {
            if (hit.source() != null) out.add((Map<String, Object>) hit.source());
        }
        return out;
    }

    // ── /opportunity/available-dates ────────────────────────────────────
    public List<String> availableDates() throws IOException {
        var response = client.cat().indices(c -> c.index(INDEX_PREFIX + "-*"));
        List<String> dates = new ArrayList<>();
        for (var rec : response.valueBody()) {
            String idx = rec.index();
            if (idx != null && idx.startsWith(INDEX_PREFIX + "-")) {
                dates.add(idx.substring(INDEX_PREFIX.length() + 1));
            }
        }
        dates.sort((a, b) -> b.compareTo(a));
        return dates;
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private Map<String, Long> stringBucketsToMap(
            List<? extends co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket> buckets) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (var b : buckets) map.put(b.key().stringValue(), b.docCount());
        return map;
    }

    private double round2(Double v) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
