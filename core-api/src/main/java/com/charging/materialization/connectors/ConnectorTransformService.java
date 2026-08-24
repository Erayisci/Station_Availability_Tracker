package com.charging.materialization.connectors;

import com.charging.ingestion.ProviderRegistry;
import com.charging.ingestion.spi.AvailabilityWindow;
import com.charging.ingestion.spi.ConnectorRow;
import com.charging.ingestion.spi.ConnectorStatus;
import com.charging.ingestion.spi.StationPayloadMapper;
import com.charging.kernel.es.EsConfig;
import com.charging.kernel.es.IndexCatalog;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * L2'nin ilk kanonik aşaması:
 * {@code {provider}_stations-{date}} → {@code {provider}_connector_transform-{date}}
 *
 * <p>İstasyon bazlı iniş alanını soket bazına açar ve her gözlem penceresini
 * türetilmiş alanlarla zenginleştirir: {@code duration_minutes},
 * {@code time_slot} (yarım saatlik kova), {@code is_available}.
 *
 * <h2>Bu servis neden sağlayıcıdan bağımsız</h2>
 * Ham payload'ı kendisi okumaz. Sağlayıcının alan adlarını ve iç içe geçmiş
 * yapısını {@link StationPayloadMapper} çözer, buraya kanonik
 * {@link ConnectorRow} olarak gelir. Bu servis yalnızca iki şeyi bilir:
 * platformun kendi seçtiği Elasticsearch şeması ve zaman aritmetiği — ikisi de
 * hiçbir şarj ağına özgü değil.
 *
 * <p>Sonuç olarak bir ağ eklemek bu dosyada değişiklik gerektirmez; yeni bir
 * {@code StationPayloadMapper} yazmak yeterlidir.
 *
 * <h2>Idempotent</h2>
 * doc_id = {@code connector_id}, hedef index her çalıştırmada silinip yeniden
 * kurulur. Aynı gün için tekrar çağırmak güvenlidir ve başka bir günün
 * index'ine dokunmaz.
 */
@Service
public class ConnectorTransformService {

    private final ElasticsearchClient client;
    private final RestClient restClient;

    @Autowired
    private ProviderRegistry providers;

    public ConnectorTransformService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    /**
     * Kanonik şema her sağlayıcı için aynı, ama index adları ayrı olduğu için
     * template de sağlayıcı başına kurulur ({@code {provider}_connector_transform_template}).
     * Tek bir joker template ({@code *_connector_transform-*}) yazmak da mümkündü;
     * ayrı template'ler tercih edildi çünkü mevcut ES durumunda çakışan desenler
     * oluşturmaz ve her sağlayıcının şeması bağımsız olarak geliştirilebilir.
     */
    @PostConstruct
    public void ensureTemplate() throws IOException {
        for (String providerId : providers.mapperProviderIds()) {
            String prefix = IndexCatalog.connectorTransform(providerId);
            String templateName = IndexCatalog.templateFor(prefix);
            client.indices().putIndexTemplate(t -> t
                    .name(templateName)
                    .indexPatterns(IndexCatalog.allDates(prefix))
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
            System.out.printf("[ES] Template '%s' created.%n", templateName);
        }
    }

    /**
     * İniş alanındaki her istasyon dokümanını sağlayıcının mapper'ı ile kanonik
     * soket satırlarına açar ve hedef index'e bulk yazar.
     *
     * @param providerId hangi ağın zinciri işlenecek
     * @param date       kaynak tarih (null = bugün)
     * @return indexlenen soket dokümanı sayısı
     */
    @SuppressWarnings("unchecked")
    public int transformConnectors(String providerId, String date) throws IOException {
        StationPayloadMapper mapper = providers.mapperFor(providerId);

        String resolvedDate = date != null ? date : LocalDate.now().toString();
        String sourceIndex = IndexCatalog.nameFor(IndexCatalog.stations(providerId), resolvedDate);
        String targetIndex = IndexCatalog.nameFor(IndexCatalog.connectorTransform(providerId), resolvedDate);

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
                for (ConnectorRow row : mapper.toConnectorRows((Map<String, Object>) hit.source())) {
                    connectorDocs.add(toDocument(row));
                }
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

    /** Kanonik satır → Elasticsearch dokümanı (platformun snake_case şeması). */
    private Map<String, Object> toDocument(ConnectorRow row) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("connector_id",       row.connectorId());
        doc.put("connector_type",     row.connectorType());
        doc.put("connector_no",       row.connectorNo());
        doc.put("max_power_kw",       row.maxPowerKw());
        doc.put("station_id",         row.stationId());
        doc.put("station_name",       row.stationName());
        doc.put("address",            row.address());
        doc.put("city_name",          row.cityName());
        doc.put("province_name",      row.provinceName());
        doc.put("lat",                row.lat());
        doc.put("lng",                row.lng());
        if (row.hasCoordinates()) {
            doc.put("location", Map.of("lat", row.lat(), "lon", row.lng()));
        }
        doc.put("distance",           row.distance());
        doc.put("opening_time",       row.openingTime());
        doc.put("closing_time",       row.closingTime());
        doc.put("is_restricted",      row.isRestricted());
        doc.put("is_roaming",         row.isRoaming());
        doc.put("evse_id",            row.evseId());
        doc.put("evse_location_desc", row.evseLocationDesc());
        doc.put("is_autocharge",      row.isAutocharge());
        doc.put("fetched_date",       row.observedDate());

        List<Map<String, Object>> availability = new ArrayList<>(row.availability().size());
        for (AvailabilityWindow window : row.availability()) {
            availability.add(enrichAvailability(window));
        }
        doc.put("availability", availability);

        return doc;
    }

    /**
     * Gözlem penceresinden türetilen alanlar. Sağlayıcıdan bağımsız saf zaman
     * aritmetiği: yarım saatlik kova, dakika cinsinden süre, boşluk göstergesi.
     */
    private Map<String, Object> enrichAvailability(AvailabilityWindow window) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        enriched.put("status",     window.status());
        enriched.put("start_time", window.startTime());
        enriched.put("end_time",   window.endTime());

        String timeSlot        = null;
        Double durationMinutes = null;

        if (window.startTime() != null) {
            try {
                LocalDateTime start = LocalDateTime.parse(window.startTime());
                int hour   = start.getHour();
                int minute = start.getMinute();
                if (minute < 30) {
                    timeSlot = String.format("%02d:00-%02d:30", hour, hour);
                } else {
                    int nextHour = (hour + 1) % 24;
                    timeSlot = String.format("%02d:30-%02d:00", hour, nextHour);
                }
                if (window.endTime() != null) {
                    LocalDateTime end = LocalDateTime.parse(window.endTime());
                    durationMinutes = (double) ChronoUnit.SECONDS.between(start, end) / 60.0;
                }
            } catch (Exception ignored) {}
        }

        enriched.put("time_slot",        timeSlot);
        enriched.put("duration_minutes", durationMinutes);
        enriched.put("is_available",     ConnectorStatus.isAvailable(window.status()) ? 1 : 0);

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
     * Verilen tarih için connector_transform index'i var mı?
     *
     * @param providerId hangi ağ
     * @param date       tarih (YYYY-MM-DD) veya null = bugün
     */
    public boolean indexExists(String providerId, String date) throws IOException {
        String indexName = IndexCatalog.nameFor(IndexCatalog.connectorTransform(providerId), date);
        return client.indices().exists(r -> r.index(indexName)).value();
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
