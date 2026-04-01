package com.charging.zes.elastic;

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

/**
 * Elasticsearch analysis queries for zes_stations-{date} indices.
 *
 * ZES nesting structure: evses (nested) → evses.connectors (nested) → evses.connectors.availability (nested)
 */
@Service
public class ZesAnalysisService {

    private static final String INDEX_PREFIX = "zes_stations";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public ZesAnalysisService() {
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

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchStations(
            String date,
            String province,
            String connectorType,
            String status,
            int from,
            int size,
            String sortField,
            String sortOrder) throws IOException {

        var response = client.search(s -> {
            s.index(indexForDate(date)).from(from).size(size);

            s.query(q -> q.bool(b -> {
                if (province != null) b.filter(f -> f.term(t -> t.field("provinceName").value(province)));

                if (connectorType != null || status != null) {
                    b.filter(f -> f.nested(n -> n
                        .path("evses")
                        .query(eq -> eq.nested(nn -> nn
                            .path("evses.connectors")
                            .query(cq -> cq.bool(cb -> {
                                if (connectorType != null)
                                    cb.filter(cf -> cf.term(t -> t.field("evses.connectors.type").value(connectorType)));
                                if (status != null)
                                    cb.filter(cf -> cf.nested(an -> an
                                        .path("evses.connectors.availability")
                                        .query(aq -> aq.term(t -> t
                                            .field("evses.connectors.availability.status")
                                            .value(status)))
                                    ));
                                return cb;
                            }))
                        ))
                    ));
                }
                return b;
            }));

            if (sortField != null) {
                SortOrder order = "desc".equalsIgnoreCase(sortOrder) ? SortOrder.Desc : SortOrder.Asc;
                String resolvedSort = resolveSort(sortField);
                s.sort(so -> so.field(f -> f.field(resolvedSort).order(order)));
            }

            return s;
        }, Map.class);

        return response.hits().hits().stream()
                .map(hit -> (Map<String, Object>) hit.source())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Aggregations
    // -------------------------------------------------------------------------

    /**
     * Connector status counts grouped by connector type (CCS, CHAdeMO, …).
     * Returns: { connectorType → { status → count } }
     *
     * ES: nested(evses) → nested(evses.connectors) → terms(type)
     *       → nested(evses.connectors.availability) → terms(status)
     */
    public Map<String, Map<String, Long>> statusByConnectorType(String date) throws IOException {
        var response = client.search(s -> s
            .index(indexForDate(date))
            .size(0)
            .aggregations("evses", a -> a
                .nested(n -> n.path("evses"))
                .aggregations("connectors", aa -> aa
                    .nested(n -> n.path("evses.connectors"))
                    .aggregations("by_type", aaa -> aaa
                        .terms(t -> t.field("evses.connectors.type").size(50))
                        .aggregations("availability", aaaa -> aaaa
                            .nested(n -> n.path("evses.connectors.availability"))
                            .aggregations("status_counts", aaaaa -> aaaaa
                                .terms(t -> t.field("evses.connectors.availability.status").size(20))
                            )
                        )
                    )
                )
            ),
            Void.class
        );

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        var connectors = response.aggregations().get("evses").nested()
                .aggregations().get("connectors").nested();
        var byType = connectors.aggregations().get("by_type").sterms();

        for (var bucket : byType.buckets().array()) {
            var avail = bucket.aggregations().get("availability").nested();
            var statusTerms = avail.aggregations().get("status_counts").sterms();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var sb : statusTerms.buckets().array()) {
                counts.put(sb.key().stringValue(), sb.docCount());
            }
            result.put(bucket.key().stringValue(), counts);
        }
        return result;
    }

    /**
     * Connector status counts grouped by province.
     * Returns: { provinceName → { status → count } }
     *
     * ES: terms(provinceName) → nested(evses) → nested(evses.connectors)
     *       → nested(evses.connectors.availability) → terms(status)
     */
    public Map<String, Map<String, Long>> statusByProvince(String date) throws IOException {
        var response = client.search(s -> s
            .index(indexForDate(date))
            .size(0)
            .aggregations("by_province", a -> a
                .terms(t -> t.field("provinceName").size(100))
                .aggregations("evses", aa -> aa
                    .nested(n -> n.path("evses"))
                    .aggregations("connectors", aaa -> aaa
                        .nested(n -> n.path("evses.connectors"))
                        .aggregations("availability", aaaa -> aaaa
                            .nested(n -> n.path("evses.connectors.availability"))
                            .aggregations("status_counts", aaaaa -> aaaaa
                                .terms(t -> t.field("evses.connectors.availability.status").size(20))
                            )
                        )
                    )
                )
            ),
            Void.class
        );

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        var byProvince = response.aggregations().get("by_province").sterms();

        for (var bucket : byProvince.buckets().array()) {
            var connectors = bucket.aggregations().get("evses").nested()
                    .aggregations().get("connectors").nested();
            var avail = connectors.aggregations().get("availability").nested();
            var statusTerms = avail.aggregations().get("status_counts").sterms();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (var sb : statusTerms.buckets().array()) {
                counts.put(sb.key().stringValue(), sb.docCount());
            }
            result.put(bucket.key().stringValue(), counts);
        }
        return result;
    }

    /**
     * Connector status counts over time buckets, based on availability.startTime.
     * Returns: { "HH:mm" → { status → count } }
     *
     * ES (no connectorType filter):
     *   nested(evses) → nested(evses.connectors) → nested(evses.connectors.availability)
     *     → dateHistogram(startTime) → terms(status)
     *
     * ES (with connectorType filter):
     *   nested(evses) → nested(evses.connectors) → filter(type=X)
     *     → nested(evses.connectors.availability) → dateHistogram(startTime) → terms(status)
     */
    public Map<String, Map<String, Long>> statusByTimeSlot(
            String date,
            int intervalMinutes,
            String province,
            String connectorType) throws IOException {

        String interval = intervalMinutes + "m";

        var response = client.search(s -> {
            s.index(indexForDate(date)).size(0);

            if (province != null)
                s.query(q -> q.term(t -> t.field("provinceName").value(province)));

            if (connectorType != null) {
                s.aggregations("evses", a -> a
                    .nested(n -> n.path("evses"))
                    .aggregations("connectors", aa -> aa
                        .nested(n -> n.path("evses.connectors"))
                        .aggregations("by_type", aaa -> aaa
                            .filter(f -> f.term(t -> t.field("evses.connectors.type").value(connectorType)))
                            .aggregations("availability", aaaa -> aaaa
                                .nested(n -> n.path("evses.connectors.availability"))
                                .aggregations("by_time", aaaaa -> aaaaa
                                    .dateHistogram(dh -> dh
                                        .field("evses.connectors.availability.startTime")
                                        .fixedInterval(fi -> fi.time(interval))
                                        .format("HH:mm")
                                    )
                                    .aggregations("status_counts", aaaaaa -> aaaaaa
                                        .terms(t -> t.field("evses.connectors.availability.status").size(20))
                                    )
                                )
                            )
                        )
                    )
                );
            } else {
                s.aggregations("evses", a -> a
                    .nested(n -> n.path("evses"))
                    .aggregations("connectors", aa -> aa
                        .nested(n -> n.path("evses.connectors"))
                        .aggregations("availability", aaa -> aaa
                            .nested(n -> n.path("evses.connectors.availability"))
                            .aggregations("by_time", aaaa -> aaaa
                                .dateHistogram(dh -> dh
                                    .field("evses.connectors.availability.startTime")
                                    .fixedInterval(fi -> fi.time(interval))
                                    .format("HH:mm")
                                )
                                .aggregations("status_counts", aaaaa -> aaaaa
                                    .terms(t -> t.field("evses.connectors.availability.status").size(20))
                                )
                            )
                        )
                    )
                );
            }
            return s;
        }, Void.class);

        var evsesNested = response.aggregations().get("evses").nested()
                .aggregations().get("connectors").nested();

        var availNested = connectorType != null
                ? evsesNested.aggregations().get("by_type").filter()
                             .aggregations().get("availability").nested()
                : evsesNested.aggregations().get("availability").nested();

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

    /** text alanlar sort için .keyword sub-field'ı gerektirir. */
    private String resolveSort(String field) {
        return switch (field) {
            case "name"    -> "name.keyword";
            case "address" -> "address.keyword";
            default        -> field;
        };
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
