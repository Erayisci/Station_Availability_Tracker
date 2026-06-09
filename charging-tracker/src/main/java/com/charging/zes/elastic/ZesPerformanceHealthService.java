package com.charging.zes.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects performance indices that were created from incomplete source data
 * (e.g. a day where the transform pipeline failed and every station ended up
 * with near-zero utilization) and offers cascading deletion.
 *
 * Status rules (defaults; overridable per call):
 *   - HEALTHY    — no criteria failed
 *   - SUSPICIOUS — exactly 1 criterion failed (warn-only)
 *   - BROKEN     — 2+ criteria failed (delete candidate)
 *
 * Criteria evaluated:
 *   mean(charging_utilization_pct)   < meanThreshold
 *   max(charging_utilization_pct)    < maxThreshold
 *   stddev(charging_utilization_pct) < stddevThreshold
 *
 * Cascade: deleting perf-{date} also drops every zes_station_forecast-* index
 * whose docs were generated from that source_date (every forecast index has a
 * single source_date because predict.py resolves it once per run).
 */
@Service
public class ZesPerformanceHealthService {

    private static final String PERF_PREFIX     = "zes_station_performance";
    private static final String FORECAST_PREFIX = "zes_station_forecast";

    public static final double DEFAULT_MEAN_THRESHOLD   = 1.0;
    public static final double DEFAULT_MAX_THRESHOLD    = 5.0;
    public static final double DEFAULT_STDDEV_THRESHOLD = 0.5;

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesPerformanceHealthService() {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    public Map<String, Object> scanAll(double meanThr, double maxThr, double stddevThr) throws IOException {
        List<String> dates = listPerfDates();
        List<Map<String, Object>> rows = new ArrayList<>();
        int broken = 0, suspicious = 0, healthy = 0;
        for (String date : dates) {
            Map<String, Object> row = check(date, meanThr, maxThr, stddevThr);
            rows.add(row);
            String status = (String) row.get("status");
            if ("BROKEN".equals(status))       broken++;
            else if ("SUSPICIOUS".equals(status)) suspicious++;
            else                                  healthy++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thresholds", thresholdsMap(meanThr, maxThr, stddevThr));
        result.put("counts", Map.of(
                "total", dates.size(),
                "healthy", healthy,
                "suspicious", suspicious,
                "broken", broken));
        result.put("indices", rows);
        return result;
    }

    public Map<String, Object> check(String date, double meanThr, double maxThr, double stddevThr) throws IOException {
        String index = PERF_PREFIX + "-" + date;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("index", index);

        if (!client.indices().exists(e -> e.index(index)).value()) {
            row.put("status", "MISSING");
            row.put("reasons", List.of("index does not exist"));
            return row;
        }

        var response = client.search(s -> s
                .index(index)
                .size(0)
                .trackTotalHits(t -> t.enabled(true))
                .aggregations("util", a -> a.extendedStats(es -> es.field("charging_utilization_pct"))),
                Void.class
        );

        long docCount = response.hits().total() != null ? response.hits().total().value() : 0;
        var stats = response.aggregations().get("util").extendedStats();
        Double mean   = stats.avg();
        Double max    = stats.max();
        Double stddev = stats.stdDeviation();
        Double min    = stats.min();

        List<String> reasons = new ArrayList<>();
        if (mean   != null && mean   < meanThr)   reasons.add("mean<"   + meanThr   + " (=" + round2(mean)   + ")");
        if (max    != null && max    < maxThr)    reasons.add("max<"    + maxThr    + " (=" + round2(max)    + ")");
        if (stddev != null && stddev < stddevThr) reasons.add("stddev<" + stddevThr + " (=" + round2(stddev) + ")");

        String status;
        if (reasons.size() >= 2)       status = "BROKEN";
        else if (reasons.size() == 1)  status = "SUSPICIOUS";
        else                           status = "HEALTHY";

        row.put("status", status);
        row.put("doc_count", docCount);
        row.put("mean_util", round2(mean));
        row.put("max_util",  round2(max));
        row.put("min_util",  round2(min));
        row.put("stddev",    round2(stddev));
        row.put("reasons",   reasons);
        return row;
    }

    /** Delete a single perf index. If cascade=true, also drop every forecast index whose source_date matches. */
    public Map<String, Object> deleteIndex(String date, boolean cascade) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);

        String perfIndex = PERF_PREFIX + "-" + date;
        boolean perfExists = client.indices().exists(e -> e.index(perfIndex)).value();
        if (perfExists) {
            client.indices().delete(d -> d.index(perfIndex));
            result.put("deleted_perf", perfIndex);
        } else {
            result.put("deleted_perf", null);
            result.put("perf_warning", "index not found");
        }

        if (cascade) {
            List<String> affected = findForecastIndicesWithSourceDate(date);
            List<String> deletedForecasts = new ArrayList<>();
            for (String fc : affected) {
                client.indices().delete(d -> d.index(fc));
                deletedForecasts.add(fc);
            }
            result.put("deleted_forecasts", deletedForecasts);
        }
        return result;
    }

    /** Delete every perf index currently flagged BROKEN. cascade applies to each. */
    public Map<String, Object> deleteBroken(double meanThr, double maxThr, double stddevThr,
                                            boolean cascade, boolean dryRun) throws IOException {
        Map<String, Object> scan = scanAll(meanThr, maxThr, stddevThr);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) scan.get("indices");
        List<Map<String, Object>> targets = rows.stream()
                .filter(r -> "BROKEN".equals(r.get("status")))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thresholds", thresholdsMap(meanThr, maxThr, stddevThr));
        result.put("dry_run", dryRun);
        result.put("cascade", cascade);
        result.put("broken_count", targets.size());

        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> t : targets) {
            String date = (String) t.get("date");
            if (dryRun) {
                Map<String, Object> preview = new LinkedHashMap<>();
                preview.put("date", date);
                preview.put("reasons", t.get("reasons"));
                preview.put("would_delete_perf", t.get("index"));
                if (cascade) preview.put("would_delete_forecasts", findForecastIndicesWithSourceDate(date));
                actions.add(preview);
            } else {
                actions.add(deleteIndex(date, cascade));
            }
        }
        result.put("actions", actions);
        return result;
    }

    /** Forecast indices whose docs were generated from the given source_date. */
    private List<String> findForecastIndicesWithSourceDate(String sourceDate) throws IOException {
        List<String> affected = new ArrayList<>();
        var cat = client.cat().indices(c -> c.index(FORECAST_PREFIX + "-*"));
        for (var rec : cat.valueBody()) {
            String idx = rec.index();
            if (idx == null) continue;
            var resp = client.search(s -> s
                    .index(idx)
                    .size(1)
                    .source(src -> src.filter(f -> f.includes(List.of("source_date"))))
                    .query(q -> q.matchAll(m -> m)),
                    Map.class
            );
            if (resp.hits().hits().isEmpty()) continue;
            Object src = resp.hits().hits().get(0).source().get("source_date");
            if (src != null && sourceDate.equals(src.toString())) {
                affected.add(idx);
            }
        }
        return affected;
    }

    private List<String> listPerfDates() throws IOException {
        var resp = client.cat().indices(c -> c.index(PERF_PREFIX + "-*"));
        List<String> dates = new ArrayList<>();
        for (var rec : resp.valueBody()) {
            String idx = rec.index();
            if (idx != null && idx.startsWith(PERF_PREFIX + "-")) {
                dates.add(idx.substring(PERF_PREFIX.length() + 1));
            }
        }
        dates.sort(String::compareTo);
        return dates;
    }

    private Map<String, Object> thresholdsMap(double meanThr, double maxThr, double stddevThr) {
        return Map.of("mean", meanThr, "max", maxThr, "stddev", stddevThr);
    }

    private Double round2(Double v) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Math.round(v * 100.0) / 100.0;
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
