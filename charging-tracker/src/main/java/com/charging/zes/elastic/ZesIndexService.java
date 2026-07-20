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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ZesIndexService {

    private static final String INDEX_PREFIX   = "zes_stations";
    private static final String TEMPLATE_NAME  = "zes_stations_template";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public ZesIndexService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }

    @PostConstruct
    public void ensureTemplate() throws IOException {
        client.indices().putIndexTemplate(t -> t
                .name(TEMPLATE_NAME)
                .indexPatterns(INDEX_PREFIX + "-*")
                .template(tmpl -> tmpl
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("0")
                        )
                        .mappings(m -> m
                                .properties("id",                 p -> p.keyword(k -> k))
                                .properties("name",               p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("address",            p -> p.text(tx -> tx))
                                .properties("latitude",           p -> p.float_(f -> f))
                                .properties("longitude",          p -> p.float_(f -> f))
                                .properties("location",           p -> p.geoPoint(g -> g))
                                .properties("distance",           p -> p.float_(f -> f))
                                .properties("openingTime",        p -> p.keyword(k -> k))
                                .properties("closingTime",        p -> p.keyword(k -> k))
                                .properties("provinceId",         p -> p.keyword(k -> k))
                                .properties("provinceName",       p -> p.keyword(k -> k))
                                .properties("cityName",           p -> p.keyword(k -> k))
                                .properties("isRestricted",       p -> p.boolean_(b -> b))
                                .properties("isRoamingLocation",  p -> p.boolean_(b -> b))
                                .properties("roamingOperatorName",p -> p.keyword(k -> k))
                                .properties("hasGreenCertificate",p -> p.boolean_(b -> b))
                                .properties("fetchedAt",          p -> p.date(d -> d))
                                .properties("evses", p -> p.nested(n -> n
                                        .properties("id",                    ep -> ep.keyword(k -> k))
                                        .properties("name",                  ep -> ep.keyword(k -> k))
                                        .properties("locationDescription",   ep -> ep.text(tx -> tx))
                                        .properties("isAutoChargeSupported", ep -> ep.boolean_(b -> b))
                                        .properties("isRestricted",          ep -> ep.boolean_(b -> b))
                                        .properties("isRoamingEvse",         ep -> ep.boolean_(b -> b))
                                        .properties("connectors", ep -> ep.nested(cn -> cn
                                                .properties("id",               cp -> cp.keyword(k -> k))
                                                .properties("type",             cp -> cp.keyword(k -> k))
                                                .properties("connectorNo",      cp -> cp.integer(i -> i))
                                                .properties("connectorNoAlias", cp -> cp.keyword(k -> k))
                                                .properties("maxElectricPower", cp -> cp.integer(i -> i))
                                                .properties("status",           cp -> cp.keyword(k -> k))
                                                .properties("availability", cp -> cp.nested(av -> av
                                                        .properties("status",    ap -> ap.keyword(k -> k))
                                                        .properties("startTime", ap -> ap.date(d -> d))
                                                        .properties("endTime",   ap -> ap.date(d -> d))
                                                ))
                                        ))
                                ))
                        )
                )
        );
        System.out.println("[ES] ZES template upserted.");
    }

    public boolean todayIndexExists(String date) throws IOException {
        return client.indices().exists(r -> r.index(INDEX_PREFIX + "-" + date)).value();
    }

    public int bulkIndexDailyStations(List<Map<String, Object>> stations, String date) throws IOException {
        if (stations == null || stations.isEmpty()) return 0;

        String indexName = INDEX_PREFIX + "-" + date;

        boolean exists = client.indices().exists(r -> r.index(indexName)).value();
        if (!exists) {
            client.indices().create(r -> r.index(indexName));
        }

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> station : stations) {
            Map<String, Object> doc = new HashMap<>(station);
            doc.put("fetchedAt", date);

            Object lat = doc.get("latitude");
            Object lon = doc.get("longitude");
            if (lat != null && lon != null) {
                doc.put("location", Map.of("lat", lat, "lon", lon));
            }

            String docId = String.valueOf(doc.get("id"));
            bulk.operations(op -> op.index(idx -> idx.index(indexName).id(docId).document(doc)));
        }

        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            response.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(item -> System.err.printf("[BULK ERROR] id=%s reason=%s%n",
                            item.id(), item.error().reason()));
        }
        long indexed = response.items().stream().filter(i -> i.error() == null).count();
        System.out.printf("[ES] Indexed %d/%d stations to %s%n", indexed, stations.size(), indexName);

        client.indices().refresh(r -> r.index(indexName));

        return (int) indexed;
    }
}
