package com.charging.zes.elastic;

import com.charging.elastic.EsConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ZesEnvironmentAnalysisService {

    private static final String ENV_INDEX            = "zes_station_environment";
    private static final String TRANSFORM_INDEX_PREFIX = "zes_connector_transform";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesEnvironmentAnalysisService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    /**
     * Her environment tipinde kaç istasyon var.
     * Returns: { "RETAIL" → 450, "COMMERCIAL" → 230, … }
     */
    public Map<String, Long> envTypeDistribution() throws IOException {
        var response = client.search(s -> s
                .index(ENV_INDEX)
                .size(0)
                .aggregations("by_env", a -> a
                        .terms(t -> t.field("environment_type").size(10))
                ),
                Void.class
        );

        Map<String, Long> result = new LinkedHashMap<>();
        for (var bucket : response.aggregations().get("by_env").sterms().buckets().array()) {
            result.put(bucket.key().stringValue(), bucket.docCount());
        }
        return result;
    }

    /**
     * Environment tipi × time slot bazında connector durum sayıları.
     * Returns: { "RETAIL" → { "08:00-08:30" → { "Available" → 1200, "Charging" → 340 } } }
     *
     * Mantık: ZesConnectorTransformAnalysisService.statusByTimeSlot ile aynı slot hesabı,
     * ancak her connector'ın station_id'si üzerinden environment_type grubuna atanır.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Map<String, Long>>> statusByEnvAndTimeSlot(
            String date, int intervalMinutes, String connectorType, String province) throws IOException {

        Map<String, String> stationEnvMap = loadStationEnvMap();

        String indexName = TRANSFORM_INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
        Map<String, Map<String, Map<String, Long>>> result = new TreeMap<>();

        var firstResponse = client.search(s -> {
            s.index(indexName).size(1000).scroll(sc -> sc.time("2m"));
            if (connectorType != null || province != null) {
                s.query(q -> q.bool(b -> {
                    if (connectorType != null)
                        b.filter(f -> f.term(t -> t.field("connector_type").value(connectorType)));
                    if (province != null)
                        b.filter(f -> f.term(t -> t.field("province_name").value(province)));
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

                String stationId = String.valueOf(doc.get("station_id"));
                String envType   = stationEnvMap.getOrDefault(stationId, "UNKNOWN");

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

                    int firstSlot = (int) (Math.ceil((double) startMin / intervalMinutes) * intervalMinutes);

                    for (int T = firstSlot; T < endMin && T < 24 * 60; T += intervalMinutes) {
                        String slotLabel = formatSlot(T, intervalMinutes);
                        result.computeIfAbsent(envType, k -> new TreeMap<>())
                              .computeIfAbsent(slotLabel, k -> new LinkedHashMap<>())
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

    @SuppressWarnings("unchecked")
    public Map<String, String> loadStationEnvMap() throws IOException {
        Map<String, String> map = new HashMap<>();

        var firstResponse = client.search(s -> s
                .index(ENV_INDEX)
                .size(1000)
                .scroll(sc -> sc.time("2m")),
                Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();

        while (!hits.isEmpty()) {
            for (var hit : hits) {
                Map<String, Object> doc = (Map<String, Object>) hit.source();
                if (doc != null) {
                    map.put(String.valueOf(doc.get("station_id")),
                            String.valueOf(doc.get("environment_type")));
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

        return map;
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

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
