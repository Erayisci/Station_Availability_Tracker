package com.charging.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AvailabilityAnalysisService {

    private static final String INDEX_PREFIX = "station_availability";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public AvailabilityAnalysisService() throws Exception {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    private String indexForDate(String date) {
        return INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Search stations in the availability index with all filtering and sorting done in ES.
     *
     * Filters (all optional):
     *   date        – target daily index (default: today)
     *   districtId  – exact match on districtid keyword
     *   cityId      – exact match on cityid keyword
     *   brand       – exact match on brand keyword
     *   socketType  – filters inside sockets nested: sockets.type
     *   status      – filters inside sockets.availability nested: sockets.availability.status
     *
     * Sorting:
     *   sortField / sortOrder – any top-level sortable field (id, brand, districtid, fetchedDate, …)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchStations(
            String date,
            String districtId,
            String cityId,
            String brand,
            String socketType,
            String status,
            int from,
            int size,
            String sortField,
            String sortOrder) throws IOException {

        var response = client.search(s -> {
            s.index(indexForDate(date)).from(from).size(size);

            // All filtering happens in ES via bool/term/nested queries
            s.query(q -> q.bool(b -> {
                if (districtId != null) b.filter(f -> f.term(t -> t.field("districtid").value(districtId)));
                if (cityId     != null) b.filter(f -> f.term(t -> t.field("cityid").value(cityId)));
                if (brand      != null) b.filter(f -> f.term(t -> t.field("brand").value(brand)));

                // Nested filter: sockets.type and/or sockets.availability.status
                if (socketType != null || status != null) {
                    b.filter(f -> f.nested(n -> n
                        .path("sockets")
                        .query(nq -> nq.bool(nb -> {
                            if (socketType != null)
                                nb.filter(nf -> nf.term(t -> t.field("sockets.type").value(socketType)));
                            // Double-nested: sockets.availability.status
                            if (status != null)
                                nb.filter(nf -> nf.nested(nn -> nn
                                    .path("sockets.availability")
                                    .query(aq -> aq.term(t -> t
                                        .field("sockets.availability.status")
                                        .value(status)))
                                ));
                            return nb;
                        }))
                    ));
                }
                return b;
            }));

            // Sorting in ES
            SortOrder order = "desc".equalsIgnoreCase(sortOrder) ? SortOrder.Desc : SortOrder.Asc;
            s.sort(so -> so.field(f -> f
                .field(sortField != null ? sortField : "id")
                .order(order)));

            return s;
        }, Map.class);

        return response.hits().hits().stream()
                .map(hit -> (Map<String, Object>) hit.source())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Aggregations — all counting/grouping done in ES, no post-processing
    // -------------------------------------------------------------------------

    /**
     * Socket status counts grouped by district.
     * Returns: { districtId → { status → socketCount } }
     *
     * ES structure: terms(districtid) → nested(sockets) → nested(sockets.availability) → terms(status)
     */
    public Map<String, Map<String, Long>> statusCountsByDistrict(String date) throws IOException {
        return statusCountsByTopLevelField(date, "districtid");
    }

    /**
     * Socket status counts grouped by brand.
     * Returns: { brand → { status → socketCount } }
     */
    public Map<String, Map<String, Long>> statusCountsByBrand(String date) throws IOException {
        return statusCountsByTopLevelField(date, "brand");
    }

    /**
     * Socket status counts grouped by socket connector type.
     * Returns: { socketType → { status → socketCount } }
     *
     * ES structure: nested(sockets) → terms(sockets.type) → nested(sockets.availability) → terms(status)
     */
    public Map<String, Map<String, Long>> statusCountsBySocketType(String date) throws IOException {
        var response = client.search(s -> s
            .index(indexForDate(date))
            .size(0)
            .aggregations("sockets", a -> a
                .nested(n -> n.path("sockets"))
                .aggregations("by_type", aa -> aa
                    .terms(t -> t.field("sockets.type").size(50))
                    .aggregations("availability", aaa -> aaa
                        .nested(n -> n.path("sockets.availability"))
                        .aggregations("status_counts", aaaa -> aaaa
                            .terms(t -> t.field("sockets.availability.status").size(20))
                        )
                    )
                )
            ),
            Void.class
        );

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        var socketsNested = response.aggregations().get("sockets").nested();
        var byType = socketsNested.aggregations().get("by_type").sterms();

        for (var bucket : byType.buckets().array()) {
            var availNested = bucket.aggregations().get("availability").nested();
            var statusTerms = availNested.aggregations().get("status_counts").sterms();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var sb : statusTerms.buckets().array()) {
                counts.put(sb.key().stringValue(), sb.docCount());
            }
            result.put(bucket.key().stringValue(), counts);
        }
        return result;
    }

    /**
     * Socket status counts grouped by city.
     * Returns: { cityId → { status → socketCount } }
     */
    public Map<String, Map<String, Long>> statusCountsByCity(String date) throws IOException {
        return statusCountsByTopLevelField(date, "cityid");
    }

    // -------------------------------------------------------------------------
    // Time-slot analysis
    // -------------------------------------------------------------------------

    /**
     * Distributes socket status counts into fixed time buckets based on
     * sockets.availability.startTime. All aggregation runs in ES.
     *
     * @param date            daily index date (default: today)
     * @param intervalMinutes bucket size in minutes (e.g. 30 → half-hour slots)
     * @param districtId      optional: filter stations by districtid
     * @param socketType      optional: filter within sockets by sockets.type
     * @return { "08:00" → { "AVAILABLE" → 450, "IN_USE" → 32 }, "08:30" → … }
     *
     * ES structure (without socketType filter):
     *   nested(sockets)
     *     → nested(sockets.availability)
     *         → date_histogram(startTime, interval)
     *             → terms(status)
     *
     * ES structure (with socketType filter):
     *   nested(sockets)
     *     → filter(sockets.type = X)          ← narrows to matching connector type
     *         → nested(sockets.availability)
     *             → date_histogram(startTime, interval)
     *                 → terms(status)
     */
    public Map<String, Map<String, Long>> statusByTimeSlot(
            String date,
            int intervalMinutes,
            String districtId,
            String socketType) throws IOException {

        String interval = intervalMinutes + "m";

        var response = client.search(s -> {
            s.index(indexForDate(date)).size(0);

            // Top-level district filter (optional) — aggregations automatically respect it
            if (districtId != null)
                s.query(q -> q.term(t -> t.field("districtid").value(districtId)));

            if (socketType != null) {
                // With connector-type filter: inject a filter aggregation between the two nested levels
                s.aggregations("sockets", a -> a
                    .nested(n -> n.path("sockets"))
                    .aggregations("by_type", fa -> fa
                        .filter(f -> f.term(t -> t.field("sockets.type").value(socketType)))
                        .aggregations("availability", aa -> aa
                            .nested(n -> n.path("sockets.availability"))
                            .aggregations("by_time", aaa -> aaa
                                .dateHistogram(dh -> dh
                                    .field("sockets.availability.startTime")
                                    .fixedInterval(fi -> fi.time(interval))
                                    .format("HH:mm")
                                )
                                .aggregations("status_counts", aaaa -> aaaa
                                    .terms(t -> t.field("sockets.availability.status").size(20))
                                )
                            )
                        )
                    )
                );
            } else {
                s.aggregations("sockets", a -> a
                    .nested(n -> n.path("sockets"))
                    .aggregations("availability", aa -> aa
                        .nested(n -> n.path("sockets.availability"))
                        .aggregations("by_time", aaa -> aaa
                            .dateHistogram(dh -> dh
                                .field("sockets.availability.startTime")
                                .fixedInterval(fi -> fi.time(interval))
                                .format("HH:mm")
                            )
                            .aggregations("status_counts", aaaa -> aaaa
                                .terms(t -> t.field("sockets.availability.status").size(20))
                            )
                        )
                    )
                );
            }
            return s;
        }, Void.class);

        // Navigate the aggregation tree (path differs when socketType filter is applied)
        var socketsNested = response.aggregations().get("sockets").nested();
        var availNested   = socketType != null
            ? socketsNested.aggregations().get("by_type").filter()
                           .aggregations().get("availability").nested()
            : socketsNested.aggregations().get("availability").nested();

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (var bucket : availNested.aggregations().get("by_time").dateHistogram().buckets().array()) {
            var statusTerms = bucket.aggregations().get("status_counts").sterms();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var sb : statusTerms.buckets().array()) {
                counts.put(sb.key().stringValue(), sb.docCount());
            }
            if (!counts.isEmpty()) result.put(bucket.keyAsString(), counts);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Generic aggregation: group stations by a top-level keyword field, then drill
     * into nested sockets → availability → status counts.
     * All aggregation logic runs in ES.
     */
    private Map<String, Map<String, Long>> statusCountsByTopLevelField(String date, String field) throws IOException {
        var response = client.search(s -> s
            .index(indexForDate(date))
            .size(0)
            .aggregations("by_field", a -> a
                .terms(t -> t.field(field).size(1000))
                .aggregations("sockets", sa -> sa
                    .nested(n -> n.path("sockets"))
                    .aggregations("availability", aa -> aa
                        .nested(n -> n.path("sockets.availability"))
                        .aggregations("status_counts", aaa -> aaa
                            .terms(t -> t.field("sockets.availability.status").size(20))
                        )
                    )
                )
            ),
            Void.class
        );

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        var byField = response.aggregations().get("by_field").sterms();

        for (var bucket : byField.buckets().array()) {
            var socketsNested = bucket.aggregations().get("sockets").nested();
            var availNested   = socketsNested.aggregations().get("availability").nested();
            var statusTerms   = availNested.aggregations().get("status_counts").sterms();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var sb : statusTerms.buckets().array()) {
                counts.put(sb.key().stringValue(), sb.docCount());
            }
            result.put(bucket.key().stringValue(), counts);
        }
        return result;
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
