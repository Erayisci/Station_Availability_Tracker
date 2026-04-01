package com.charging.zes.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads zes_connector_transform-{date} and computes per-time-slot status snapshots.
 *
 * Logic: a connector is counted in status S at slot T if there exists an availability entry
 * where start_time <= T < end_time (or end_time is null → ongoing until end of day).
 * This gives a true "how many connectors are in each status at this moment" view.
 */
@Service
public class ZesConnectorTransformAnalysisService {

    private static final String INDEX_PREFIX = "zes_connector_transform";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    @Autowired
    private ZesEnvironmentAnalysisService envAnalysisService;

    public ZesConnectorTransformAnalysisService() {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    private String indexForDate(String date) {
        return INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    /**
     * Returns: { "09:00-09:30" → { "Available" → 4500, "Charging" → 300, … }, … }
     *
     * For each connector's availability entry [start, end):
     *   - first slot covered = ceil(startMin / intervalMinutes) * intervalMinutes
     *   - keep incrementing by intervalMinutes while T < endMin
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Long>> statusByTimeSlot(
            String date, int intervalMinutes, String connectorType, String province) throws IOException {

        String indexName = indexForDate(date);
        Map<String, Map<String, Long>> result = new TreeMap<>();

        var firstResponse = client.search(s -> {
            s.index(indexName).size(1000).scroll(sc -> sc.time("2m"));
            if (connectorType != null || province != null) {
                s.query(q -> q.bool(b -> {
                    if (connectorType != null)
                        b.filter(f -> f.term(t -> t.field("connector_type").value(connectorType)));
                    if (province != null)
                        b.filter(f -> f.term(t -> t.field("city_name").value(province)));
                    return b;
                }));
            }
            return s;
        }, Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();

        while (!hits.isEmpty()) {
            for (var hit : hits) {
                Map<String, Object> doc = (Map<String, Object>) hit.source();
                if (doc == null) continue;

                List<Map<String, Object>> availability =
                        (List<Map<String, Object>>) doc.getOrDefault("availability", List.of());

                for (Map<String, Object> avail : availability) {
                    String status   = (String) avail.get("status");
                    String startStr = (String) avail.get("start_time");
                    String endStr   = (String) avail.get("end_time");

                    if (status == null || startStr == null) continue;

                    int startMin = minutesOfDay(LocalDateTime.parse(startStr));
                    int endMin   = endStr != null
                            ? minutesOfDay(LocalDateTime.parse(endStr))
                            : 24 * 60 - 1;

                    // First slot T where T >= startMin and T is a multiple of intervalMinutes
                    int firstSlot = (int) (Math.ceil((double) startMin / intervalMinutes) * intervalMinutes);

                    for (int T = firstSlot; T < endMin && T < 24 * 60; T += intervalMinutes) {
                        String slotLabel = formatSlot(T, intervalMinutes);
                        result.computeIfAbsent(slotLabel, k -> new LinkedHashMap<>())
                              .merge(status, 1L, Long::sum);
                    }
                }
            }

            String sid = scrollId;
            var next = client.scroll(r -> r.scrollId(sid).scroll(sc -> sc.time("2m")), Map.class);
            scrollId = next.scrollId();
            hits = next.hits().hits();
        }

        if (scrollId != null) {
            String sid = scrollId;
            client.clearScroll(r -> r.scrollId(List.of(sid)));
        }

        return result;
    }

    private int minutesOfDay(LocalDateTime dt) {
        return dt.getHour() * 60 + dt.getMinute();
    }

    private String formatSlot(int startMin, int intervalMinutes) {
        int endMin = startMin + intervalMinutes;
        return String.format("%02d:%02d-%02d:%02d",
                startMin / 60, startMin % 60,
                (endMin / 60) % 24, endMin % 60);
    }

    /**
     * Returns: { cityName → { status → totalDurationMinutes } }
     * Sorted by total charging minutes descending, top 20 cities.
     * duration_minutes null olan (açık) slotlar ES sum tarafından otomatik ignore edilir.
     */
    public Map<String, Map<String, Double>> durationByCity(String date) throws IOException {
        String indexName = indexForDate(date);

        var response = client.search(s -> s
                .index(indexName)
                .size(0)
                .aggregations("by_city", a -> a
                        .terms(t -> t.field("city_name").size(50))
                        .aggregations("avail_nested", aa -> aa
                                .nested(n -> n.path("availability"))
                                .aggregations("by_status", aaa -> aaa
                                        .terms(t -> t.field("availability.status").size(20))
                                        .aggregations("total_minutes", aaaa -> aaaa
                                                .sum(sum -> sum.field("availability.duration_minutes"))
                                        )
                                )
                        )
                ),
                Void.class
        );

        Map<String, Double> cityTotals = new HashMap<>();
        Map<String, Map<String, Double>> raw = new LinkedHashMap<>();

        for (var cityBucket : response.aggregations().get("by_city").sterms().buckets().array()) {
            String city = cityBucket.key().stringValue();
            var nested = cityBucket.aggregations().get("avail_nested").nested();
            var byStatus = nested.aggregations().get("by_status").sterms();

            Map<String, Double> statusDurations = new LinkedHashMap<>();
            double cityTotal = 0;
            for (var sb : byStatus.buckets().array()) {
                double minutes = sb.aggregations().get("total_minutes").sum().value();
                statusDurations.put(sb.key().stringValue(), minutes);
                cityTotal += minutes;
            }
            raw.put(city, statusDurations);
            cityTotals.put(city, cityTotal);
        }

        // Sort by total duration descending, keep top 20
        return cityTotals.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(20)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> raw.get(e.getKey()),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    /**
     * Belirli bir saatte belli statüste olan connector'ları arar.
     *
     * Filtre mantığı: availability array'inde
     *   start_time <= {date}T{time}:00  AND  (end_time >= {date}T{time}:00 OR end_time null)
     *   AND status = {status}
     *
     * @param time "HH:mm" formatında — örn. "08:30"
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchConnectors(
            String date, String province, String connectorType,
            String status, String time, String address, String envType, int from, int size) throws IOException {

        String indexName = INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
        String dateStr   = date != null ? date : LocalDate.now().toString();
        String datetime  = time != null ? dateStr + "T" + time + ":00" : null;

        Map<String, String> envMap = envAnalysisService.loadStationEnvMap();

        List<String> matchingStationIds = envType != null
                ? envMap.entrySet().stream()
                        .filter(e -> envType.equalsIgnoreCase(e.getValue()))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList())
                : null;

        var response = client.search(s -> {
            s.index(indexName).from(from).size(size);
            s.query(q -> q.bool(b -> {
                if (province != null)
                    b.filter(f -> f.term(t -> t.field("city_name").value(province)));
                if (connectorType != null)
                    b.filter(f -> f.term(t -> t.field("connector_type").value(connectorType)));
                if (address != null)
                    b.filter(f -> f.wildcard(w -> w
                        .field("address.keyword")
                        .wildcard("*" + address + "*")
                        .caseInsensitive(true)));
                if (matchingStationIds != null)
                    b.filter(f -> f.terms(t -> t
                        .field("station_id")
                        .terms(tv -> tv.value(matchingStationIds.stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .collect(Collectors.toList())))));
                if (status != null && time != null) {
                    b.filter(f -> f.nested(n -> n
                        .path("availability")
                        .query(aq -> aq.bool(ab -> {
                            ab.filter(af -> af.term(t -> t.field("availability.status").value(status)));
                            ab.filter(af -> af.range(r -> r
                                .field("availability.start_time")
                                .lte(JsonData.of(datetime))));
                            ab.filter(af -> af.bool(rb -> rb
                                .should(sh -> sh.range(r -> r
                                    .field("availability.end_time")
                                    .gte(JsonData.of(datetime))))
                                .should(sh -> sh.bool(nb -> nb
                                    .mustNot(mn -> mn.exists(e -> e.field("availability.end_time")))))
                                .minimumShouldMatch("1")));
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
                .peek(doc -> {
                    String stationId = String.valueOf(doc.get("station_id"));
                    doc.put("env_type", envMap.getOrDefault(stationId, "UNKNOWN"));
                })
                .collect(Collectors.toList());
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
