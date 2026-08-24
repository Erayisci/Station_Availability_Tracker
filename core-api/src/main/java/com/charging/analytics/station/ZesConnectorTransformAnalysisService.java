package com.charging.analytics.station;

import com.charging.kernel.es.IndexCatalog;

import com.charging.analytics.environment.ZesEnvironmentAnalysisService;
import com.charging.kernel.es.EsConfig;
import com.charging.kernel.persistence.ConnectorStatusHistory;
import com.charging.kernel.persistence.ConnectorStatusHistoryRepository;


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

    private static final String INDEX_PREFIX = IndexCatalog.ZES_CONNECTOR_TRANSFORM;

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    @Autowired
    private ZesEnvironmentAnalysisService envAnalysisService;

    @Autowired
    private ConnectorStatusHistoryRepository historyRepository;

    public ZesConnectorTransformAnalysisService() {
        restClient = RestClient.builder(EsConfig.host()).build();
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

        // Prefer H2 polling history when available and no filters are set.
        // H2 holds the authoritative raw polling slots for the retention window
        // (default 7 days). The daily ES transform can lag or be incomplete if
        // polling started late in the day, so H2 gives a truer picture for
        // recent dates. Filters (connectorType/province) are not on H2 rows,
        // so filtered queries always go through ES.
        LocalDate today = LocalDate.now();
        LocalDate target = (date != null) ? LocalDate.parse(date) : today;
        if (connectorType == null && province == null) {
            List<ConnectorStatusHistory> rows = historyRepository.findByTrackDate(target);
            if (!rows.isEmpty()) {
                return statusByTimeSlotFromH2(target, intervalMinutes, rows);
            }
        }

        String indexName = indexForDate(date);

        // Pre-build every slot of the day so each one is present in the result,
        // even when no connector reported a status for it.
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        List<String> allSlots = new ArrayList<>();
        for (int t = 0; t < 24 * 60; t += intervalMinutes) {
            String label = formatSlot(t, intervalMinutes);
            allSlots.add(label);
            result.put(label, new LinkedHashMap<>());
        }

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

                // For this connector, decide the status of every slot in the day.
                // First slice that covers a slot wins (slices shouldn't overlap, but be safe).
                Map<String, String> slotStatus = new HashMap<>();
                for (Map<String, Object> avail : availability) {
                    String status   = (String) avail.get("status");
                    String startStr = (String) avail.get("start_time");
                    String endStr   = (String) avail.get("end_time");

                    if (status == null || startStr == null) continue;

                    int startMin = minutesOfDay(LocalDateTime.parse(startStr));
                    int endMin   = endStr != null
                            ? minutesOfDay(LocalDateTime.parse(endStr))
                            : 24 * 60;

                    // Slot containing startMin (floor) — start_time's own time_slot field uses this rule.
                    int firstSlot = (startMin / intervalMinutes) * intervalMinutes;

                    for (int T = firstSlot; T < endMin && T < 24 * 60; T += intervalMinutes) {
                        slotStatus.putIfAbsent(formatSlot(T, intervalMinutes), status);
                    }
                }

                // Every connector contributes exactly one status to every slot.
                // Slots with no observation become "Unknown" so the per-slot total is constant.
                for (String slot : allSlots) {
                    String status = slotStatus.getOrDefault(slot, "Unknown");
                    result.get(slot).merge(status, 1L, Long::sum);
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

    /**
     * Continuous-time event-stream view (no slot binning).
     *
     * Returns an ordered list of snapshots. Each entry is a moment in time when at
     * least one connector changed status, plus the absolute count per status at
     * that exact instant. Frontend renders with stepped interpolation: between
     * two events the counts stay constant.
     *
     * Initial state (t = 00:00:00 of the day): every connector starts as "Unknown".
     * As polling observations come in, connectors transition out of Unknown into
     * their observed status. Open slices (end_time = null) are interpreted as
     * "this status holds until end of day" — no synthetic close event is emitted,
     * so the last segment renders as a flat line through midnight.
     *
     * Output: [ { "t": "2026-04-09T10:05:23", "Available": 4500, "Charging": 300, "Unknown": 100, ... }, ... ]
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> statusTimeline(
            String date, String connectorType, String province) throws IOException {

        String indexName = indexForDate(date);
        String dayStr = (date != null ? date : LocalDate.now().toString());
        LocalDateTime dayStart = LocalDate.parse(dayStr).atStartOfDay();

        // Each event: timestamp + status delta map (status -> +/-1 deltas)
        // We model every transition as -1 from the previous status, +1 to the new status.
        // Per connector we walk its slices in time order.

        // event_timestamp -> aggregated deltas across all connectors at that instant
        TreeMap<LocalDateTime, Map<String, Long>> events = new TreeMap<>();
        long totalConnectors = 0;

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
                totalConnectors++;

                List<Map<String, Object>> availability =
                        (List<Map<String, Object>>) doc.getOrDefault("availability", List.of());

                // Sort slices by start_time; slices without a start are skipped.
                List<Map<String, Object>> sorted = new ArrayList<>();
                for (Map<String, Object> a : availability) {
                    if (a.get("start_time") != null && a.get("status") != null) sorted.add(a);
                }
                sorted.sort(Comparator.comparing(a -> LocalDateTime.parse((String) a.get("start_time"))));

                // Walk slices: at start_time of slice i the connector transitions from
                // (Unknown if i==0, else previous slice's status) to slice's status.
                String prevStatus = "Unknown";
                for (Map<String, Object> a : sorted) {
                    String status = (String) a.get("status");
                    LocalDateTime start = LocalDateTime.parse((String) a.get("start_time"));
                    if (start.isBefore(dayStart)) start = dayStart;

                    if (!status.equals(prevStatus)) {
                        Map<String, Long> deltas = events.computeIfAbsent(start, k -> new HashMap<>());
                        deltas.merge(prevStatus, -1L, Long::sum);
                        deltas.merge(status, 1L, Long::sum);
                        prevStatus = status;
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

        // Build timeline: start with all connectors as Unknown at 00:00, then apply deltas in order.
        List<Map<String, Object>> timeline = new ArrayList<>();
        Map<String, Long> running = new LinkedHashMap<>();
        running.put("Unknown", totalConnectors);

        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("t", dayStart.toString());
        initial.putAll(running);
        timeline.add(initial);

        for (var entry : events.entrySet()) {
            for (var d : entry.getValue().entrySet()) {
                long newVal = running.getOrDefault(d.getKey(), 0L) + d.getValue();
                if (newVal == 0) running.remove(d.getKey());
                else running.put(d.getKey(), newVal);
            }
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("t", entry.getKey().toString());
            snap.putAll(running);
            timeline.add(snap);
        }

        return timeline;
    }

    /**
     * Build the time-slot snapshot directly from H2 polling history.
     * For today, emits slots up to the current minute (no future slots) and
     * treats open slices (end_time = null) as ongoing through "now".
     * For past dates, emits all 24h and treats open slices as ongoing through
     * end-of-day.
     */
    private Map<String, Map<String, Long>> statusByTimeSlotFromH2(
            LocalDate date, int intervalMinutes, List<ConnectorStatusHistory> rows) {

        boolean isToday = date.equals(LocalDate.now());
        int upperMin     = isToday ? minutesOfDay(LocalDateTime.now()) : 24 * 60 - 1;
        int openSliceEnd = isToday ? upperMin + 1                      : 24 * 60;

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        List<String> allSlots = new ArrayList<>();
        for (int t = 0; t < 24 * 60 && t <= upperMin; t += intervalMinutes) {
            String label = formatSlot(t, intervalMinutes);
            allSlots.add(label);
            result.put(label, new LinkedHashMap<>());
        }
        if (allSlots.isEmpty()) return result;

        Map<String, List<ConnectorStatusHistory>> byConnector = rows.stream()
                .collect(Collectors.groupingBy(ConnectorStatusHistory::getConnectorId));

        for (Map.Entry<String, List<ConnectorStatusHistory>> entry : byConnector.entrySet()) {
            Map<String, String> slotStatus = new HashMap<>();
            for (ConnectorStatusHistory row : entry.getValue()) {
                if (row.getStatus() == null || row.getStartTime() == null) continue;
                int startMin = minutesOfDay(row.getStartTime());
                int endMin   = row.getEndTime() != null
                        ? minutesOfDay(row.getEndTime())
                        : openSliceEnd;
                int firstSlot = (startMin / intervalMinutes) * intervalMinutes;
                for (int T = firstSlot; T < endMin && T <= upperMin; T += intervalMinutes) {
                    slotStatus.putIfAbsent(formatSlot(T, intervalMinutes), row.getStatus());
                }
            }
            for (String slot : allSlots) {
                String status = slotStatus.getOrDefault(slot, "Unknown");
                result.get(slot).merge(status, 1L, Long::sum);
            }
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

    public List<String> availableDates() throws IOException {
        var response = client.indices().get(r -> r.index(INDEX_PREFIX + "-*"));
        return response.result().keySet().stream()
                .map(name -> name.replace(INDEX_PREFIX + "-", ""))
                .filter(d -> d.matches("\\d{4}-\\d{2}-\\d{2}"))
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
