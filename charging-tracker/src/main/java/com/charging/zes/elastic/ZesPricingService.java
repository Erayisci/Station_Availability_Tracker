package com.charging.zes.elastic;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dinamik fiyatlandırma (indirim) önerileri sorguları.
 * Kaynak: zes_station_pricing-{date}  (Python: pricing/optimize.py üretir)
 *
 * Dokümanlar iki seviyededir (level alanı):
 *   level="city"    — karar birimi: (şehir, segment) başına 24-saatlik indirim/util dizileri
 *   level="station" — şehrin indirim politikasını miras alan istasyon kırılımı
 *
 * Endpoints:
 *   /pricing/summary          — panel başlığı: gelir artışı (± bant), PAR düşüşü, indirim dağılımı
 *   /pricing/recommendations  — sayfalanmış (şehir, segment) öneri listesi
 *   /pricing/heatmap          — şehir × saat indirim ısı haritası (segment seçimli)
 *   /pricing/city             — harita için (şehir, segment) günlük rollup
 *   /pricing/station          — tek istasyon kırılımı
 *   /pricing/available-dates
 *
 * NOT: Gelir artışı bir SİMÜLASYONDUR (varsayılan elastikiyet altında); tek bir
 * sayı değil, low/high bandı ile yorumlanmalıdır.
 */
@Service
public class ZesPricingService {

    private static final String INDEX_PREFIX = "zes_station_pricing";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesPricingService() {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    private String index(String date) {
        return INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    private boolean indexMissing(String date) throws IOException {
        return !client.indices().exists(e -> e.index(index(date))).value();
    }

    /** Tüm city-level dokümanları çek (küçük: ~150 satır). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchCityRows(String date) throws IOException {
        var resp = client.search(s -> s
                .index(index(date))
                .size(1000)
                .query(q -> q.term(t -> t.field("level").value("city"))),
                Map.class
        );
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var hit : resp.hits().hits()) {
            if (hit.source() != null) rows.add((Map<String, Object>) hit.source());
        }
        return rows;
    }

    /**
     * Panel özeti: toplam gelir artışı (± elastikiyet bandı), PAR düşüşü, indirim dağılımı.
     */
    public Map<String, Object> summary(String date) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (indexMissing(date)) {
            result.put("available", false);
            return result;
        }
        List<Map<String, Object>> cityRows = fetchCityRows(date);
        result.put("available", true);
        result.put("n_city_segments", cityRows.size());

        double baseSum = 0, polSum = 0, wUpliftLow = 0, wUpliftHigh = 0, wBase = 0;
        double parBaseSum = 0, parPolSum = 0;
        double discWeighted = 0, connSum = 0;
        Map<String, Long> segCounts = new LinkedHashMap<>();
        Map<String, Long> tierHist = new LinkedHashMap<>();  // discount tier histogram over city-hours

        for (Map<String, Object> r : cityRows) {
            double rb = num(r.get("baseline_revenue"));
            double rp = num(r.get("predicted_revenue"));
            baseSum += rb;
            polSum += rp;
            wUpliftLow  += num(r.get("revenue_uplift_pct_low"))  * rb;
            wUpliftHigh += num(r.get("revenue_uplift_pct_high")) * rb;
            wBase += rb;

            double conn = num(r.get("connector_count"));
            connSum += conn;
            discWeighted += num(r.get("mean_discount")) * conn;

            segCounts.merge(str(r.get("segment")), 1L, Long::sum);

            parBaseSum += par(numList(r.get("baseline_utilization_pct")));
            parPolSum  += par(numList(r.get("predicted_utilization_pct")));

            for (Double d : numList(r.get("recommended_discount"))) {
                tierHist.merge(String.format("%.0f%%", d * 100.0), 1L, Long::sum);
            }
        }

        double upliftCentral = baseSum > 0 ? (polSum - baseSum) / baseSum * 100.0 : 0.0;
        Map<String, Object> uplift = new LinkedHashMap<>();
        uplift.put("central", round2(upliftCentral));
        uplift.put("low",  round2(wBase > 0 ? wUpliftLow  / wBase : 0.0));   // revenue-weighted band
        uplift.put("high", round2(wBase > 0 ? wUpliftHigh / wBase : 0.0));
        result.put("revenue_uplift_pct", uplift);

        int n = Math.max(cityRows.size(), 1);
        result.put("par_mean_baseline", round2(parBaseSum / n));
        result.put("par_mean_policy",   round2(parPolSum / n));
        result.put("par_reduction",     round2((parBaseSum - parPolSum) / n));
        result.put("mean_discount",     round2(connSum > 0 ? discWeighted / connSum : 0.0));
        result.put("segment_counts",    segCounts);
        result.put("discount_histogram", tierHist);
        result.put("is_simulation", true);
        return result;
    }

    /**
     * Sayfalanmış (şehir, segment) öneri listesi.
     * sort: "uplift" (gelir artışı) | "discount" (ortalama indirim) | "par"
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> recommendations(
            String date, Integer from, Integer size,
            String city, String segment, String sort, String order) throws IOException {
        int fr = from != null ? from : 0;
        int sz = size != null ? size : 20;
        SortOrder so = "asc".equalsIgnoreCase(order) ? SortOrder.Asc : SortOrder.Desc;
        String sortField = switch (sort == null ? "" : sort) {
            case "discount" -> "mean_discount";
            case "par"      -> "var_baseline";
            default          -> "revenue_uplift_pct";
        };

        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("level").value("city"))));
        if (city != null && !city.isBlank()) {
            filters.add(Query.of(q -> q.term(t -> t.field("city_name").value(city))));
        }
        if (segment != null && !segment.isBlank()) {
            filters.add(Query.of(q -> q.term(t -> t.field("segment").value(segment.toUpperCase()))));
        }

        var resp = client.search(s -> s
                .index(index(date))
                .from(fr).size(sz)
                .trackTotalHits(t -> t.enabled(true))
                .query(q -> q.bool(b -> b.filter(filters)))
                .sort(srt -> srt.field(f -> f.field(sortField).order(so))),
                Map.class
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var hit : resp.hits().hits()) {
            if (hit.source() != null) rows.add((Map<String, Object>) hit.source());
        }
        long total = resp.hits().total() != null ? resp.hits().total().value() : 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("from", fr);
        result.put("size", sz);
        result.put("rows", rows);
        return result;
    }

    /**
     * Şehir × saat indirim ısı haritası. segment verilirse o segment, yoksa "DC".
     * Dönen: [{ city_name, segment, recommended_discount:[24], baseline_utilization_pct:[24],
     *           predicted_utilization_pct:[24], revenue_uplift_pct }]
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> heatmap(String date, String segment) throws IOException {
        String seg = (segment != null && !segment.isBlank()) ? segment.toUpperCase() : "DC";
        var resp = client.search(s -> s
                .index(index(date))
                .size(1000)
                .source(src -> src.filter(f -> f.includes(List.of(
                        "city_name", "segment", "recommended_discount",
                        "baseline_utilization_pct", "predicted_utilization_pct",
                        "revenue_uplift_pct", "mean_discount", "connector_count", "n_stations"))))
                .query(q -> q.bool(b -> b
                        .filter(Query.of(f -> f.term(t -> t.field("level").value("city"))))
                        .filter(Query.of(f -> f.term(t -> t.field("segment").value(seg))))))
                .sort(srt -> srt.field(f -> f.field("mean_discount").order(SortOrder.Desc))),
                Map.class
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (var hit : resp.hits().hits()) {
            if (hit.source() != null) out.add((Map<String, Object>) hit.source());
        }
        return out;
    }

    /** Harita rollup için tüm city-level satırlar (her iki segment). */
    public List<Map<String, Object>> city(String date) throws IOException {
        if (indexMissing(date)) return List.of();
        return fetchCityRows(date);
    }

    /** Tek istasyon kırılımı (her iki segment dokümanı). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> station(String date, String stationId) throws IOException {
        var resp = client.search(s -> s
                .index(index(date))
                .size(4)
                .query(q -> q.bool(b -> b
                        .filter(Query.of(f -> f.term(t -> t.field("level").value("station"))))
                        .filter(Query.of(f -> f.term(t -> t.field("station_id").value(stationId)))))),
                Map.class
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (var hit : resp.hits().hits()) {
            if (hit.source() != null) out.add((Map<String, Object>) hit.source());
        }
        return out;
    }

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

    // ── helpers ──
    private static double par(List<Double> profile) {
        if (profile == null || profile.isEmpty()) return 1.0;
        double sum = 0, max = 0;
        for (Double v : profile) {
            double x = v == null ? 0 : v;
            sum += x;
            if (x > max) max = x;
        }
        double mean = sum / profile.size();
        return mean > 1e-9 ? max / mean : 1.0;
    }

    private static List<Double> numList(Object o) {
        List<Double> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object v : list) out.add(v instanceof Number n ? n.doubleValue() : 0.0);
        }
        return out;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
