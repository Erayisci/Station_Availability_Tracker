package com.charging.astor.controller;

import com.charging.astor.elastic.AstorAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/astor/availability")
public class AstorAnalysisController {

    @Autowired
    private AstorAnalysisService analysisService;

    /** Zaman dilimlerine göre istasyon durumu sayıları.
     *  Returns: { "HH:mm" → { status → count } } */
    @GetMapping("/stats/by-time-slot")
    public Map<String, Map<String, Long>> statsByTimeSlot(
            @RequestParam(name = "date",                 required = false)   String date,
            @RequestParam(name = "intervalMinutes",      defaultValue = "30") int intervalMinutes,
            @RequestParam(name = "tenantCode",           required = false)   String tenantCode,
            @RequestParam(name = "connectorCurrentType", required = false)   String connectorCurrentType) throws IOException {
        return analysisService.statusByTimeSlot(date, intervalMinutes, tenantCode, connectorCurrentType);
    }

    /** İl koduna göre toplam süre dağılımı — Top 20.
     *  Returns: { locationCode → { status → totalMinutes } } */
    @GetMapping("/stats/by-location")
    public Map<String, Map<String, Double>> statsByLocation(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        return analysisService.durationByLocationCode(date);
    }

    /** İstasyon arama — status, connectorCurrentType, tenantCode, locationCode, address, time filtreleri.
     *  Returns full station docs with availability array. */
    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam(name = "date",                 required = false)    String date,
            @RequestParam(name = "status",               required = false)    String status,
            @RequestParam(name = "connectorCurrentType", required = false)    String connectorCurrentType,
            @RequestParam(name = "tenantCode",           required = false)    String tenantCode,
            @RequestParam(name = "locationCode",         required = false)    String locationCode,
            @RequestParam(name = "address",              required = false)    String address,
            @RequestParam(name = "time",                 required = false)    String time,
            @RequestParam(name = "from",                 defaultValue = "0")  int from,
            @RequestParam(name = "size",                 defaultValue = "20") int size) throws IOException {
        return analysisService.searchStations(
                date, status, connectorCurrentType, tenantCode, locationCode, address, time, from, size);
    }
}
