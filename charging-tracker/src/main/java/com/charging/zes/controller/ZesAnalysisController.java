package com.charging.zes.controller;

import com.charging.zes.elastic.EpdkConnectorSnapshotService;
import com.charging.zes.elastic.ZesAnalysisService;
import com.charging.zes.elastic.ZesCityAnalysisService;
import com.charging.zes.elastic.ZesOpportunityService;
import com.charging.zes.elastic.ZesForecastService;
import com.charging.zes.elastic.ZesPricingService;
import com.charging.zes.elastic.ZesConnectorTransformAnalysisService;
import com.charging.zes.elastic.ZesConnectorTransformService;
import com.charging.zes.elastic.ZesEnvironmentAnalysisService;
import com.charging.zes.elastic.ZesStationPerformanceIndexService;
import com.charging.zes.elastic.ZesStationPerformanceQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/zes/availability")
public class ZesAnalysisController {

    // Removed: analysisService (methods removed as unused)
    // @Autowired
    // private ZesAnalysisService analysisService;

    @Autowired
    private EpdkConnectorSnapshotService epdkSnapshotService;

    @Autowired
    private ZesConnectorTransformAnalysisService transformAnalysisService;

    @Autowired
    private ZesConnectorTransformService connectorTransformService;

    @Autowired
    private ZesEnvironmentAnalysisService envAnalysisService;

    @Autowired
    private ZesStationPerformanceIndexService performanceIndexService;

    @Autowired
    private ZesStationPerformanceQueryService performanceQueryService;

    @Autowired
    private ZesCityAnalysisService cityAnalysisService;

    @Autowired
    private ZesOpportunityService opportunityService;

    @Autowired
    private ZesForecastService forecastService;

    @Autowired
    private ZesPricingService pricingService;

    // Removed: /stats/by-connector-type (unused by UI)
    // Removed: /stats/by-province (unused by UI)
    // Removed: /stats/by-time-slot (duplicate - use /connector-transform/stats/by-time-slot instead)

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

    // Removed: /search (unused by UI - use /connector-transform/search instead)

    // ── Backfill Mechanism ────────────────────────────────────────────────────

    /**
     * Backfills missing performance indexes for a date range.
     * Only creates indexes where connector_transform exists but performance is missing.
     *
     * POST /zes/availability/station-performance/backfill
     *   ?startDate=2026-04-01&endDate=2026-05-14&radius=1.0
     *
     * Response:
     * {
     *   "requested_range": { "start": "2026-04-01", "end": "2026-05-14" },
     *   "total_dates": 44,
     *   "skipped": 10,      // connector_transform missing
     *   "existing": 5,      // performance index already exists
     *   "built": 29,        // newly created
     *   "failed": 0,
     *   "details": [
     *     { "date": "2026-04-01", "status": "BUILT", "stations": 1818 },
     *     { "date": "2026-04-02", "status": "SKIPPED", "reason": "connector_transform missing" },
     *     ...
     *   ]
     * }
     */
    @PostMapping("/station-performance/backfill")
    public Map<String, Object> backfillPerformanceIndex(
            @RequestParam(name = "startDate") String startDate,
            @RequestParam(name = "endDate")   String endDate,
            @RequestParam(name = "radius", defaultValue = "1.0") double radius,
            @RequestParam(name = "force",  defaultValue = "false") boolean force) {

        LocalDate start = LocalDate.parse(startDate);
        LocalDate end   = LocalDate.parse(endDate);

        List<Map<String, Object>> details = new ArrayList<>();
        int skipped = 0, existing = 0, built = 0, failed = 0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String dateStr = date.toString();
            Map<String, Object> result = new HashMap<>();
            result.put("date", dateStr);

            try {
                // Check if connector_transform exists
                boolean hasConnectorTransform =
                    connectorTransformService.indexExists(dateStr);

                if (!hasConnectorTransform) {
                    result.put("status", "SKIPPED");
                    result.put("reason", "connector_transform index missing");
                    skipped++;
                    details.add(result);
                    continue;
                }

                // Skip if already exists and force not requested
                if (!force && performanceIndexService.indexExists(dateStr)) {
                    result.put("status", "EXISTING");
                    existing++;
                    details.add(result);
                    continue;
                }

                // Build (or rebuild) performance index
                int count = performanceIndexService.buildPerformanceIndex(dateStr, radius);
                result.put("status", force ? "REBUILT" : "BUILT");
                result.put("stations", count);
                built++;
                details.add(result);

            } catch (Exception e) {
                result.put("status", "FAILED");
                result.put("error", e.getMessage());
                failed++;
                details.add(result);
            }
        }

        long totalDates = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;

        return Map.of(
            "requested_range", Map.of("start", startDate, "end", endDate),
            "total_dates", totalDates,
            "skipped", skipped,
            "existing", existing,
            "built", built,
            "failed", failed,
            "details", details
        );
    }

    // ── City Analysis (Türkiye haritası + şehir detayı) ──────────────────────

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

    // ── Opportunity (zes_station_opportunity-{date}) ─────────────────────────

    /**
     * Panel başlığı / sanity-check: dağılımlar, mutabakat, histogram.
     *
     * GET /zes/availability/opportunity/summary?date=2026-06-03
     */
    @GetMapping("/opportunity/summary")
    public Map<String, Object> opportunitySummary(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return opportunityService.summary(date);
    }

    /**
     * En yüksek opportunity_score'a sahip istasyonlar.
     * components nested alanı ile birlikte tam doküman döner.
     *
     * GET /zes/availability/opportunity/top?date=&size=20&city=&growth=HIGH
     */
    @GetMapping("/opportunity/top")
    public List<Map<String, Object>> opportunityTop(
            @RequestParam(name = "date",   required = false)      String date,
            @RequestParam(name = "size",   defaultValue = "20")   Integer size,
            @RequestParam(name = "city",   required = false)      String city,
            @RequestParam(name = "growth", required = false)      String growth,
            @RequestParam(name = "order",  defaultValue = "desc") String order) throws IOException {
        return opportunityService.top(date, size, city, growth, order);
    }

    /**
     * Tek istasyonun tam kırılımı (drill-in için).
     *
     * GET /zes/availability/opportunity/station?date=&stationId=...
     */
    @GetMapping("/opportunity/station")
    public Map<String, Object> opportunityStation(
            @RequestParam(name = "date",      required = false) String date,
            @RequestParam(name = "stationId")                   String stationId) throws IOException {
        return opportunityService.station(date, stationId);
    }

    /**
     * Score vs existing_demand_supply_ratio scatter (agreement renkli).
     *
     * GET /zes/availability/opportunity/scatter?date=&size=2000
     */
    @GetMapping("/opportunity/scatter")
    public List<Map<String, Object>> opportunityScatter(
            @RequestParam(name = "date", required = false)       String date,
            @RequestParam(name = "size", defaultValue = "2000")  Integer size) throws IOException {
        return opportunityService.scatter(date, size);
    }

    @GetMapping("/opportunity/available-dates")
    public List<String> opportunityAvailableDates() throws IOException {
        return opportunityService.availableDates();
    }

    // ── Forecast (zes_station_forecast-{date}) ───────────────────────────────

    @GetMapping("/forecast/summary")
    public Map<String, Object> forecastSummary(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return forecastService.summary(date);
    }

    /**
     * Sayfalandırılmış tahmin listesi (delta_vs_source bazlı sıralama).
     * direction=up → en çok yükseliş; direction=down → en çok düşüş.
     * stationName: kısmi eşleşme.
     *
     * Yanıt: { total, from, size, rows: [...] }
     */
    @GetMapping("/forecast/top")
    public Map<String, Object> forecastTop(
            @RequestParam(name = "date",        required = false)        String date,
            @RequestParam(name = "from",        defaultValue = "0")      Integer from,
            @RequestParam(name = "size",        defaultValue = "20")     Integer size,
            @RequestParam(name = "direction",   defaultValue = "up")     String direction,
            @RequestParam(name = "city",        required = false)        String city,
            @RequestParam(name = "stationName", required = false)        String stationName,
            @RequestParam(name = "sortBy",      defaultValue = "delta")  String sortBy) throws IOException {
        return forecastService.top(date, from, size, direction, city, stationName, sortBy);
    }

    @GetMapping("/forecast/scatter")
    public List<Map<String, Object>> forecastScatter(
            @RequestParam(name = "date", required = false)       String date,
            @RequestParam(name = "size", defaultValue = "2000")  Integer size) throws IOException {
        return forecastService.scatter(date, size);
    }

    @GetMapping("/forecast/station")
    public Map<String, Object> forecastStation(
            @RequestParam(name = "date",      required = false) String date,
            @RequestParam(name = "stationId")                   String stationId) throws IOException {
        return forecastService.station(date, stationId);
    }

    @GetMapping("/forecast/available-dates")
    public List<String> forecastAvailableDates() throws IOException {
        return forecastService.availableDates();
    }

    // ── İstasyon-bazlı dinamik fiyatlandırma ─────────────────────────────────
    // Karar artık kullanıcıda (saat-bantları + manuel indirim); gelir tarayıcıda
    // (pricingModel.js) hesaplanır. Backend yalnızca veri + varsayım sunar.

    /** GET /zes/availability/pricing/config — ekonomik varsayımlar (taban fiyat, η, U_CAP …). */
    @GetMapping("/pricing/config")
    public Map<String, Object> pricingConfig() {
        return pricingService.config();
    }

    /** GET /zes/availability/pricing/station-forecast?date=&stationId= — hedef tarih saatlik forecast (her segment). */
    @GetMapping("/pricing/station-forecast")
    public List<Map<String, Object>> pricingStationForecast(
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "stationId") String stationId) throws IOException {
        return pricingService.stationForecast(date, stationId);
    }

    /** GET /zes/availability/pricing/station-history?stationId=&days= — son N günlük gözlenen doluluk. */
    @GetMapping("/pricing/station-history")
    public List<Map<String, Object>> pricingStationHistory(
            @RequestParam(name = "stationId") String stationId,
            @RequestParam(name = "days", required = false) Integer days) throws IOException {
        return pricingService.stationHistory(stationId, days);
    }

    @GetMapping("/pricing/available-dates")
    public List<String> pricingAvailableDates() throws IOException {
        return pricingService.availableDates();
    }

    @GetMapping("/epdk-connectors/stats/by-snapshot")
    public Map<String, Map<String, Long>> epdkConnectorBySnapshot(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return epdkSnapshotService.statusBySnapshot(date);
    }

    @GetMapping("/epdk-connectors/available-dates")
    public List<String> epdkConnectorAvailableDates() throws IOException {
        return epdkSnapshotService.availableDates();
    }

    @GetMapping("/connector-transform/available-dates")
    public List<String> connectorTransformAvailableDates() throws IOException {
        return transformAnalysisService.availableDates();
    }

    @GetMapping("/station-performance/available-dates")
    public List<String> stationPerformanceAvailableDates() throws IOException {
        return performanceQueryService.availableDates();
    }
}
