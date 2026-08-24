package com.charging.analytics.enrichment;

import com.charging.kernel.es.IndexCatalog;

import com.charging.kernel.es.EsConfig;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

@Service
public class ZesStationEnrichmentService {

    private static final String ZES_INDEX_PREFIX  = IndexCatalog.ZES_STATIONS;
    private static final String ENV_INDEX         = IndexCatalog.ZES_STATION_ENVIRONMENT;
    private static final int    BATCH_SIZE        = 100;
    private static final int    RADIUS_METERS     = 300;

    private static final List<String> ENV_PRIORITY = List.of("retail", "commercial", "office", "residential");

    @Autowired
    private OverpassApiClient overpassClient;

    private final ElasticsearchClient client;
    private final RestClient          restClient;

    public ZesStationEnrichmentService() {
        restClient = RestClient.builder(EsConfig.host()).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
    }

    /**
     * Adım 1–6.
     * - Daha önce işlenmiş istasyonlar (zes_station_environment'ta kayıtlı) atlanır.
     * - Her batch başarıyla tamamlanınca hemen ES'e yazılır.
     * - Overpass'tan exception gelirse durur; o ana kadar yazılanlar ES'te kalır.
     *
     * @return zes_station_environment'a yazılan yeni kayıt sayısı
     */
    @SuppressWarnings("unchecked")
    public int enrichAndWriteEnvironments(String date) throws Exception {
        String sourceIndex = ZES_INDEX_PREFIX + "-" + (date != null ? date : LocalDate.now().toString());
        String enrichedAt  = date != null ? date : LocalDate.now().toString();

        ensureEnvironmentIndex();

        // ── Adım 1 ──────────────────────────────────────────────────────────────
        List<Map<String, Object>> stations = fetchAllStationCoordinates(sourceIndex);
        System.out.printf("[ENRICH] %d istasyon çekildi.%n", stations.size());

        // Daha önce işlenmiş istasyonları atla
        Set<String> alreadyDone = fetchProcessedStationIds();
        if (!alreadyDone.isEmpty()) {
            int before = stations.size();
            stations.removeIf(s -> alreadyDone.contains(String.valueOf(s.get("id"))));
            System.out.printf("[ENRICH] %d istasyon zaten işlenmiş, atlanıyor. Kalan: %d%n",
                    before - stations.size(), stations.size());
        }

        if (stations.isEmpty()) {
            System.out.println("[ENRICH] İşlenecek yeni istasyon yok.");
            return 0;
        }

        // ── Adım 2 ──────────────────────────────────────────────────────────────
        List<List<Map<String, Object>>> batches = partition(stations, BATCH_SIZE);
        System.out.printf("[ENRICH] %d batch oluşturuldu.%n", batches.size());

        int totalWritten = 0;

        for (int i = 0; i < batches.size(); i++) {
            List<Map<String, Object>> batch = batches.get(i);
            System.out.printf("[ENRICH] Batch %d/%d başlıyor (%d istasyon)...%n",
                    i + 1, batches.size(), batch.size());

            // ── Adım 3 ──────────────────────────────────────────────────────────
            Map<String, Object> response;
            try {
                response = overpassClient.fetchPoisForStations(batch);
            } catch (Exception e) {
                System.err.printf("[ENRICH] Batch %d/%d Overpass hatası: %s — duruyorum.%n",
                        i + 1, batches.size(), e.getMessage());
                System.out.printf("[ENRICH] %d batch işlendi, toplam %d kayıt yazıldı.%n", i, totalWritten);
                return totalWritten;
            }

            List<Map<String, Object>> elements =
                    (List<Map<String, Object>>) response.getOrDefault("elements", List.of());

            // ── Adım 4+5 ────────────────────────────────────────────────────────
            Map<String, Map<String, Integer>> poiCounts = matchPoisToStations(batch, elements);

            // ── Adım 6: hemen yaz ───────────────────────────────────────────────
            int written = writeEnvironments(batch, poiCounts, enrichedAt);
            totalWritten += written;

            System.out.printf("[ENRICH] Batch %d/%d tamamlandı: %d POI → %d kayıt yazıldı (toplam: %d).%n",
                    i + 1, batches.size(), elements.size(), written, totalWritten);

            if (i < batches.size() - 1) {
                Thread.sleep(3_000);
            }
        }

        System.out.printf("[ENRICH] Tüm batchler tamamlandı: %d kayıt yazıldı.%n", totalWritten);
        return totalWritten;
    }

    // ── Daha önce işlenmiş istasyon ID'lerini çek ───────────────────────────────

    private Set<String> fetchProcessedStationIds() throws IOException {
        boolean exists = client.indices().exists(r -> r.index(ENV_INDEX)).value();
        if (!exists) return Set.of();

        Set<String> ids = new HashSet<>();

        var firstResponse = client.search(s -> s
                        .index(ENV_INDEX)
                        .size(1000)
                        .source(src -> src.filter(f -> f.includes("station_id")))
                        .scroll(sc -> sc.time("2m")),
                Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();

        while (!hits.isEmpty()) {
            for (var hit : hits) {
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = (Map<String, Object>) hit.source();
                if (doc != null && doc.get("station_id") != null) {
                    ids.add(String.valueOf(doc.get("station_id")));
                }
            }
            String sid = scrollId;
            var next = client.scroll(r -> r.scrollId(sid).scroll(sc -> sc.time("2m")), Map.class);
            scrollId = next.scrollId();
            hits     = next.hits().hits();
        }

        if (scrollId != null) {
            String sid = scrollId;
            client.clearScroll(r -> r.scrollId(List.of(sid)));
        }

        System.out.printf("[ENRICH] %d istasyon daha önce işlenmiş.%n", ids.size());
        return ids;
    }

    // ── Adım 4: POI → en yakın istasyon eşleştirmesi ───────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Integer>> matchPoisToStations(
            List<Map<String, Object>> stations, List<Map<String, Object>> elements) {

        Map<String, Map<String, Integer>> result = new HashMap<>();

        for (Map<String, Object> element : elements) {
            Map<String, Object> tags = (Map<String, Object>) element.get("tags");
            if (tags == null) continue;

            String category = resolveCategory(tags);
            if (category == null) continue;

            // node: lat/lon direkt alanda; way: center altında
            double poiLat, poiLon;
            Map<String, Object> center = (Map<String, Object>) element.get("center");
            if (center != null) {
                poiLat = toDouble(center.get("lat"));
                poiLon = toDouble(center.get("lon"));
            } else if (element.get("lat") != null) {
                poiLat = toDouble(element.get("lat"));
                poiLon = toDouble(element.get("lon"));
            } else {
                continue;
            }

            String nearestId = null;
            double minDist   = Double.MAX_VALUE;

            for (Map<String, Object> station : stations) {
                double stLat = toDouble(station.get("latitude"));
                double stLon = toDouble(station.get("longitude"));
                double dist  = haversineMeters(poiLat, poiLon, stLat, stLon);
                if (dist < RADIUS_METERS && dist < minDist) {
                    minDist   = dist;
                    nearestId = String.valueOf(station.get("id"));
                }
            }

            if (nearestId != null) {
                result.computeIfAbsent(nearestId, k -> new HashMap<>())
                      .merge(category, 1, Integer::sum);
            }
        }
        return result;
    }

    /**
     * OSM tag'lerinden environment kategorisi çıkarır.
     * Önce landuse, sonra building, sonra shop/amenity'e bakar.
     */
    private static String resolveCategory(Map<String, Object> tags) {
        String landuse = (String) tags.get("landuse");
        if (landuse != null) {
            return switch (landuse) {
                case "residential"  -> "residential";
                case "retail"       -> "retail";
                case "commercial"   -> "commercial";
                case "office"       -> "office";
                default             -> null;
            };
        }

        String building = (String) tags.get("building");
        if (building != null) {
            return switch (building) {
                case "retail", "supermarket"        -> "retail";
                case "commercial"                   -> "commercial";
                case "residential", "apartments"    -> "residential";
                case "office"                       -> "office";
                default                             -> null;
            };
        }

        if (tags.containsKey("shop")) return "retail";

        String amenity = (String) tags.get("amenity");
        if (amenity != null) {
            return switch (amenity) {
                case "supermarket", "marketplace"                           -> "retail";
                case "restaurant", "cafe", "bar", "fast_food", "food_court" -> "commercial";
                case "bank", "office"                                       -> "office";
                default                                                     -> null;
            };
        }

        return null;
    }

    // ── Adım 5: environment_type belirleme ──────────────────────────────────────

    private String determineEnvironmentType(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "UNKNOWN";

        return counts.entrySet().stream()
                .max(Comparator
                        .comparingInt(Map.Entry<String, Integer>::getValue)
                        .thenComparingInt(e -> {
                            int idx = ENV_PRIORITY.indexOf(e.getKey());
                            return idx < 0 ? -1 : ENV_PRIORITY.size() - idx;
                        }))
                .map(e -> e.getKey().toUpperCase())
                .orElse("UNKNOWN");
    }

    // ── Adım 6: Elasticsearch'e hemen yaz ──────────────────────────────────────

    private void ensureEnvironmentIndex() throws IOException {
        boolean exists = client.indices().exists(r -> r.index(ENV_INDEX)).value();
        if (!exists) {
            client.indices().create(r -> r
                    .index(ENV_INDEX)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m
                            .properties("station_id",       p -> p.keyword(k -> k))
                            .properties("latitude",         p -> p.float_(f -> f))
                            .properties("longitude",        p -> p.float_(f -> f))
                            .properties("environment_type", p -> p.keyword(k -> k))
                            .properties("poi_counts",       p -> p.object(o -> o.enabled(false)))
                            .properties("enriched_at",      p -> p.date(d -> d))
                    ));
            System.out.printf("[ES] %s index oluşturuldu.%n", ENV_INDEX);
        }
    }

    private int writeEnvironments(List<Map<String, Object>> stations,
                                   Map<String, Map<String, Integer>> poiCounts,
                                   String enrichedAt) throws IOException {

        BulkRequest.Builder bulk = new BulkRequest.Builder();

        for (Map<String, Object> station : stations) {
            String id     = String.valueOf(station.get("id"));
            Map<String, Integer> counts = poiCounts.getOrDefault(id, Map.of());

            Map<String, Object> doc = new HashMap<>();
            doc.put("station_id",       id);
            doc.put("latitude",         station.get("latitude"));
            doc.put("longitude",        station.get("longitude"));
            doc.put("environment_type", determineEnvironmentType(counts));
            doc.put("poi_counts",       counts);
            doc.put("enriched_at",      enrichedAt);

            bulk.operations(op -> op.index(idx -> idx.index(ENV_INDEX).id(id).document(doc)));
        }

        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            response.items().stream()
                    .filter(item -> item.error() != null)
                    .forEach(item -> System.err.printf("[BULK ERROR] id=%s reason=%s%n",
                            item.id(), item.error().reason()));
        }
        return (int) response.items().stream().filter(i -> i.error() == null).count();
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllStationCoordinates(String indexName) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();

        var firstResponse = client.search(s -> s
                        .index(indexName)
                        .size(500)
                        .source(src -> src.filter(f -> f.includes("id", "latitude", "longitude")))
                        .scroll(sc -> sc.time("2m")),
                Map.class);

        String scrollId = firstResponse.scrollId();
        var hits = firstResponse.hits().hits();

        while (!hits.isEmpty()) {
            for (var hit : hits) {
                Map<String, Object> doc = (Map<String, Object>) hit.source();
                if (doc != null && doc.get("latitude") != null && doc.get("longitude") != null) {
                    Map<String, Object> station = new HashMap<>();
                    station.put("id",        doc.get("id"));
                    station.put("latitude",  doc.get("latitude"));
                    station.put("longitude", doc.get("longitude"));
                    result.add(station);
                }
            }

            String sid = scrollId;
            var next = client.scroll(r -> r.scrollId(sid).scroll(sc -> sc.time("2m")), Map.class);
            scrollId = next.scrollId();
            hits     = next.hits().hits();
        }

        if (scrollId != null) {
            String sid = scrollId;
            client.clearScroll(r -> r.scrollId(List.of(sid)));
        }

        return result;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R    = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(val));
    }

    @PreDestroy
    public void close() throws IOException {
        restClient.close();
    }
}
