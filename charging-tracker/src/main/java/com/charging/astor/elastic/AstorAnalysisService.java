package com.charging.astor.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Elasticsearch analysis queries for astor_stations-{date} indices.
 *
 * Doc structure: flat fields (unit_name, location_code, tenant_code, connector_current_type, ...)
 *   + availability (nested): status, start_time, end_time, duration_minutes, time_slot, is_available
 */
@Service
public class AstorAnalysisService {

    private static final String INDEX_PREFIX = "astor_stations";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public AstorAnalysisService() {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
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

    // -------------------------------------------------------------------------
    // Status by time slot — scroll + in-memory expansion
    // Each availability slot [startTime, endTime) is expanded into interval buckets.
    // Returns: { "HH:mm" → { status → count } }
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Long>> statusByTimeSlot(
            String date, int intervalMinutes,
            String tenantCode, String connectorCurrentType) throws IOException {

        Map<String, Map<String, Long>> result = new TreeMap<>();

        var firstResponse = client.search(s -> {
            s.index(indexForDate(date)).size(1000).scroll(sc -> sc.time("2m"));
            if (tenantCode != null || connectorCurrentType != null) {
                s.query(q -> q.bool(b -> {
                    if (tenantCode != null)
                        b.filter(f -> f.term(t -> t.field("tenant_code").value(tenantCode)));
                    if (connectorCurrentType != null)
                        b.filter(f -> f.term(t -> t.field("connector_current_type").value(connectorCurrentType)));
                    return b;
                }));
            }
            return s;
        }, Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();
        accumulateTimeSlots(hits, intervalMinutes, result);

        while (!hits.isEmpty()) {
            String sid = scrollId;
            var next = client.scroll(r -> r.scrollId(sid).scroll(sc -> sc.time("2m")), Map.class);
            scrollId = next.scrollId();
            hits = next.hits().hits();
            accumulateTimeSlots(hits, intervalMinutes, result);
        }

        if (scrollId != null) {
            String sid = scrollId;
            client.clearScroll(r -> r.scrollId(List.of(sid)));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private void accumulateTimeSlots(List<?> hits, int intervalMinutes,
                                      Map<String, Map<String, Long>> result) {
        for (var hit : hits) {
            var h = (co.elastic.clients.elasticsearch.core.search.Hit<Map>) hit;
            Map<String, Object> doc = h.source();
            if (doc == null) continue;

            List<Map<String, Object>> availability =
                    (List<Map<String, Object>>) doc.getOrDefault("availability", List.of());

            for (Map<String, Object> avail : availability) {
                String status       = (String) avail.get("status");
                String startTimeStr = (String) avail.get("start_time");
                String endTimeStr   = (String) avail.get("end_time");
                if (status == null || startTimeStr == null) continue;

                try {
                    String startPart = startTimeStr.contains("T") ? startTimeStr.split("T")[1] : startTimeStr;
                    String[] startParts = startPart.substring(0, 5).split(":");
                    int startMin = Integer.parseInt(startParts[0]) * 60 + Integer.parseInt(startParts[1]);

                    int endMin;
                    if (endTimeStr != null) {
                        String endPart = endTimeStr.contains("T") ? endTimeStr.split("T")[1] : endTimeStr;
                        String[] endParts = endPart.substring(0, 5).split(":");
                        endMin = Integer.parseInt(endParts[0]) * 60 + Integer.parseInt(endParts[1]);
                    } else {
                        endMin = 24 * 60;
                    }

                    int firstBucket = ((startMin + intervalMinutes - 1) / intervalMinutes) * intervalMinutes;
                    for (int t = firstBucket; t < endMin; t += intervalMinutes) {
                        String slotLabel = String.format("%02d:%02d", t / 60, t % 60);
                        result.computeIfAbsent(slotLabel, k -> new LinkedHashMap<>())
                              .merge(status, 1L, Long::sum);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Duration by location code — scroll + in-memory sum
    // Returns: { locationCode → { status → totalMinutes } }
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Double>> durationByLocationCode(String date) throws IOException {
        Map<String, Map<String, Double>> raw = new HashMap<>();

        var firstResponse = client.search(s -> s
                .index(indexForDate(date))
                .size(1000)
                .scroll(sc -> sc.time("2m")),
                Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();
        accumulateDuration(hits, raw);

        while (!hits.isEmpty()) {
            String sid = scrollId;
            var next = client.scroll(r -> r.scrollId(sid).scroll(sc -> sc.time("2m")), Map.class);
            scrollId = next.scrollId();
            hits = next.hits().hits();
            accumulateDuration(hits, raw);
        }

        if (scrollId != null) {
            String sid = scrollId;
            client.clearScroll(r -> r.scrollId(List.of(sid)));
        }

        // Sort by total available duration descending, return top 20
        return raw.entrySet().stream()
                .sorted((a, b) -> Double.compare(
                        b.getValue().getOrDefault("available", 0.0),
                        a.getValue().getOrDefault("available", 0.0)))
                .limit(20)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new));
    }

    @SuppressWarnings("unchecked")
    private void accumulateDuration(List<?> hits, Map<String, Map<String, Double>> result) {
        for (var hit : hits) {
            var h = (co.elastic.clients.elasticsearch.core.search.Hit<Map>) hit;
            Map<String, Object> doc = h.source();
            if (doc == null) continue;

            String locationCode = (String) doc.get("location_code");
            if (locationCode == null) continue;

            List<Map<String, Object>> availability =
                    (List<Map<String, Object>>) doc.getOrDefault("availability", List.of());

            for (Map<String, Object> avail : availability) {
                String status   = (String) avail.get("status");
                if (status == null) continue;

                Object durObj = avail.get("duration_minutes");
                double duration;
                if (durObj != null) {
                    try { duration = Double.parseDouble(String.valueOf(durObj)); }
                    catch (NumberFormatException e) { continue; }
                } else {
                    // Open slot (end_time null) — hesapla: start_time'dan şu ana kadar
                    String startStr = (String) avail.get("start_time");
                    if (startStr == null) continue;
                    try {
                        LocalDateTime start = LocalDateTime.parse(startStr.length() > 19
                                ? startStr.substring(0, 19) : startStr);
                        duration = ChronoUnit.SECONDS.between(start, LocalDateTime.now()) / 60.0;
                        if (duration <= 0) continue;
                    } catch (Exception e) { continue; }
                }

                result.computeIfAbsent(locationCode, k -> new LinkedHashMap<>())
                      .merge(status, duration, Double::sum);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Station search
    // Returns full station docs (including availability array).
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchStations(
            String date,
            String status,
            String connectorCurrentType,
            String tenantCode,
            String locationCode,
            String address,
            String time,
            int from,
            int size) throws IOException {

        String resolvedDate = date != null ? date : LocalDate.now().toString();

        var response = client.search(s -> {
            s.index(indexForDate(date)).from(from).size(size);
            s.query(q -> q.bool(b -> {
                if (connectorCurrentType != null)
                    b.filter(f -> f.term(t -> t.field("connector_current_type").value(connectorCurrentType)));
                if (tenantCode != null)
                    b.filter(f -> f.term(t -> t.field("tenant_code").value(tenantCode)));
                if (locationCode != null)
                    b.filter(f -> f.term(t -> t.field("location_code").value(locationCode)));
                if (address != null)
                    b.filter(f -> f.match(m -> m.field("address").query(address)));

                if (status != null || time != null) {
                    String dateTimeStr = time != null ? resolvedDate + "T" + time + ":00" : null;
                    b.filter(f -> f.nested(n -> n
                        .path("availability")
                        .query(aq -> aq.bool(ab -> {
                            if (status != null)
                                ab.filter(sf -> sf.term(t -> t.field("availability.status").value(status)));
                            if (dateTimeStr != null) {
                                ab.filter(rf -> rf.range(r -> r
                                        .field("availability.start_time")
                                        .lte(JsonData.of(dateTimeStr))));
                                ab.should(s1 -> s1.range(r -> r
                                        .field("availability.end_time")
                                        .gte(JsonData.of(dateTimeStr))));
                                ab.should(s2 -> s2.bool(b2 -> b2
                                        .mustNot(mn -> mn.exists(e -> e.field("availability.end_time")))));
                                ab.minimumShouldMatch("1");
                            }
                            return ab;
                        }))
                    ));
                }
                return b;
            }));
            return s;
        }, Map.class);

        return response.hits().hits().stream()
                .map(hit -> (Map<String, Object>) hit.source())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
