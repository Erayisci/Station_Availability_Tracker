package com.charging.zes.controller;

import com.charging.zes.elastic.ZesAnalysisService;
import com.charging.zes.elastic.ZesConnectorTransformAnalysisService;
import com.charging.zes.elastic.ZesEnvironmentAnalysisService;
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
