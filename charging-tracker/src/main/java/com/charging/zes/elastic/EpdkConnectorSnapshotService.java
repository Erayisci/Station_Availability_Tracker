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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Queries epdk_zes_connectors-{date} to produce snapshot-based time-availability data.
 *
 * Each document in that index = (socket_id × snapshot_time) with current status.
 * Returns { "09:49" → { "Charging": N, "Other": M } } by aggregating
 * over the `time` field with 1-minute buckets and mapping IN_USE → Charging.
 */
@Service
public class EpdkConnectorSnapshotService {

    private static final String INDEX_PREFIX = "epdk_zes_connectors";

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public EpdkConnectorSnapshotService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    /**
     * Returns snapshot-time → status counts.
     * IN_USE is mapped to "Charging"; all other statuses are summed into "Other".
     * Only dates with an existing index are supported.
     */
    public Map<String, Map<String, Long>> statusBySnapshot(String date) throws IOException {
        String indexName = INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());

        if (!client.indices().exists(r -> r.index(indexName)).value()) {
            return new LinkedHashMap<>();
        }

        var response = client.search(s -> s
                .index(indexName)
                .size(0)
                .aggregations("by_time", a -> a
                        .dateHistogram(dh -> dh
                                .field("time")
                                .fixedInterval(fi -> fi.time("1m"))
                                .format("HH:mm")
                                .minDocCount(1)
                        )
                        .aggregations("by_status", inner -> inner
                                .terms(t -> t.field("status").size(10))
                        )
                ), Void.class);

        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (var bucket : response.aggregations().get("by_time").dateHistogram().buckets().array()) {
            long charging = 0, other = 0;
            for (var sb : bucket.aggregations().get("by_status").sterms().buckets().array()) {
                if ("IN_USE".equals(sb.key().stringValue())) charging += sb.docCount();
                else other += sb.docCount();
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("Charging", charging);
            counts.put("Other", other);
            result.put(bucket.keyAsString(), counts);
        }
        return result;
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
