package com.charging.controller;

import com.charging.elastic.AvailabilityAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sarjet/availability")
public class AvailabilityController {

    @Autowired
    private AvailabilityAnalysisService analysisService;

    /**
     * Search availability with optional ES-side filters and sort.
     *
     * GET /sarjet/availability/search
     *   ?date=2026-03-02          (default: today)
     *   &district=<districtid>
     *   &city=<cityid>
     *   &brand=<brand>
     *   &socketType=<type>        (e.g. CCS, CHAdeMO, AC)
     *   &status=<status>          (e.g. AVAILABLE, OCCUPIED, RESERVED)
     *   &from=0  &size=20
     *   &sort=id  &order=asc|desc
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam(name = "date",       required = false)           String date,
            @RequestParam(name = "district",   required = false)           String districtId,
            @RequestParam(name = "city",       required = false)           String cityId,
            @RequestParam(name = "brand",      required = false)           String brand,
            @RequestParam(name = "socketType", required = false)           String socketType,
            @RequestParam(name = "status",     required = false)           String status,
            @RequestParam(name = "from",       defaultValue = "0")         int from,
            @RequestParam(name = "size",       defaultValue = "20")        int size,
            @RequestParam(name = "sort",       defaultValue = "id")        String sort,
            @RequestParam(name = "order",      defaultValue = "asc")       String order
    ) throws IOException {
        return analysisService.searchStations(date, districtId, cityId, brand, socketType, status, from, size, sort, order);
    }

    /**
     * Socket status counts per district (all aggregation in ES).
     * GET /sarjet/availability/stats/by-district?date=2026-03-02
     * Response: { "34": { "AVAILABLE": 120, "OCCUPIED": 45 }, "06": { ... } }
     */
    @GetMapping("/stats/by-district")
    public Map<String, Map<String, Long>> statsByDistrict(
            @RequestParam(name = "date", required = false) String date
    ) throws IOException {
        return analysisService.statusCountsByDistrict(date);
    }

    /**
     * Socket status counts per city.
     * GET /sarjet/availability/stats/by-city?date=2026-03-02
     */
    @GetMapping("/stats/by-city")
    public Map<String, Map<String, Long>> statsByCity(
            @RequestParam(name = "date", required = false) String date
    ) throws IOException {
        return analysisService.statusCountsByCity(date);
    }

    /**
     * Socket status counts per connector type (CCS, CHAdeMO, AC, …).
     * GET /sarjet/availability/stats/by-socket-type?date=2026-03-02
     * Response: { "CCS": { "AVAILABLE": 300, "OCCUPIED": 80 }, "CHAdeMO": { ... } }
     */
    @GetMapping("/stats/by-socket-type")
    public Map<String, Map<String, Long>> statsBySocketType(
            @RequestParam(name = "date", required = false) String date
    ) throws IOException {
        return analysisService.statusCountsBySocketType(date);
    }

    /**
     * Socket status counts per brand.
     * GET /sarjet/availability/stats/by-brand?date=2026-03-02
     */
    @GetMapping("/stats/by-brand")
    public Map<String, Map<String, Long>> statsByBrand(
            @RequestParam(name = "date", required = false) String date
    ) throws IOException {
        return analysisService.statusCountsByBrand(date);
    }

    /**
     * Socket status counts distributed over fixed time slots of the day.
     *
     * GET /sarjet/availability/stats/by-time-slot
     *   ?date=2026-03-02
     *   &intervalMinutes=30        (default: 30 → half-hour buckets)
     *   &district=34               (optional)
     *   &socketType=CCS            (optional)
     *
     * Response example (30-min slots):
     * {
     *   "06:00": { "AVAILABLE": 890, "IN_USE": 12 },
     *   "06:30": { "AVAILABLE": 850, "IN_USE": 55 },
     *   "08:00": { "AVAILABLE": 420, "IN_USE": 380 },
     *   ...
     * }
     */
    @GetMapping("/stats/by-time-slot")
    public Map<String, Map<String, Long>> statsByTimeSlot(
            @RequestParam(name = "date",            required = false)      String date,
            @RequestParam(name = "intervalMinutes", defaultValue = "30")   int intervalMinutes,
            @RequestParam(name = "district",        required = false)      String districtId,
            @RequestParam(name = "socketType",      required = false)      String socketType
    ) throws IOException {
        return analysisService.statusByTimeSlot(date, intervalMinutes, districtId, socketType);
    }
}
