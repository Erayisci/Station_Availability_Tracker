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
    // Note: Aggregation methods removed - use ZesConnectorTransformAnalysisService instead
    // -------------------------------------------------------------------------

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
