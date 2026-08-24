package com.charging.analytics.api;

import com.charging.analytics.city.ZesCityAnalysisService;
import com.charging.analytics.environment.ZesEnvironmentAnalysisService;
import com.charging.analytics.station.ZesConnectorTransformAnalysisService;
import com.charging.analytics.station.ZesStationPerformanceQueryService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * L3 — Analytics read edge for ZES.
 *
 * Serves aggregations over indices that layer 2 (materialization) has already
 * built: {@code zes_connector_transform-*}, {@code zes_station_performance-*},
 * and {@code zes_station_environment}.
 *
 * Strictly read-only: nothing here writes to Elasticsearch. Index-building
 * triggers live in {@code materialization/api}, and endpoints that read
 * Python-produced indices live in {@code intelligence/api}.
 */
@RestController
@RequestMapping("/zes/availability")
public class ZesAnalyticsController {

    @Autowired
    private ZesConnectorTransformAnalysisService transformAnalysisService;

    @Autowired
    private ZesEnvironmentAnalysisService envAnalysisService;

    @Autowired
    private ZesStationPerformanceQueryService performanceQueryService;

    @Autowired
    private ZesCityAnalysisService cityAnalysisService;

    // ── Connector transform (zes_connector_transform-{date}) ─────────────────

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

    @GetMapping("/connector-transform/available-dates")
    public List<String> connectorTransformAvailableDates() throws IOException {
        return transformAnalysisService.availableDates();
    }

    // ── Environment (zes_station_environment) ────────────────────────────────

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

    // ── Station performance queries (zes_station_performance-{date}) ─────────

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

    @GetMapping("/station-performance/available-dates")
    public List<String> stationPerformanceAvailableDates() throws IOException {
        return performanceQueryService.availableDates();
    }

    // ── City analysis (Türkiye haritası + şehir detayı) ──────────────────────

    /**
     * 81 il için tek istekte özet metrikler.
     * Türkiye haritasını choropleth olarak boyamak için kullanılır.
     *
     * GET /zes/availability/city/summary?date=2026-06-03
     */
    @GetMapping("/city/summary")
    public Map<String, Map<String, Object>> citySummary(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return cityAnalysisService.summarizeCities(date);
    }

    /**
     * Tek şehir için zengin detay: tier dağılımları, connector_type, top istasyonlar.
     *
     * GET /zes/availability/city/detail?date=2026-06-03&city=Ankara
     */
    @GetMapping("/city/detail")
    public Map<String, Object> cityDetail(
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "city")                   String city) throws IOException {
        return cityAnalysisService.cityDetail(date, city);
    }

}
