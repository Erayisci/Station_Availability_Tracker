package com.charging.intelligence.api;

import com.charging.intelligence.forecast.ZesForecastService;
import com.charging.intelligence.opportunity.ZesOpportunityService;
import com.charging.intelligence.pricing.ZesPricingService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * L4 — Intelligence read edge for ZES.
 *
 * Every endpoint here reads an index that the <b>Python</b> pipeline produced
 * ({@code zes_station_forecast-*}, {@code zes_station_opportunity-*},
 * {@code zes_station_hourly_forecast-*}, {@code zes_station_hourly_performance-*}).
 * Java never computes these values — it is a pure reader across the
 * Elasticsearch contract. See {@code kernel/es/IndexCatalog}.
 *
 * The pricing endpoints deliberately expose only <em>data + assumptions</em>:
 * the discount decision is made by the operator in the dashboard and revenue is
 * simulated browser-side in {@code pricingModel.js}, mirroring
 * {@code intelligence/pricing/demand_model.py}.
 */
@RestController
@RequestMapping("/zes/availability")
public class ZesIntelligenceController {

    @Autowired
    private ZesOpportunityService opportunityService;

    @Autowired
    private ZesForecastService forecastService;

    @Autowired
    private ZesPricingService pricingService;

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
}
