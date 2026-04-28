package com.charging.zes.controller;

import com.charging.zes.elastic.ZesAnalysisService;
import com.charging.zes.elastic.ZesConnectorTransformAnalysisService;
import com.charging.zes.elastic.ZesEnvironmentAnalysisService;
import com.charging.zes.elastic.ZesStationPerformanceIndexService;
import com.charging.zes.elastic.ZesStationPerformanceQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/zes/availability")
public class ZesAnalysisController {

    @Autowired
    private ZesAnalysisService analysisService;

    @Autowired
    private ZesConnectorTransformAnalysisService transformAnalysisService;

    @Autowired
    private ZesEnvironmentAnalysisService envAnalysisService;

    @Autowired
    private ZesStationPerformanceIndexService performanceIndexService;

    @Autowired
    private ZesStationPerformanceQueryService performanceQueryService;

    @GetMapping("/stats/by-connector-type")
    public Map<String, Map<String, Long>> statsByConnectorType(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return analysisService.statusByConnectorType(date);
    }

    @GetMapping("/stats/by-province")
    public Map<String, Map<String, Long>> statsByProvince(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return analysisService.statusByProvince(date);
    }

    @GetMapping("/stats/by-time-slot")
    public Map<String, Map<String, Long>> statsByTimeSlot(
            @RequestParam(name = "date",            required = false)  String date,
            @RequestParam(name = "intervalMinutes", defaultValue = "30") int intervalMinutes,
            @RequestParam(name = "province",        required = false)  String province,
            @RequestParam(name = "connectorType",   required = false)  String connectorType) throws IOException {
        return analysisService.statusByTimeSlot(date, intervalMinutes, province, connectorType);
    }

    @GetMapping("/connector-transform/stats/by-city")
    public Map<String, Map<String, Double>> connectorTransformByCity(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return transformAnalysisService.durationByCity(date);
    }

    @GetMapping("/connector-transform/stats/by-time-slot")
    public Map<String, Map<String, Long>> connectorTransformTimeSlot(
            @RequestParam(name = "date",            required = false)   String date,
            @RequestParam(name = "intervalMinutes", defaultValue = "30") int intervalMinutes,
            @RequestParam(name = "province",        required = false)   String province,
            @RequestParam(name = "connectorType",   required = false)   String connectorType) throws IOException {
        return transformAnalysisService.statusByTimeSlot(date, intervalMinutes, connectorType, province);
    }

    @GetMapping("/connector-transform/stats/timeline")
    public List<Map<String, Object>> connectorTransformTimeline(
            @RequestParam(name = "date",          required = false) String date,
            @RequestParam(name = "province",      required = false) String province,
            @RequestParam(name = "connectorType", required = false) String connectorType) throws IOException {
        return transformAnalysisService.statusTimeline(date, connectorType, province);
    }

    @GetMapping("/connector-transform/search")
    public List<Map<String, Object>> connectorSearch(
            @RequestParam(name = "date",          required = false)    String date,
            @RequestParam(name = "province",      required = false)    String province,
            @RequestParam(name = "connectorType", required = false)    String connectorType,
            @RequestParam(name = "status",        required = false)    String status,
            @RequestParam(name = "time",          required = false)    String time,
            @RequestParam(name = "address",       required = false)    String address,
            @RequestParam(name = "envType",       required = false)    String envType,
            @RequestParam(name = "from",          defaultValue = "0")  int from,
            @RequestParam(name = "size",          defaultValue = "20") int size) throws IOException {
        return transformAnalysisService.searchConnectors(date, province, connectorType, status, time, address, envType, from, size);
    }

    @GetMapping("/stats/env-distribution")
    public Map<String, Long> envDistribution() throws IOException {
        return envAnalysisService.envTypeDistribution();
    }

    @GetMapping("/connector-transform/stats/by-env-timeslot")
    public Map<String, Map<String, Map<String, Long>>> byEnvTimeSlot(
            @RequestParam(name = "date",            required = false)   String date,
            @RequestParam(name = "intervalMinutes", defaultValue = "30") int intervalMinutes,
            @RequestParam(name = "province",        required = false)   String province,
            @RequestParam(name = "connectorType",   required = false)   String connectorType) throws IOException {
        return envAnalysisService.statusByEnvAndTimeSlot(date, intervalMinutes, connectorType, province);
    }

    // ── Station Performance Index (zes_station_performance-{date}) ───────────

    /**
     * zes_connector_transform-{date} → zes_station_performance-{date} transform'unu tetikler.
     * Scheduler zaten gece 23:59'da çalıştırır; bu endpoint manuel/backfill için kullanılır.
     *
     * POST /zes/availability/station-performance/build?date=2026-04-14&radius=1.0
     */
    @PostMapping("/station-performance/build")
    public Map<String, Object> buildPerformanceIndex(
            @RequestParam(name = "date",   required = false)     String date,
            @RequestParam(name = "radius", defaultValue = "1.0") double radius) throws Exception {
        int count = performanceIndexService.buildPerformanceIndex(date, radius);
        return Map.of("indexed_stations", count, "date", date != null ? date : "today");
    }

    /**
     * Yüksek talep + düşük arz: yeni istasyon inşa edilmesi önerilenler.
     * demand_supply_ratio desc sıralı.
     *
     * GET /zes/availability/station-performance/overutilized?date=2026-04-14&size=20
     */
    @GetMapping("/station-performance/overutilized")
    public List<Map<String, Object>> overutilized(
            @RequestParam(name = "date", required = false)     String date,
            @RequestParam(name = "size", defaultValue = "20")  int size) throws IOException {
        return performanceQueryService.overutilized(date, size);
    }

    /**
     * Düşük talep + yüksek arz: strateji gözden geçirilmesi / taşınması önerilenler.
     * charging_utilization_pct asc sıralı.
     *
     * GET /zes/availability/station-performance/underutilized?date=2026-04-14&size=20
     */
    @GetMapping("/station-performance/underutilized")
    public List<Map<String, Object>> underutilized(
            @RequestParam(name = "date", required = false)     String date,
            @RequestParam(name = "size", defaultValue = "20")  int size) throws IOException {
        return performanceQueryService.underutilized(date, size);
    }

    /**
     * Esnek performans arama.
     *
     * GET /zes/availability/station-performance/search
     *       ?date=&demandTier=HIGH&supplyTier=FEW&recommendationCode=EXPAND
     *       &cityName=Ankara&stationName=...
     *       &minUtilizationPct=30&maxUtilizationPct=100
     *       &sort=demand_supply_ratio&order=desc&from=0&size=20
     */
    @GetMapping("/station-performance/search")
    public List<Map<String, Object>> stationPerformanceSearch(
            @RequestParam(name = "date",               required = false)    String date,
            @RequestParam(name = "demandTier",         required = false)    String demandTier,
            @RequestParam(name = "supplyTier",         required = false)    String supplyTier,
            @RequestParam(name = "recommendationCode", required = false)    String recommendationCode,
            @RequestParam(name = "cityName",           required = false)    String cityName,
            @RequestParam(name = "stationName",        required = false)    String stationName,
            @RequestParam(name = "minUtilizationPct",  required = false)    Double minUtilizationPct,
            @RequestParam(name = "maxUtilizationPct",  required = false)    Double maxUtilizationPct,
            @RequestParam(name = "sort",               required = false)    String sort,
            @RequestParam(name = "order",              defaultValue = "desc") String order,
            @RequestParam(name = "from",               defaultValue = "0")  int from,
            @RequestParam(name = "size",               defaultValue = "20") int size) throws IOException {
        return performanceQueryService.search(date, demandTier, supplyTier, recommendationCode,
                cityName, stationName, minUtilizationPct, maxUtilizationPct, sort, order, from, size);
    }

    /**
     * Tavsiye kodlarının dağılımı: { "EXPAND": 12, "MAINTAIN": 45, … }
     *
     * GET /zes/availability/station-performance/stats/recommendation-distribution?date=
     */
    @GetMapping("/station-performance/stats/recommendation-distribution")
    public Map<String, Long> recommendationDistribution(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return performanceQueryService.recommendationDistribution(date);
    }

    /**
     * Demand × Supply matrisi: { "HIGH": { "FEW": 5, "MODERATE": 3 }, … }
     *
     * GET /zes/availability/station-performance/stats/demand-supply-matrix?date=
     */
    @GetMapping("/station-performance/stats/demand-supply-matrix")
    public Map<String, Map<String, Long>> demandSupplyMatrix(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return performanceQueryService.demandSupplyMatrix(date);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam(name = "date",          required = false)    String date,
            @RequestParam(name = "province",      required = false)    String province,
            @RequestParam(name = "connectorType", required = false)    String connectorType,
            @RequestParam(name = "status",        required = false)    String status,
            @RequestParam(name = "from",          defaultValue = "0")  int from,
            @RequestParam(name = "size",          defaultValue = "20") int size,
            @RequestParam(name = "sort",          required = false)    String sort,
            @RequestParam(name = "order",         defaultValue = "asc") String order) throws IOException {
        return analysisService.searchStations(date, province, connectorType, status, from, size, sort, order);
    }
}
