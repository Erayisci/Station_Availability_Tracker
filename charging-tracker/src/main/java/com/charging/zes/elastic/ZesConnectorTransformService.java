package com.charging.zes.elastic;

import com.charging.elastic.EsConfig;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ZesConnectorTransformService {

    private static final String SOURCE_INDEX_PREFIX = "zes_stations";
    private static final String TARGET_INDEX_PREFIX = "zes_connector_transform";
    private static final String TEMPLATE_NAME       = "zes_connector_transform_template";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public ZesConnectorTransformService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    @PostConstruct
    public void ensureTemplate() throws IOException {
        client.indices().putIndexTemplate(t -> t
                .name(TEMPLATE_NAME)
                .indexPatterns(TARGET_INDEX_PREFIX + "-*")
                .template(tmpl -> tmpl
                        .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                        .mappings(m -> m
                                .properties("connector_id",       p -> p.keyword(k -> k))
                                .properties("connector_type",     p -> p.keyword(k -> k))
                                .properties("connector_no",       p -> p.integer(i -> i))
                                .properties("max_power_kw",       p -> p.integer(i -> i))
                                .properties("station_id",         p -> p.keyword(k -> k))
                                .properties("station_name",       p -> p.keyword(k -> k))
                                .properties("address",            p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("city_name",          p -> p.keyword(k -> k))
                                .properties("province_name",      p -> p.keyword(k -> k))
                                .properties("lat",                p -> p.float_(f -> f))
                                .properties("lng",                p -> p.float_(f -> f))
                                .properties("location",           p -> p.geoPoint(g -> g))
                                .properties("distance",           p -> p.float_(f -> f))
                                .properties("opening_time",       p -> p.keyword(k -> k))
                                .properties("closing_time",       p -> p.keyword(k -> k))
                                .properties("is_restricted",      p -> p.boolean_(b -> b))
                                .properties("is_roaming",         p -> p.boolean_(b -> b))
                                .properties("evse_id",            p -> p.keyword(k -> k))
                                .properties("evse_location_desc", p -> p.text(tx -> tx))
                                .properties("is_autocharge",      p -> p.boolean_(b -> b))
                                .properties("fetched_date",       p -> p.date(d -> d))
                                .properties("availability", p -> p.nested(n -> n
                                        .properties("status",           ap -> ap.keyword(k -> k))
                                        .properties("start_time",       ap -> ap.date(d -> d))
                                        .properties("end_time",         ap -> ap.date(d -> d))
                                        .properties("duration_minutes", ap -> ap.float_(f -> f))
                                        .properties("time_slot",        ap -> ap.keyword(k -> k))
                                        .properties("is_available",     ap -> ap.integer(i -> i))
                                ))
                        )
                )
        );
        System.out.println("[ES] Template '" + TEMPLATE_NAME + "' created.");
    }

    /**
     * Reads every station document from zes_stations-{date}, extracts each connector
     * with its availability array, and bulk-indexes into zes_connector_transform-{date}.
     * doc_id = connector_id (idempotent: safe to call multiple times).
     */
    @SuppressWarnings("unchecked")
    public int transformConnectors(String date) throws IOException {
        String sourceIndex = SOURCE_INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
        String targetIndex = TARGET_INDEX_PREFIX  + "-" + (date != null ? date : LocalDate.now().toString());

        long sourceCount = client.count(c -> c.index(sourceIndex)).count();
        System.out.printf("[TRANSFORM] %s (%d station docs) -> %s%n", sourceIndex, sourceCount, targetIndex);

        if (client.indices().exists(r -> r.index(targetIndex)).value()) {
            client.indices().delete(r -> r.index(targetIndex));
        }
        client.indices().create(r -> r.index(targetIndex));

        int totalConnectors = 0;
        int processed = 0;

        var firstResponse = client.search(s -> s
                        .index(sourceIndex)
                        .size(500)
                        .scroll(sc -> sc.time("2m")),
                Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();

        while (!hits.isEmpty()) {
            List<Map<String, Object>> connectorDocs = new ArrayList<>();
            for (var hit : hits) {
                connectorDocs.addAll(extractConnectors((Map<String, Object>) hit.source()));
            }
            if (!connectorDocs.isEmpty()) {
                bulkIndex(targetIndex, connectorDocs);
                totalConnectors += connectorDocs.size();
            }
            processed += hits.size();
            System.out.printf("[TRANSFORM] %d/%d stations, %d connectors so far%n", processed, sourceCount, totalConnectors);

            String sid = scrollId;
            var next = client.scroll(r -> r.scrollId(sid).scroll(sc -> sc.time("2m")), Map.class);
            scrollId = next.scrollId();
            hits = next.hits().hits();
        }

        if (scrollId != null) {
            String sid = scrollId;
            client.clearScroll(r -> r.scrollId(List.of(sid)));
        }

        System.out.printf("[TRANSFORM] Done: %d connector docs in %s%n", totalConnectors, targetIndex);
        return totalConnectors;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractConnectors(Map<String, Object> stationDoc) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (stationDoc == null) return result;

        String stationId    = String.valueOf(stationDoc.get("id"));
        String stationName  = (String) stationDoc.get("name");
        String address      = (String) stationDoc.get("address");
        String cityName     = (String) stationDoc.get("cityName");
        String provinceName = (String) stationDoc.get("provinceName");
        Object lat          = stationDoc.get("latitude");
        Object lng          = stationDoc.get("longitude");
        Object distance     = stationDoc.get("distance");
        String openingTime  = (String) stationDoc.get("openingTime");
        String closingTime  = (String) stationDoc.get("closingTime");
        Object isRestricted = stationDoc.get("isRestricted");
        Object isRoaming    = stationDoc.get("isRoamingLocation");
        String fetchedDate  = (String) stationDoc.get("fetchedAt");

        List<Map<String, Object>> evses = (List<Map<String, Object>>) stationDoc.getOrDefault("evses", List.of());
        for (Map<String, Object> evse : evses) {
            String evseId       = String.valueOf(evse.get("id"));
            String evseLocDesc  = (String) evse.get("locationDescription");
            Object isAutocharge = evse.get("isAutoChargeSupported");

            List<Map<String, Object>> connectors = (List<Map<String, Object>>) evse.getOrDefault("connectors", List.of());
            for (Map<String, Object> connector : connectors) {
                String connectorId   = String.valueOf(connector.get("id"));
                String connectorType = (String) connector.get("type");
                Object connectorNo   = connector.get("connectorNo");
                Object maxPower      = connector.get("maxElectricPower");

                List<Map<String, Object>> rawAvail = (List<Map<String, Object>>) connector.getOrDefault("availability", List.of());
                List<Map<String, Object>> availability = new ArrayList<>();
                for (Map<String, Object> avail : rawAvail) {
                    availability.add(enrichAvailability(avail));
                }

                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("connector_id",       connectorId);
                doc.put("connector_type",     connectorType);
                doc.put("connector_no",       connectorNo);
                doc.put("max_power_kw",       maxPower);
                doc.put("station_id",         stationId);
                doc.put("station_name",       stationName);
                doc.put("address",            address);
                doc.put("city_name",          cityName);
                doc.put("province_name",      provinceName);
                doc.put("lat",                lat);
                doc.put("lng",                lng);
                if (lat != null && lng != null) {
                    doc.put("location", Map.of("lat", lat, "lon", lng));
                }
                doc.put("distance",           distance);
                doc.put("opening_time",       openingTime);
                doc.put("closing_time",       closingTime);
                doc.put("is_restricted",      isRestricted);
                doc.put("is_roaming",         isRoaming);
                doc.put("evse_id",            evseId);
                doc.put("evse_location_desc", evseLocDesc);
                doc.put("is_autocharge",      isAutocharge);
                doc.put("fetched_date",       fetchedDate);
                doc.put("availability",       availability);

                result.add(doc);
            }
        }
        return result;
    }

    private Map<String, Object> enrichAvailability(Map<String, Object> avail) {
        Map<String, Object> enriched = new LinkedHashMap<>(avail);

        String startTimeStr = (String) avail.get("startTime");
        String endTimeStr   = (String) avail.get("endTime");
        String status       = (String) avail.get("status");

        enriched.remove("startTime");
        enriched.remove("endTime");
        enriched.put("start_time", startTimeStr);
        enriched.put("end_time",   endTimeStr);

        String timeSlot        = null;
        Double durationMinutes = null;

        if (startTimeStr != null) {
            try {
                LocalDateTime start = LocalDateTime.parse(startTimeStr);
                int hour   = start.getHour();
                int minute = start.getMinute();
                if (minute < 30) {
                    timeSlot = String.format("%02d:00-%02d:30", hour, hour);
                } else {
                    int nextHour = (hour + 1) % 24;
                    timeSlot = String.format("%02d:30-%02d:00", hour, nextHour);
                }
                if (endTimeStr != null) {
                    LocalDateTime end = LocalDateTime.parse(endTimeStr);
                    durationMinutes = (double) ChronoUnit.SECONDS.between(start, end) / 60.0;
                }
            } catch (Exception ignored) {}
        }

        enriched.put("time_slot",        timeSlot);
        enriched.put("duration_minutes", durationMinutes);
        enriched.put("is_available",     "Available".equals(status) ? 1 : 0);

        return enriched;
    }

    private void bulkIndex(String targetIndex, List<Map<String, Object>> docs) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> doc : docs) {
            String connectorId = String.valueOf(doc.get("connector_id"));
            bulk.operations(op -> op.index(idx -> idx.index(targetIndex).id(connectorId).document(doc)));
        }
        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            System.err.println("[WARN] Some errors during connector transform bulk indexing.");
        }
    }

    /**
     * Checks if the connector_transform index exists for a given date.
     *
     * @param date Date string (YYYY-MM-DD) or null for today
     * @return true if index exists, false otherwise
     */
    public boolean indexExists(String date) throws IOException {
        String indexName = TARGET_INDEX_PREFIX + "-" +
            (date != null ? date : LocalDate.now().toString());
        return client.indices().exists(r -> r.index(indexName)).value();
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
