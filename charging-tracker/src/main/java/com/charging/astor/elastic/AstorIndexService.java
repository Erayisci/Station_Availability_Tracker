package com.charging.astor.elastic;

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
public class AstorIndexService {

    private static final String INDEX_PREFIX  = "astor_stations";
    private static final String TEMPLATE_NAME = "astor_stations_template";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public AstorIndexService() {
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
                                .properties("id",                     p -> p.keyword(k -> k))
                                .properties("unit_name",              p -> p.keyword(k -> k))
                                .properties("address",                p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("tenant_code",            p -> p.keyword(k -> k))
                                .properties("location_code",          p -> p.keyword(k -> k))
                                .properties("connector_current_type", p -> p.keyword(k -> k))
                                .properties("max_power",              p -> p.integer(i -> i))
                                .properties("vendor_code",            p -> p.keyword(k -> k))
                                .properties("opens_at",               p -> p.keyword(k -> k))
                                .properties("closes_at",              p -> p.keyword(k -> k))
                                .properties("last_communication",     p -> p.date(d -> d))
                                .properties("lat",                    p -> p.float_(f -> f))
                                .properties("lng",                    p -> p.float_(f -> f))
                                .properties("location",               p -> p.geoPoint(g -> g))
                                .properties("fetched_date",           p -> p.date(d -> d))
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
        System.out.println("[ES] ASTOR template upserted.");
    }

    public boolean todayIndexExists(String date) throws IOException {
        return client.indices().exists(r -> r.index(INDEX_PREFIX + "-" + date)).value();
    }

    public int bulkIndex(List<Map<String, Object>> stations, String date) throws IOException {
        if (stations == null || stations.isEmpty()) return 0;

        String indexName = INDEX_PREFIX + "-" + date;

        if (!client.indices().exists(r -> r.index(indexName)).value()) {
            client.indices().create(r -> r.index(indexName));
        }

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> station : stations) {
            Map<String, Object> doc = new HashMap<>(station);
            String docId = String.valueOf(doc.get("id"));
            bulk.operations(op -> op.index(idx -> idx.index(indexName).id(docId).document(doc)));
        }

        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            response.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(item -> System.err.printf("[ASTOR BULK ERROR] id=%s reason=%s%n",
                            item.id(), item.error().reason()));
        }

        long indexed = response.items().stream().filter(i -> i.error() == null).count();
        System.out.printf("[ES] ASTOR: indexed %d/%d stations to %s%n", indexed, stations.size(), indexName);

        client.indices().refresh(r -> r.index(indexName));
        return (int) indexed;
    }
}
