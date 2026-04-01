package com.charging.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class IndexInitService {

    private static final String STATIONS_INDEX           = "stations";
    private static final String STATIONS_BACKING_INDEX   = "stations_v2";
    private static final String STATIONS_TEMPLATE_NAME   = "stations_template";
    private static final String AVAILABILITY_INDEX_PREFIX = "station_availability";
    private static final String AVAILABILITY_TEMPLATE_NAME = "station_availability_template";

    private final ElasticsearchClient client;
    private final RestClient restClient;

    public IndexInitService() {
        restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    private String todayAvailabilityIndex() {
        return AVAILABILITY_INDEX_PREFIX + "-" + LocalDate.now();
    }

    // -------------------------------------------------------------------------
    // Template setup (creates only if not exists)
    // -------------------------------------------------------------------------

    @PostConstruct
    public void ensureTemplates() throws IOException {
        ensureStationsTemplate();
        ensureAvailabilityTemplate();
    }

    public void ensureStationsTemplate() throws IOException {
        boolean exists = client.indices()
                .existsIndexTemplate(ExistsIndexTemplateRequest.of(r -> r.name(STATIONS_TEMPLATE_NAME)))
                .value();

        if (exists) {
            System.out.println("[ES] Template '" + STATIONS_TEMPLATE_NAME + "' already exists, skipping.");
            return;
        }

        client.indices().putIndexTemplate(t -> t
                .name(STATIONS_TEMPLATE_NAME)
                .indexPatterns(STATIONS_INDEX + "*")
                .template(tmpl -> tmpl
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("0")
                        )
                        .mappings(m -> m
                                .properties("id",        p -> p.long_(l -> l))
                                .properties("lat",       p -> p.float_(f -> f))
                                .properties("lng",       p -> p.float_(f -> f))
                                .properties("green",     p -> p.keyword(k -> k))
                                .properties("brand",     p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("title",     p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("available", p -> p.boolean_(b -> b))
                                .properties("sockets",   p -> p.nested(n -> n
                                        .properties("id", sp -> sp.long_(l -> l))
                                ))
                        )
                )
        );

        System.out.println("[ES] Template '" + STATIONS_TEMPLATE_NAME + "' created.");
    }

    

    public void ensureAvailabilityTemplate() throws IOException {
        boolean exists = client.indices()
                .existsIndexTemplate(ExistsIndexTemplateRequest.of(r -> r.name(AVAILABILITY_TEMPLATE_NAME)))
                .value();

        if (exists) {
            System.out.println("[ES] Template '" + AVAILABILITY_TEMPLATE_NAME + "' already exists, skipping.");
            return;
        }

        client.indices().putIndexTemplate(t -> t
                .name(AVAILABILITY_TEMPLATE_NAME)
                .indexPatterns(AVAILABILITY_INDEX_PREFIX + "-*")
                .template(tmpl -> tmpl
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("0")
                        )
                        .mappings(m -> m
                                .properties("id",             p -> p.long_(l -> l))
                                .properties("title",          p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("address",        p -> p.text(tx -> tx))
                                .properties("lat",            p -> p.float_(f -> f))
                                .properties("lng",            p -> p.float_(f -> f))
                                .properties("phone",          p -> p.keyword(k -> k))
                                .properties("reportUrl",      p -> p.keyword(k -> k))
                                .properties("reservationUrl", p -> p.keyword(k -> k))
                                .properties("operatorid",     p -> p.keyword(k -> k))
                                .properties("operatortitle",  p -> p.text(tx -> tx.fields("keyword", f -> f.keyword(k -> k))))
                                .properties("licenceActive",  p -> p.boolean_(b -> b))
                                .properties("licenceStatus",  p -> p.integer(i -> i))
                                .properties("stationActive",  p -> p.boolean_(b -> b))
                                .properties("serviceType",    p -> p.keyword(k -> k))
                                .properties("green",          p -> p.keyword(k -> k))
                                .properties("brand",          p -> p.keyword(k -> k))
                                .properties("cityid",         p -> p.keyword(k -> k))
                                .properties("districtid",     p -> p.keyword(k -> k))
                                .properties("fetchedDate",    p -> p.date(d -> d))
                                .properties("sockets", p -> p.nested(n -> n
                                        .properties("id",           sp -> sp.long_(l -> l))
                                        .properties("type",         sp -> sp.keyword(k -> k))
                                        .properties("subType",      sp -> sp.keyword(k -> k))
                                        .properties("socketNumber", sp -> sp.keyword(k -> k))
                                        .properties("price",        sp -> sp.float_(f -> f))
                                        .properties("power",        sp -> sp.float_(f -> f))
                                        .properties("prices", sp -> sp.nested(pn -> pn
                                                .properties("active",    pp -> pp.integer(i -> i))
                                                .properties("price",     pp -> pp.float_(f -> f))
                                                .properties("startTime", pp -> pp.date(d -> d))
                                                .properties("endTime",   pp -> pp.date(d -> d))
                                        ))
                                        .properties("availability", sp -> sp.nested(an -> an
                                                .properties("id",            ap -> ap.long_(l -> l))
                                                .properties("active",        ap -> ap.integer(i -> i))
                                                .properties("status",        ap -> ap.keyword(k -> k))
                                                .properties("startTime",     ap -> ap.date(d -> d))
                                                .properties("endTime",       ap -> ap.date(d -> d))
                                                .properties("reservationid", ap -> ap.long_(l -> l))
                                        ))
                                ))
                                .properties("paymentTypes", p -> p.nested(n -> n
                                        .properties("name", np -> np.keyword(k -> k))
                                ))
                        )
                )
        );

        System.out.println("[ES] Template '" + AVAILABILITY_TEMPLATE_NAME + "' created.");
    }

    // -------------------------------------------------------------------------
    // Index creation
    // -------------------------------------------------------------------------

    public String createStationsIndex() throws IOException {
        boolean exists = client.indices().exists(r -> r.index(STATIONS_INDEX)).value();
        if (exists) {
            return "Stations index already exists: " + STATIONS_INDEX;
        }
        client.indices().create(r -> r
                .index(STATIONS_INDEX)
                .settings(s -> s.maxResultWindow(20000))
        );
        return "Stations index created: " + STATIONS_INDEX;
    }

    public String createTodayAvailabilityIndex() throws IOException {
        String index = todayAvailabilityIndex();
        boolean exists = client.indices().exists(r -> r.index(index)).value();
        if (exists) {
            return "Availability index already exists: " + index;
        }
        client.indices().create(r -> r.index(index));
        return "Availability index created: " + index;
    }

    // -------------------------------------------------------------------------
    // Data indexing
    // -------------------------------------------------------------------------

    public void bulkIndexStations(List<Map<String, Object>> stations) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (Map<String, Object> station : stations) {
            String id = String.valueOf(station.get("id"));
            bulk.operations(op -> op.index(idx -> idx
                    .index(STATIONS_INDEX)
                    .id(id)
                    .document(station)
            ));
        }
        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            System.err.println("[WARN] Some errors occurred while bulk indexing stations.");
        }
    }

    public void indexStationDetail(Map<String, Object> detail) throws IOException {
        int stationId = ((Number) detail.get("id")).intValue();
        String today = LocalDate.now().toString();

        Map<String, Object> doc = new HashMap<>(detail);
        doc.put("fetchedDate", today);

        String docId = stationId + "_" + today;
        client.index(idx -> idx
                .index(todayAvailabilityIndex())
                .id(docId)
                .document(doc)
        );
    }

    // -------------------------------------------------------------------------
    // Query helpers
    // -------------------------------------------------------------------------

    public List<Integer> getAllStationIdsFromIndex() throws IOException {
        long count = client.count(c -> c.index(STATIONS_INDEX)).count();
        if (count == 0) return List.of();

        var response = client.search(s -> s
                        .index(STATIONS_INDEX)
                        .size((int) count)
                        .source(src -> src.fetch(false)),
                Void.class
        );
        return response.hits().hits().stream()
                .map(h -> Integer.parseInt(h.id()))
                .collect(Collectors.toList());
    }


    /**
     * Returns true if {@code name} is an alias (not a concrete index).
     * ES throws 404 when no index has an alias with that name.
     */
    private boolean isAlias(String name) throws IOException {
        try {
            var response = client.indices().getAlias(r -> r.name(name));
            return !response.result().isEmpty();
        } catch (ElasticsearchException e) {
            return false;
        }
    }

    /**
     * One-time migration: if "stations" is still a concrete index (old setup),
     * creates "stations_v2" with the new template mapping, reindexes all data,
     * deletes the concrete index, and points the "stations" alias to "stations_v2".
     * Subsequent startups detect the alias and skip this method.
     */
    public void migrateStationsIndexIfNeeded() throws IOException {
        boolean exists = client.indices().exists(r -> r.index(STATIONS_INDEX)).value();
        if (!exists) {
            System.out.println("[ES] No stations index found, skipping migration.");
            return;
        }
        if (isAlias(STATIONS_INDEX)) {
            System.out.println("[ES] stations is already an alias — migration already done.");
            return;
        }

        System.out.println("[ES] Migrating " + STATIONS_INDEX + " -> " + STATIONS_BACKING_INDEX + " ...");

        boolean backingExists = client.indices().exists(r -> r.index(STATIONS_BACKING_INDEX)).value();
        if (!backingExists) {
            client.indices().create(r -> r
                    .index(STATIONS_BACKING_INDEX)
                    .settings(s -> s.maxResultWindow(20000))
            );
        }

        client.reindex(r -> r
                .source(s -> s.index(STATIONS_INDEX))
                .dest(d -> d.index(STATIONS_BACKING_INDEX))
        );

        client.indices().delete(r -> r.index(STATIONS_INDEX));

        client.indices().putAlias(r -> r.index(STATIONS_BACKING_INDEX).name(STATIONS_INDEX));

        System.out.println("[ES] Migration complete: alias '" + STATIONS_INDEX + "' -> " + STATIONS_BACKING_INDEX);
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
