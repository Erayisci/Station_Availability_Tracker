package com.charging.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.ExistsIndexTemplateRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SocketTransformService {

    private static final String AVAILABILITY_INDEX_PREFIX   = "station_availability";
    private static final String SOCKET_TRANSFORM_PREFIX     = "socket_transform";
    private static final String SOCKET_TRANSFORM_TEMPLATE   = "socket_transform_template";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public SocketTransformService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    // -------------------------------------------------------------------------
    // Template
    // -------------------------------------------------------------------------

    @PostConstruct
    public void ensureSocketTransformTemplate() throws IOException {
        boolean exists = client.indices()
                .existsIndexTemplate(ExistsIndexTemplateRequest.of(r -> r.name(SOCKET_TRANSFORM_TEMPLATE)))
                .value();

        if (exists) {
            System.out.println("[ES] Template '" + SOCKET_TRANSFORM_TEMPLATE + "' already exists, skipping.");
            return;
        }

        client.indices().putIndexTemplate(t -> t
                .name(SOCKET_TRANSFORM_TEMPLATE)
                .indexPatterns(SOCKET_TRANSFORM_PREFIX + "-*")
                .template(tmpl -> tmpl
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("0")
                        )
                        .mappings(m -> m
                                .properties("station_id",    p -> p.long_(l -> l))
                                .properties("brand",         p -> p.keyword(k -> k))
                                .properties("cityid",        p -> p.keyword(k -> k))
                                .properties("districtid",    p -> p.keyword(k -> k))
                                .properties("lat",           p -> p.float_(f -> f))
                                .properties("lng",           p -> p.float_(f -> f))
                                .properties("fetched_date",  p -> p.date(d -> d))
                                .properties("socket_id",     p -> p.long_(l -> l))
                                .properties("socket_type",   p -> p.keyword(k -> k))
                                .properties("socket_power",  p -> p.float_(f -> f))
                                .properties("socket_price",  p -> p.float_(f -> f))
                                .properties("socket_number", p -> p.keyword(k -> k))
                                .properties("status",        p -> p.keyword(k -> k))
                                .properties("start_time",    p -> p.date(d -> d))
                                .properties("end_time",      p -> p.date(d -> d))
                                .properties("active",        p -> p.integer(i -> i))
                        )
                )
        );

        System.out.println("[ES] Template '" + SOCKET_TRANSFORM_TEMPLATE + "' created.");
    }

    // -------------------------------------------------------------------------
    // Transform
    // -------------------------------------------------------------------------

    /**
     * Reads every document from the daily availability index, flattens each
     * station → sockets[] → availability[] into one flat document per row,
     * and bulk-indexes into socket_transform-{date}.
     *
     * The target index is deleted and recreated on each call for idempotency.
     */
    @SuppressWarnings("unchecked")
    public int transformAvailabilityToSocketIndex(String date) throws IOException {
        String sourceIndex = AVAILABILITY_INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
        String targetIndex = SOCKET_TRANSFORM_PREFIX    + "-" + (date != null ? date : LocalDate.now().toString());

        long sourceCount = client.count(c -> c.index(sourceIndex)).count();
        System.out.printf("[TRANSFORM] %s (%d station docs) -> %s%n", sourceIndex, sourceCount, targetIndex);

        if (client.indices().exists(r -> r.index(targetIndex)).value()) {
            client.indices().delete(r -> r.index(targetIndex));
        }
        client.indices().create(r -> r.index(targetIndex));

        int totalFlat = 0;
        int processed = 0;

        var firstResponse = client.search(s -> s
                .index(sourceIndex)
                .size(500)
                .scroll(sc -> sc.time("2m")),
                Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();

        while (!hits.isEmpty()) {
            List<Map<String, Object>> flatDocs = new ArrayList<>();
            for (var hit : hits) {
                flatDocs.addAll(flatten((Map<String, Object>) hit.source()));
            }
            if (!flatDocs.isEmpty()) {
                bulkIndex(targetIndex, flatDocs);
                totalFlat += flatDocs.size();
            }
            processed += hits.size();
            System.out.printf("[TRANSFORM] %d/%d stations, %d flat docs so far%n", processed, sourceCount, totalFlat);

            String sid = scrollId;
            var next = client.scroll(r -> r.scrollId(sid).scroll(sc -> sc.time("2m")), Map.class);
            scrollId = next.scrollId();
            hits = next.hits().hits();
        }

        if (scrollId != null) {
            String sid = scrollId;
            client.clearScroll(r -> r.scrollId(List.of(sid)));
        }

        System.out.printf("[TRANSFORM] Done: %d flat socket records in %s%n", totalFlat, targetIndex);
        return totalFlat;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> flatten(Map<String, Object> stationDoc) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (stationDoc == null) return result;

        Number stationId   = (Number) stationDoc.get("id");
        String brand       = (String) stationDoc.get("brand");
        String cityid      = (String) stationDoc.get("cityid");
        String districtid  = (String) stationDoc.get("districtid");
        Object lat         = stationDoc.get("lat");
        Object lng         = stationDoc.get("lng");
        String fetchedDate = (String) stationDoc.get("fetchedDate");

        List<Map<String, Object>> sockets = (List<Map<String, Object>>) stationDoc.get("sockets");
        if (sockets == null) return result;

        for (Map<String, Object> socket : sockets) {
            Number socketId     = (Number) socket.get("id");
            String socketType   = (String) socket.get("type");
            Object socketPower  = socket.get("power");
            Object socketPrice  = socket.get("price");
            String socketNumber = (String) socket.get("socketNumber");

            List<Map<String, Object>> availList = (List<Map<String, Object>>) socket.get("availability");
            if (availList == null || availList.isEmpty()) {
                result.add(base(stationId, brand, cityid, districtid, lat, lng, fetchedDate,
                        socketId, socketType, socketPower, socketPrice, socketNumber));
                continue;
            }

            for (Map<String, Object> avail : availList) {
                Map<String, Object> flat = base(stationId, brand, cityid, districtid, lat, lng, fetchedDate,
                        socketId, socketType, socketPower, socketPrice, socketNumber);
                flat.put("status",     avail.get("status"));
                flat.put("start_time", avail.get("startTime"));
                flat.put("end_time",   avail.get("endTime"));
                flat.put("active",     avail.get("active"));
                result.add(flat);
            }
        }
        return result;
    }

    private Map<String, Object> base(Number stationId, String brand, String cityid, String districtid,
                                     Object lat, Object lng, String fetchedDate,
                                     Number socketId, String socketType,
                                     Object socketPower, Object socketPrice, String socketNumber) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("station_id",    stationId);
        flat.put("brand",         brand);
        flat.put("cityid",        cityid);
        flat.put("districtid",    districtid);
        flat.put("lat",           lat);
        flat.put("lng",           lng);
        flat.put("fetched_date",  fetchedDate);
        flat.put("socket_id",     socketId);
        flat.put("socket_type",   socketType);
        flat.put("socket_power",  socketPower);
        flat.put("socket_price",  socketPrice);
        flat.put("socket_number", socketNumber);
        return flat;
    }

    private void bulkIndex(String targetIndex, List<Map<String, Object>> docs) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> doc : docs) {
            bulk.operations(op -> op.index(idx -> idx.index(targetIndex).document(doc)));
        }
        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            System.err.println("[WARN] Some errors during socket transform bulk indexing.");
        }
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
