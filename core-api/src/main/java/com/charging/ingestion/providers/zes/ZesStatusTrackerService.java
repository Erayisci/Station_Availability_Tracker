package com.charging.ingestion.providers.zes;

import com.charging.ingestion.spi.StationStatusProvider;

import com.charging.materialization.connectors.ConnectorTransformService;
import com.charging.kernel.persistence.ConnectorStatusHistory;
import com.charging.kernel.persistence.ConnectorStatusHistoryRepository;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core service for ZES 5-minute polling and end-of-day Elasticsearch indexing.
 *
 * Flow:
 *   pollAndTrack()        — called every 5 min; detects connector status changes, persists slots to H2
 *   indexDailyToElastic() — called at 23:59; enriches last station snapshot with availability array and indexes to ES
 */
@Service
public class ZesStatusTrackerService implements StationStatusProvider {

    @Override
    public String providerId() {
        return ZesStationPayloadMapper.PROVIDER_ID;
    }


    @Value("${zes.poll.lat:39.96282958984371}")
    private double lat;

    @Value("${zes.poll.lng:32.77142333984374}")
    private double lng;

    @Value("${zes.poll.locationHash:b8dae592482b26a57c5368501cd70ef2}")
    private String locationHash;

    @Value("${zes.poll.radius:12000}")
    private double radius;

    @Value("${zes.history.keepDays:7}")
    private int keepDays;

    @Autowired
    private ZesApiClient apiClient;

    @Autowired
    private ZesStationSnapshotIndexService indexService;

    @Autowired
    private ConnectorTransformService connectorTransformService;

    @Autowired
    private ConnectorStatusHistoryRepository repository;

    private final Map<String, String> lastKnownStatus = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> lastStationSnapshots = new ConcurrentHashMap<>();

    /**
     * Program crash yerse veya durdurup tekrar çalıştırdığımızda lastKnownStatus
     * silineceği için h2 da o gün için yapılan bir kayıt varsa program başlangıcında
     * (postconstruct) o kayıtlara bak ve onları tekrar lastknownstatus e kopyala
     */
    @PostConstruct
    @Override
    public void loadTodayOpenSlots() {
        List<ConnectorStatusHistory> openSlots = repository.findByTrackDateAndEndTimeIsNull(LocalDate.now());
        for (ConnectorStatusHistory slot : openSlots) {
            lastKnownStatus.put(slot.getConnectorId(), slot.getStatus());
        }
        System.out.printf("Loaded %d open slots from H2%n", openSlots.size());

        repository.deleteByTrackDateBefore(LocalDate.now().minusDays(keepDays));
    }

    /**
     * Fetches all ZES stations and detects connector status changes.
     * First time a connector is seen: opens a new H2 slot.
     * Status changed: closes the old slot (endTime=now), opens a new one.
     * Status unchanged: no DB write.
     * 
     * burada zese istek atılıp laststationsnapshots a gelen veri doldurluyor. Eğer lastknownstatusten 
     * farkl ıbir şey gelirse h2 ya o farklı status aynı connectorId için kaydediliyor.
     *
     */
    @SuppressWarnings("unchecked")
    @Override
    public int pollAndTrack() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        System.out.printf("[POLL] %s — fetching ZES stations...%n", now);

        Map<String, Object> raw = apiClient.fetchNearbyStations(lat, lng, locationHash, radius);
        List<Map<String, Object>> stations = (List<Map<String, Object>>) raw.getOrDefault("data", List.of());

        List<String> changedConnectors = new ArrayList<>();

        for (Map<String, Object> station : stations) {
            String stationId = String.valueOf(station.get("id"));
            lastStationSnapshots.put(stationId, station);

            List<Map<String, Object>> evses =
                    (List<Map<String, Object>>) station.getOrDefault("evses", List.of());

            for (Map<String, Object> evse : evses) {
                String evseId = String.valueOf(evse.get("id"));
                List<Map<String, Object>> connectors =
                        (List<Map<String, Object>>) evse.getOrDefault("connectors", List.of());

                for (Map<String, Object> connector : connectors) {
                    String connectorId = String.valueOf(connector.get("id"));
                    String newStatus = String.valueOf(connector.getOrDefault("status", "UNKNOWN"));
                    String prevStatus = lastKnownStatus.get(connectorId);

                    if (prevStatus == null) {
                        openSlot(stationId, evseId, connectorId, newStatus, now, today);
                        System.out.printf("[H2] NEW connector seen — station=%s connector=%s status=%s%n",
                                stationId, connectorId, newStatus);
                        changedConnectors.add(connectorId);
                    } else if (!prevStatus.equals(newStatus)) {
                        System.out.printf("[H2] STATUS CHANGE — station=%s connector=%s  %s -> %s%n",
                                stationId, connectorId, prevStatus, newStatus);
                        repository.findAllByConnectorIdAndTrackDateAndEndTimeIsNull(connectorId, today)
                                .forEach(open -> {
                                    open.setEndTime(now);
                                    repository.save(open);
                                });
                        openSlot(stationId, evseId, connectorId, newStatus, now, today);
                        changedConnectors.add(connectorId);
                    }

                    lastKnownStatus.put(connectorId, newStatus);
                }
            }
        }

        boolean indexMissing = !indexService.todayIndexExists(today.toString());
        if (!changedConnectors.isEmpty()) {
            System.out.printf("[H2] %d connector(s) updated in H2: %s%n", changedConnectors.size(), changedConnectors);
            triggerIndexChain(today.toString());
        } else if (indexMissing) {
            System.out.printf("[POLL] No status changes but today's ES index is missing — rebuilding.%n");
            triggerIndexChain(today.toString());
        } else {
            System.out.printf("[POLL] No status changes detected — index chain skipped.%n");
        }

        return stations.size();
    }

    private void triggerIndexChain(String date) {
        try {
            System.out.println("[INDEX] Status change detected — rebuilding zes_stations index...");
            int stationCount = indexDailyToElastic(false);
            System.out.printf("[INDEX] zes_stations-%s updated: %d station docs indexed.%n", date, stationCount);

            System.out.println("[TRANSFORM] Rebuilding zes_connector_transform index...");
            int connectorCount = connectorTransformService.transformConnectors(providerId(), date);
            System.out.printf("[TRANSFORM] zes_connector_transform-%s updated: %d connector docs indexed.%n", date, connectorCount);
        } catch (Exception e) {
            System.err.printf("[INDEX CHAIN ERROR] Failed after H2 update: %s%n", e.getMessage());
        }
    }

    /**
     * @param finalRun true  → gün sonu: slotları kapat, cache temizle (sadece scheduler çağırır)
     *                 false → gün içi: slotlara dokunma, cache temizleme (manuel endpoint çağırır)
     */
    @SuppressWarnings("unchecked")
    @Override
    public int indexDailyToElastic(boolean finalRun) throws Exception {
        LocalDate today = LocalDate.now();

        if (finalRun) {
            LocalDateTime endOfDay = today.atTime(23, 59, 59);
            List<ConnectorStatusHistory> openSlots = repository.findByTrackDateAndEndTimeIsNull(today);
            for (ConnectorStatusHistory slot : openSlots) {
                slot.setEndTime(endOfDay);
                repository.save(slot);
            }
        }

        if (lastStationSnapshots.isEmpty()) {
            System.out.println("[INDEX] lastStationSnapshots boş, otomatik poll yapılıyor...");
            pollAndTrack();
        }

        List<ConnectorStatusHistory> allHistory = repository.findByTrackDate(today);
        Map<String, List<Map<String, Object>>> connectorAvailability = new HashMap<>();
        for (ConnectorStatusHistory h : allHistory) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("status", h.getStatus());
            slot.put("startTime", h.getStartTime().toString());
            slot.put("endTime", h.getEndTime() != null ? h.getEndTime().toString() : null);
            connectorAvailability
                    .computeIfAbsent(h.getConnectorId(), k -> new ArrayList<>())
                    .add(slot);
        }

        List<Map<String, Object>> enrichedStations = new ArrayList<>();
        for (Map<String, Object> station : lastStationSnapshots.values()) {
            Map<String, Object> enrichedStation = new HashMap<>(station);

            List<Map<String, Object>> enrichedEvses = new ArrayList<>();
            for (Map<String, Object> evse : (List<Map<String, Object>>) station.getOrDefault("evses", List.of())) {
                Map<String, Object> enrichedEvse = new HashMap<>(evse);

                List<Map<String, Object>> enrichedConnectors = new ArrayList<>();
                for (Map<String, Object> connector : (List<Map<String, Object>>) evse.getOrDefault("connectors", List.of())) {
                    Map<String, Object> enrichedConnector = new HashMap<>(connector);
                    String connectorId = String.valueOf(connector.get("id"));
                    enrichedConnector.put("availability",
                            connectorAvailability.getOrDefault(connectorId, List.of()));
                    enrichedConnectors.add(enrichedConnector);
                }

                enrichedEvse.put("connectors", enrichedConnectors);
                enrichedEvses.add(enrichedEvse);
            }

            enrichedStation.put("evses", enrichedEvses);
            enrichedStations.add(enrichedStation);
        }

        if (finalRun) {
            lastKnownStatus.clear();
            lastStationSnapshots.clear();
        }

        return indexService.bulkIndexDailyStations(enrichedStations, today.toString());
    }

    private void openSlot(String stationId, String evseId, String connectorId,
                          String status, LocalDateTime startTime, LocalDate trackDate) {
        ConnectorStatusHistory slot = new ConnectorStatusHistory();
        slot.setStationId(stationId);
        slot.setEvseId(evseId);
        slot.setConnectorId(connectorId);
        slot.setStatus(status);
        slot.setStartTime(startTime);
        slot.setEndTime(null);
        slot.setTrackDate(trackDate);
        repository.save(slot);
    }
}
