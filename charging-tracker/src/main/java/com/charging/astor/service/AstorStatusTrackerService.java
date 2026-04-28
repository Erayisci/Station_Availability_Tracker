package com.charging.astor.service;

import com.charging.astor.client.AstorApiClient;
import com.charging.astor.elastic.AstorIndexService;
import com.charging.astor.model.AstorStationStatusHistory;
import com.charging.astor.repository.AstorStationStatusHistoryRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core service for ASTOR 5-minute polling and Elasticsearch indexing.
 *
 * Tracking unit: parent charge point (availabilityStatusCode)
 * Storage: H2 for slot history (crash-safe), in-memory for last known status + snapshots
 *
 * Flow:
 *   pollAndTrack()        — called every 5 min; detects charge point status changes, persists slots to H2
 *   indexDailyToElastic() — called on status change and at 23:59; builds availability arrays from H2 and indexes to ES
 */
@Service
public class AstorStatusTrackerService {

    @Value("${astor.history.keepDays:7}")
    private int keepDays;

    @Autowired
    private AstorApiClient apiClient;

    @Autowired
    private AstorIndexService indexService;

    @Autowired
    private AstorStationStatusHistoryRepository repository;

    // stationId -> current status (restored from H2 on startup)
    private final Map<String, String> lastKnownStatus = new ConcurrentHashMap<>();

    // stationId -> last API response snapshot
    private final Map<String, Map<String, Object>> lastStationSnapshots = new ConcurrentHashMap<>();

    /**
     * Crash/restart sonrası bugün için açık H2 slotları lastKnownStatus'e geri yükler.
     */
    @PostConstruct
    public void loadTodayOpenSlots() {
        List<AstorStationStatusHistory> openSlots = repository.findByTrackDateAndEndTimeIsNull(LocalDate.now());
        for (AstorStationStatusHistory slot : openSlots) {
            lastKnownStatus.put(slot.getStationId(), slot.getStatus());
        }
        System.out.printf("[ASTOR] Loaded %d open slots from H2%n", openSlots.size());

        repository.deleteByTrackDateBefore(LocalDate.now().minusDays(keepDays));
    }

    public int pollAndTrack() throws Exception {
        LocalDateTime now  = LocalDateTime.now();
        LocalDate today    = now.toLocalDate();

        System.out.printf("[ASTOR POLL] %s — fetching stations...%n", now);

        List<Map<String, Object>> stations = apiClient.fetchAllStations();
        List<String> changed = new ArrayList<>();

        for (Map<String, Object> station : stations) {
            String stationId  = String.valueOf(station.get("id"));
            String newStatus  = String.valueOf(station.getOrDefault("availabilityStatusCode", "UNKNOWN"));
            String prevStatus = lastKnownStatus.get(stationId);

            lastStationSnapshots.put(stationId, station);

            if (prevStatus == null) {
                openSlot(stationId, newStatus, now, today);
                System.out.printf("[H2] NEW station=%s status=%s%n", stationId, newStatus);
                changed.add(stationId);
            } else if (!prevStatus.equals(newStatus)) {
                System.out.printf("[H2] STATUS CHANGE station=%s  %s -> %s%n", stationId, prevStatus, newStatus);
                repository.findAllByStationIdAndTrackDateAndEndTimeIsNull(stationId, today)
                        .forEach(open -> {
                            open.setEndTime(now);
                            repository.save(open);
                        });
                openSlot(stationId, newStatus, now, today);
                changed.add(stationId);
            }

            lastKnownStatus.put(stationId, newStatus);
        }

        boolean indexMissing = !indexService.todayIndexExists(today.toString());
        if (!changed.isEmpty()) {
            System.out.printf("[ASTOR] %d station(s) changed — rebuilding index...%n", changed.size());
            indexDailyToElastic(false);
        } else if (indexMissing) {
            System.out.println("[ASTOR] No status changes but today's index is missing — rebuilding...");
            indexDailyToElastic(false);
        } else {
            System.out.println("[ASTOR] No status changes — index skipped.");
        }

        return stations.size();
    }

    /**
     * @param finalRun true  → gün sonu: open slot'ları 23:59:59'da kapat, cache temizle
     *                 false → gün içi: open slot'lara dokunma
     */
    public int indexDailyToElastic(boolean finalRun) throws Exception {
        LocalDate today      = LocalDate.now();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);

        if (finalRun) {
            repository.findByTrackDateAndEndTimeIsNull(today).forEach(slot -> {
                slot.setEndTime(endOfDay);
                repository.save(slot);
            });
        }

        if (lastStationSnapshots.isEmpty()) {
            System.out.println("[ASTOR INDEX] No snapshots in memory — triggering poll first...");
            pollAndTrack();
        }

        // H2'den bugünün tüm slot geçmişini çek
        List<AstorStationStatusHistory> allHistory = repository.findByTrackDate(today);
        Map<String, List<Map<String, Object>>> stationAvailability = new HashMap<>();
        for (AstorStationStatusHistory h : allHistory) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("status",     h.getStatus());
            slot.put("startTime",  h.getStartTime().toString());
            slot.put("endTime",    h.getEndTime() != null ? h.getEndTime().toString() : null);
            stationAvailability
                    .computeIfAbsent(h.getStationId(), k -> new ArrayList<>())
                    .add(slot);
        }

        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> station : lastStationSnapshots.values()) {
            String stationId = String.valueOf(station.get("id"));
            List<Map<String, Object>> rawSlots = stationAvailability.getOrDefault(stationId, List.of());
            List<Map<String, Object>> availability = rawSlots.stream()
                    .map(s -> enrichAvailability(s))
                    .toList();
            docs.add(buildStationDoc(station, availability, today.toString()));
        }

        if (finalRun) {
            lastKnownStatus.clear();
            lastStationSnapshots.clear();
            System.out.println("[ASTOR INDEX] In-memory state cleared for new day.");
        }

        return indexService.bulkIndex(docs, today.toString());
    }

    private void openSlot(String stationId, String status, LocalDateTime startTime, LocalDate trackDate) {
        AstorStationStatusHistory slot = new AstorStationStatusHistory();
        slot.setStationId(stationId);
        slot.setStatus(status);
        slot.setStartTime(startTime);
        slot.setEndTime(null);
        slot.setTrackDate(trackDate);
        repository.save(slot);
    }

    private Map<String, Object> buildStationDoc(Map<String, Object> station,
                                                 List<Map<String, Object>> availability,
                                                 String date) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id",                     String.valueOf(station.get("id")));
        doc.put("unit_name",              station.get("unitName"));
        doc.put("address",                station.get("address"));
        doc.put("tenant_code",            station.get("tenantCode"));
        doc.put("location_code",          station.get("locationCode"));
        doc.put("connector_current_type", station.get("connectorCurrentType"));
        doc.put("vendor_code",            station.get("vendorCode"));
        doc.put("opens_at",               station.get("opensAt"));
        doc.put("closes_at",              station.get("closesAt"));
        doc.put("last_communication",     station.get("lastCommunication"));

        Object rawMaxPower = station.get("maxPower");
        if (rawMaxPower != null) {
            try { doc.put("max_power", Integer.parseInt(String.valueOf(rawMaxPower))); }
            catch (NumberFormatException e) { doc.put("max_power", null); }
        }

        // Coordinate — stored as strings in the API response
        Object coordinateObj = station.get("coordinate");
        if (coordinateObj instanceof Map<?, ?> coord) {
            try {
                double lat = Double.parseDouble(String.valueOf(coord.get("latitude")));
                double lng = Double.parseDouble(String.valueOf(coord.get("longitude")));
                doc.put("lat", lat);
                doc.put("lng", lng);
                doc.put("location", Map.of("lat", lat, "lon", lng));
            } catch (NumberFormatException ignored) {}
        }

        doc.put("fetched_date", date);
        doc.put("availability", availability);
        return doc;
    }

    private Map<String, Object> enrichAvailability(Map<String, Object> raw) {
        Map<String, Object> entry = new LinkedHashMap<>(raw);
        String startTimeStr = (String) raw.get("startTime");
        String endTimeStr   = (String) raw.get("endTime");
        String status       = (String) raw.get("status");

        entry.remove("startTime");
        entry.remove("endTime");
        entry.put("start_time", startTimeStr);
        entry.put("end_time",   endTimeStr);

        Double durationMinutes = null;
        String timeSlot        = null;

        if (startTimeStr != null) {
            try {
                LocalDateTime start = LocalDateTime.parse(startTimeStr);
                int hour   = start.getHour();
                int minute = start.getMinute();
                timeSlot = minute < 30
                        ? String.format("%02d:00-%02d:30", hour, hour)
                        : String.format("%02d:30-%02d:00", hour, (hour + 1) % 24);

                if (endTimeStr != null) {
                    LocalDateTime end = LocalDateTime.parse(endTimeStr);
                    durationMinutes = ChronoUnit.SECONDS.between(start, end) / 60.0;
                }
            } catch (Exception ignored) {}
        }

        entry.put("time_slot",        timeSlot);
        entry.put("duration_minutes", durationMinutes);
        entry.put("is_available",     "available".equals(status) ? 1 : 0);
        return entry;
    }
}
