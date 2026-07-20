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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1-gün ileri utilizasyon tahmini sorguları.
 * Kaynak: zes_station_forecast-{date}  (Python: forecast/predict.py üretir)
 *
 * Endpoints:
 *   /forecast/summary       — panel başlığı: model bilgisi + dağılım özetleri
 *   /forecast/top           — En çok yükseliş/düşüş bekleyen istasyonlar
 *   /forecast/scatter       — Bugün vs tahmin scatter (line of equality için)
 *   /forecast/station       — Tek istasyon detayı
 *   /forecast/available-dates
 */
@Service
public class ZesForecastService {

    private static final String INDEX_PREFIX = "zes_station_forecast";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesForecastService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    private String index(String date) {
        return INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    /**
     * Genel özet — UI başlığı için.
     */
    public Map<String, Object> summary(String date) throws IOException {
        var response = client.search(s -> s
                .index(index(date))
                .size(0)
                .aggregations("pred_stats", a -> a.extendedStats(es -> es.field("predicted_utilization_pct")))
                .aggregations("source_stats", a -> a.extendedStats(es -> es.field("source_utilization_pct")))
                .aggregations("delta_stats", a -> a.extendedStats(es -> es.field("delta_vs_source")))
                .aggregations("pred_hist", a -> a
                        .histogram(h -> h.field("predicted_utilization_pct").interval(10.0).minDocCount(0)))
                .aggregations("delta_hist", a -> a
                        .histogram(h -> h.field("delta_vs_source").interval(5.0).minDocCount(0))),
                Void.class
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", response.hits().total() != null ? response.hits().total().value() : 0);
        result.put("predicted_stats", statsMap(response.aggregations().get("pred_stats").extendedStats()));
        result.put("source_stats",     statsMap(response.aggregations().get("source_stats").extendedStats()));
        result.put("delta_stats",     statsMap(response.aggregations().get("delta_stats").extendedStats()));

        List<Map<String, Object>> predHist = new ArrayList<>();
        for (var b : response.aggregations().get("pred_hist").histogram().buckets().array()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.key());
            row.put("count",  b.docCount());
            predHist.add(row);
        }
        result.put("predicted_histogram", predHist);

        List<Map<String, Object>> deltaHist = new ArrayList<>();
        for (var b : response.aggregations().get("delta_hist").histogram().buckets().array()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.key());
            row.put("count",  b.docCount());
            deltaHist.add(row);
        }
        result.put("delta_histogram", deltaHist);

        return result;
    }

    /**
     * Sayfalandırılmış tahmin listesi.
     * direction = "up" → desc; "down" → asc
     * sortBy = "delta" (default) | "predicted" | "source" | "actual" | "error"
     *   - delta/predicted/source → ES-side sort
     *   - actual/error           → tüm matching docs çekilir, enrich edilir,
     *                              in-memory sort + sayfalama yapılır
     *     (actual/error olmayan satırlar her zaman sona düşer)
     * stationName: kısmi (substring) eşleşme — keyword field üzerinde wildcard
     */
    private static final int MEMORY_SORT_POOL_CAP = 5000;

    @SuppressWarnings("unchecked")
    public Map<String, Object> top(
            String date, Integer from, Integer size,
            String direction, String city, String stationName,
            String sortBy) throws IOException {
        int fr = from != null ? from : 0;
        int sz = size != null ? size : 20;
        SortOrder order = "down".equalsIgnoreCase(direction) ? SortOrder.Asc : SortOrder.Desc;
        String sb = (sortBy == null || sortBy.isBlank()) ? "delta" : sortBy.toLowerCase();

        List<Query> filters = new ArrayList<>();
        if (city != null && !city.isBlank()) {
            filters.add(Query.of(q -> q.term(t -> t.field("city_name").value(city))));
        }
        if (stationName != null && !stationName.isBlank()) {
            String pattern = "*" + stationName.toLowerCase() + "*";
            filters.add(Query.of(q -> q.wildcard(w -> w
                    .field("station_name")
                    .value(pattern)
                    .caseInsensitive(true))));
        }
        Query queryDef = filters.isEmpty()
                ? Query.of(q -> q.matchAll(m -> m))
                : Query.of(q -> q.bool(b -> b.filter(filters)));

        boolean memorySort = sb.equals("actual") || sb.equals("error");

        if (!memorySort) {
            String esField = switch (sb) {
                case "predicted" -> "predicted_utilization_pct";
                case "source"    -> "source_utilization_pct";
                default          -> "delta_vs_source";
            };
            var response = client.search(s -> s
                    .index(index(date))
                    .from(fr)
                    .size(sz)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(queryDef)
                    .sort(so -> so.field(f -> f.field(esField).order(order))),
                    Map.class
            );

            List<Map<String, Object>> rows = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                if (hit.source() != null) rows.add((Map<String, Object>) hit.source());
            }
            enrichWithActuals(rows, date);

            long total = response.hits().total() != null ? response.hits().total().value() : 0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", total);
            result.put("from",  fr);
            result.put("size",  sz);
            result.put("sortBy", sb);
            result.put("rows",  rows);
            return result;
        }

        // Memory-sort path: fetch full pool (capped), enrich all, sort, slice.
        var pool = client.search(s -> s
                .index(index(date))
                .from(0)
                .size(MEMORY_SORT_POOL_CAP)
                .trackTotalHits(t -> t.enabled(true))
                .query(queryDef),
                Map.class
        );
        List<Map<String, Object>> allRows = new ArrayList<>();
        for (var hit : pool.hits().hits()) {
            if (hit.source() != null) allRows.add((Map<String, Object>) hit.source());
        }
        enrichWithActuals(allRows, date);

        // Drop rows with no charging activity (actual == 0 or missing) so they don't
        // dominate the ascending view.
        allRows.removeIf(r -> {
            Double a = numOrNull(r.get("actual_utilization_pct"));
            return a == null || a <= 0.0;
        });

        String key = sb.equals("actual") ? "actual_utilization_pct" : "error_vs_actual";
        boolean asc = order == SortOrder.Asc;
        boolean absSort = sb.equals("error");
        allRows.sort((a, b) -> {
            Double av = numOrNull(a.get(key));
            Double bv = numOrNull(b.get(key));
            // Missing values always last, regardless of direction.
            if (av == null && bv == null) return 0;
            if (av == null) return 1;
            if (bv == null) return -1;
            // For error, sort by magnitude (|error|) so ascending shows ~0 first.
            double aKey = absSort ? Math.abs(av) : av;
            double bKey = absSort ? Math.abs(bv) : bv;
            int cmp = Double.compare(aKey, bKey);
            return asc ? cmp : -cmp;
        });

        int toIdx = Math.min(fr + sz, allRows.size());
        List<Map<String, Object>> rows = fr >= allRows.size()
                ? List.of()
                : new ArrayList<>(allRows.subList(fr, toIdx));

        long total = allRows.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("from",  fr);
        result.put("size",  sz);
        result.put("sortBy", sb);
        result.put("poolCap", MEMORY_SORT_POOL_CAP);
        result.put("rows",  rows);
        return result;
    }

    private static Double numOrNull(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }

    /** Mutates rows: adds actual_utilization_pct + error_vs_actual when the perf-{date} index exists. */
    @SuppressWarnings("unchecked")
    private void enrichWithActuals(List<Map<String, Object>> rows, String date) throws IOException {
        if (rows.isEmpty()) return;
        String forecastDate = date != null ? date : LocalDate.now().toString();
        String perfIndex = "zes_station_performance-" + forecastDate;
        if (!client.indices().exists(e -> e.index(perfIndex)).value()) return;

        List<String> ids = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Object sid = r.get("station_id");
            if (sid != null) ids.add(sid.toString());
        }
        if (ids.isEmpty()) return;

        var perfResp = client.search(s -> s
                .index(perfIndex)
                .size(ids.size())
                .source(src -> src.filter(f -> f.includes(List.of("station_id","charging_utilization_pct"))))
                .query(q -> q.ids(i -> i.values(ids))),
                Map.class
        );
        Map<String, Double> actualByStation = new LinkedHashMap<>();
        for (var hit : perfResp.hits().hits()) {
            Map<String, Object> src = (Map<String, Object>) hit.source();
            if (src == null) continue;
            Object sid = src.get("station_id");
            Object u   = src.get("charging_utilization_pct");
            if (sid != null && u instanceof Number n) {
                actualByStation.put(sid.toString(), n.doubleValue());
            }
        }

        for (Map<String, Object> r : rows) {
            Object sid = r.get("station_id");
            if (sid == null) continue;
            Double a = actualByStation.get(sid.toString());
            if (a == null) continue;
            r.put("actual_utilization_pct", round2(a));
            Object pred = r.get("predicted_utilization_pct");
            if (pred instanceof Number p) {
                r.put("error_vs_actual", round2(p.doubleValue() - a));
            }
        }
    }

    /**
     * Bugün vs tahmin scatter (line-of-equality için).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> scatter(String date, Integer size) throws IOException {
        int sz = Math.min(size != null ? size : 2000, 2000);
        var response = client.search(s -> s
                .index(index(date))
                .size(sz)
                .source(src -> src.filter(f -> f.includes(List.of(
                        "station_id", "station_name", "city_name",
                        "predicted_utilization_pct", "source_utilization_pct", "delta_vs_source"))))
                .query(q -> q.matchAll(m -> m)),
                Map.class
        );
        List<Map<String, Object>> out = new ArrayList<>();
        for (var hit : response.hits().hits()) {
            if (hit.source() != null) out.add((Map<String, Object>) hit.source());
        }
        return out;
    }

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
    private Map<String, Object> statsMap(co.elastic.clients.elasticsearch._types.aggregations.ExtendedStatsAggregate s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count",  s.count());
        m.put("min",    round2(s.min()));
        m.put("max",    round2(s.max()));
        m.put("avg",    round2(s.avg()));
        m.put("stddev", round2(s.stdDeviation()));
        return m;
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
