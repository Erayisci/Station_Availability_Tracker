package com.charging.zes.elastic;

import com.charging.elastic.EsConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Backtest: deployed forecast vs realised performance.
 *
 *   /zes/backtest/dates              — overlap dates (forecast ∩ perf)
 *   /zes/backtest/{date}/summary     — KPIs + per-city breakdown for one date
 *   /zes/backtest/{date}/stations    — every station row (pred, source, actual, error)
 *   /zes/backtest/trend              — per-date MAE for model vs naive baseline
 *
 * Mirrors model_training/forecast/backtest.py — same metrics, same naive baseline
 * (predicted = source_utilization_pct).
 */
@Service
public class ZesForecastBacktestService {

    private static final String FORECAST_PREFIX = "zes_station_forecast";
    private static final String PERF_PREFIX     = "zes_station_performance";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesForecastBacktestService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    /** Dates where both forecast-{D} AND perf-{D} exist (intersection). */
    public List<String> overlapDates() throws IOException {
        Set<String> fc   = listDates(FORECAST_PREFIX);
        Set<String> perf = listDates(PERF_PREFIX);
        List<String> out = new ArrayList<>(fc);
        out.retainAll(perf);
        out.sort(Comparator.reverseOrder());
        return out;
    }

    /** Per-station joined rows: predicted, source (naive), actual, error. */
    public List<Map<String, Object>> stationsFor(String date) throws IOException {
        Map<String, Map<String, Object>> fcMap = scanForecast(date);
        Map<String, Double> actualMap = scanActual(date);

        List<Map<String, Object>> rows = new ArrayList<>(fcMap.size());
        for (Map.Entry<String, Map<String, Object>> e : fcMap.entrySet()) {
            String sid  = e.getKey();
            Double act  = actualMap.get(sid);
            if (act == null) continue;
            Map<String, Object> src = e.getValue();
            Double pred = asDouble(src.get("predicted_utilization_pct"));
            Double srcU = asDouble(src.get("source_utilization_pct"));
            if (pred == null || srcU == null) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("station_id",   sid);
            row.put("station_name", src.get("station_name"));
            row.put("city_name",    src.get("city_name"));
            row.put("predicted",    round2(pred));
            row.put("source",       round2(srcU));
            row.put("actual",       round2(act));
            row.put("error",        round2(pred - act));
            row.put("abs_error",    round2(Math.abs(pred - act)));
            row.put("naive_error",  round2(srcU - act));
            rows.add(row);
        }
        return rows;
    }

    /** KPIs + per-city aggregation for one date. */
    public Map<String, Object> summaryFor(String date) throws IOException {
        List<Map<String, Object>> rows = stationsFor(date);
        if (rows.isEmpty()) {
            return Map.of("date", date, "n", 0, "error", "no overlap rows after join");
        }

        double[] yTrue  = new double[rows.size()];
        double[] yModel = new double[rows.size()];
        double[] yNaive = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            yTrue[i]  = ((Number) rows.get(i).get("actual")).doubleValue();
            yModel[i] = ((Number) rows.get(i).get("predicted")).doubleValue();
            yNaive[i] = ((Number) rows.get(i).get("source")).doubleValue();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date",  date);
        result.put("n",     rows.size());
        result.put("model", metrics(yTrue, yModel));
        result.put("naive", metrics(yTrue, yNaive));

        double mMae = ((Number) ((Map<?,?>) result.get("model")).get("mae")).doubleValue();
        double nMae = ((Number) ((Map<?,?>) result.get("naive")).get("mae")).doubleValue();
        double improvement = nMae > 0 ? (nMae - mMae) / nMae * 100.0 : 0.0;
        result.put("improvement_pct_vs_naive", round2(improvement));

        // Per-city aggregation
        Map<String, List<double[]>> byCity = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String city = (String) rows.get(i).getOrDefault("city_name", "—");
            if (city == null) city = "—";
            byCity.computeIfAbsent(city, k -> new ArrayList<>())
                  .add(new double[]{ yTrue[i], yModel[i], yNaive[i] });
        }
        List<Map<String, Object>> cityRows = new ArrayList<>();
        for (var e : byCity.entrySet()) {
            int n = e.getValue().size();
            double[] yt = new double[n], ym = new double[n], yn = new double[n];
            for (int i = 0; i < n; i++) { yt[i] = e.getValue().get(i)[0]; ym[i] = e.getValue().get(i)[1]; yn[i] = e.getValue().get(i)[2]; }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("city",      e.getKey());
            r.put("n",         n);
            r.put("mae_model", round2(mae(yt, ym)));
            r.put("mae_naive", round2(mae(yt, yn)));
            cityRows.add(r);
        }
        cityRows.sort((a, b) -> Integer.compare((Integer) b.get("n"), (Integer) a.get("n")));
        result.put("by_city", cityRows);

        return result;
    }

    /** Per-date MAE for every overlap date (cheap; no per-station detail). */
    public List<Map<String, Object>> trend() throws IOException {
        List<String> dates = overlapDates();
        Collections.reverse(dates); // ascending for time-series
        List<Map<String, Object>> out = new ArrayList<>();
        for (String d : dates) {
            List<Map<String, Object>> rows = stationsFor(d);
            if (rows.isEmpty()) continue;
            double[] yt = new double[rows.size()], ym = new double[rows.size()], yn = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                yt[i] = ((Number) rows.get(i).get("actual")).doubleValue();
                ym[i] = ((Number) rows.get(i).get("predicted")).doubleValue();
                yn[i] = ((Number) rows.get(i).get("source")).doubleValue();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date",       d);
            r.put("n",          rows.size());
            r.put("mae_model",  round2(mae(yt, ym)));
            r.put("mae_naive",  round2(mae(yt, yn)));
            out.add(r);
        }
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────

    private Map<String, Map<String, Object>> scanForecast(String date) throws IOException {
        Map<String, Map<String, Object>> map = new HashMap<>();
        String index = FORECAST_PREFIX + "-" + date;
        if (!client.indices().exists(e -> e.index(index)).value()) return map;

        String pit = client.openPointInTime(p -> p.index(index).keepAlive(t -> t.time("1m"))).id();
        try {
            List<co.elastic.clients.elasticsearch._types.FieldValue> after = null;
            while (true) {
                final List<co.elastic.clients.elasticsearch._types.FieldValue> afterCopy = after;
                var resp = client.search(s -> {
                        s.size(1000)
                         .pit(p -> p.id(pit).keepAlive(t -> t.time("1m")))
                         .sort(so -> so.field(f -> f.field("station_id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)))
                         .source(src -> src.filter(f -> f.includes(List.of(
                                 "station_id","station_name","city_name",
                                 "predicted_utilization_pct","source_utilization_pct"))));
                        if (afterCopy != null) s.searchAfter(afterCopy);
                        return s;
                    }, Map.class);
                var hits = resp.hits().hits();
                if (hits.isEmpty()) break;
                for (Hit<Map> h : hits) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) h.source();
                    if (src == null) continue;
                    Object sid = src.get("station_id");
                    if (sid != null) map.put(sid.toString(), src);
                }
                after = hits.get(hits.size() - 1).sort();
                if (hits.size() < 1000) break;
            }
        } finally {
            client.closePointInTime(c -> c.id(pit));
        }
        return map;
    }

    private Map<String, Double> scanActual(String date) throws IOException {
        Map<String, Double> map = new HashMap<>();
        String index = PERF_PREFIX + "-" + date;
        if (!client.indices().exists(e -> e.index(index)).value()) return map;

        String pit = client.openPointInTime(p -> p.index(index).keepAlive(t -> t.time("1m"))).id();
        try {
            List<co.elastic.clients.elasticsearch._types.FieldValue> after = null;
            while (true) {
                final List<co.elastic.clients.elasticsearch._types.FieldValue> afterCopy = after;
                var resp = client.search(s -> {
                        s.size(1000)
                         .pit(p -> p.id(pit).keepAlive(t -> t.time("1m")))
                         .sort(so -> so.field(f -> f.field("station_id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)))
                         .source(src -> src.filter(f -> f.includes(List.of(
                                 "station_id","charging_utilization_pct"))));
                        if (afterCopy != null) s.searchAfter(afterCopy);
                        return s;
                    }, Map.class);
                var hits = resp.hits().hits();
                if (hits.isEmpty()) break;
                for (Hit<Map> h : hits) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) h.source();
                    if (src == null) continue;
                    Object sid = src.get("station_id");
                    Double u    = asDouble(src.get("charging_utilization_pct"));
                    if (sid != null && u != null) map.put(sid.toString(), u);
                }
                after = hits.get(hits.size() - 1).sort();
                if (hits.size() < 1000) break;
            }
        } finally {
            client.closePointInTime(c -> c.id(pit));
        }
        return map;
    }

    private Set<String> listDates(String prefix) throws IOException {
        var resp = client.cat().indices(c -> c.index(prefix + "-*"));
        Set<String> dates = new HashSet<>();
        for (var rec : resp.valueBody()) {
            String idx = rec.index();
            if (idx != null && idx.startsWith(prefix + "-")) {
                dates.add(idx.substring(prefix.length() + 1));
            }
        }
        return dates;
    }

    private static Map<String, Object> metrics(double[] yTrue, double[] yPred) {
        if (yTrue.length == 0) return Map.of("n", 0);
        double sumAbs = 0, sumSq = 0, sumErr = 0;
        double meanTrue = 0;
        for (double v : yTrue) meanTrue += v;
        meanTrue /= yTrue.length;

        double ssRes = 0, ssTot = 0;
        for (int i = 0; i < yTrue.length; i++) {
            double e = yPred[i] - yTrue[i];
            sumAbs += Math.abs(e);
            sumSq  += e * e;
            sumErr += e;
            ssRes  += e * e;
            double dT = yTrue[i] - meanTrue;
            ssTot  += dT * dT;
        }
        double mae  = sumAbs / yTrue.length;
        double rmse = Math.sqrt(sumSq / yTrue.length);
        double bias = sumErr / yTrue.length;
        double r2   = ssTot > 0 ? 1.0 - ssRes / ssTot : 0.0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n",    yTrue.length);
        m.put("mae",  round2(mae));
        m.put("rmse", round2(rmse));
        m.put("r2",   round4(r2));
        m.put("bias", round2(bias));
        return m;
    }

    private static double mae(double[] yt, double[] yp) {
        double s = 0;
        for (int i = 0; i < yt.length; i++) s += Math.abs(yp[i] - yt[i]);
        return s / yt.length;
    }

    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return null; }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
