package com.charging.controller;

import com.charging.elastic.IndexInitService;
import com.charging.elastic.SocketTransformService;
import com.charging.service.ChargingService;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sarjet/elastic")
public class ChargingController {

    @Autowired
    private ChargingService chargingService;

    @Autowired
    private IndexInitService elasticService;

    @Autowired
    private SocketTransformService socketTransformService;

    // --- template setup ---

    @PostMapping("/ensure-stations-template")
    public void ensureStationsTemplate() throws Exception {
        elasticService.ensureStationsTemplate();
    }

    @PostMapping("/ensure-availability-template")
    public void ensureAvailabilityTemplate() throws Exception {
        elasticService.ensureAvailabilityTemplate();
    }

    // --- index creation ---

    @PostMapping("/create-stations-index")
    public String createStationsIndex() throws Exception {
        return elasticService.createStationsIndex();
    }

    @PostMapping("/create-today-availability-index")
    public String createTodayAvailabilityIndex() throws Exception {
        return elasticService.createTodayAvailabilityIndex();
    }

    // --- fill with data ---

    @PostMapping("/index-all-stations")
    public String indexAllStations() throws Exception {
        int count = chargingService.indexAllStations();
        return "Indexed " + count + " stations.";
    }

    @PostMapping("/index-station-detail")
    public String indexStationDetail() throws Exception {
        int count = chargingService.indexStationDetail();
        return "Indexed availability for " + count + " stations.";
    }

    /** Sequential indexing, one request at a time with a delay between each. */
    @PostMapping("/index-station-detail-sequential")
    public String indexStationDetailSequential(
            @RequestParam(name = "offset",  defaultValue = "0")   int offset,
            @RequestParam(name = "limit",   defaultValue = "-1")  int limit,
            @RequestParam(name = "delayMs", defaultValue = "500") long delayMs) throws Exception {
        int count = chargingService.indexStationDetailSequential(offset, limit, delayMs);
        return String.format("Indexed %d stations (offset=%d, delay=%dms).", count, offset, delayMs);
    }

    /** Batched concurrent indexing. workers=parallel requests per batch, delayMs=pause between batches. */
    @PostMapping("/index-station-detail-throttled")
    public String indexStationDetailThrottled(
            @RequestParam(name = "offset",   defaultValue = "0")   int offset,
            @RequestParam(name = "limit",    defaultValue = "-1")  int limit,
            @RequestParam(name = "workers",  defaultValue = "5")   int workers,
            @RequestParam(name = "delayMs",  defaultValue = "500") long delayMs) throws Exception {
        int count = chargingService.indexStationDetailThrottled(offset, limit, workers, delayMs);
        return String.format("Indexed %d stations (offset=%d, workers=%d, delay=%dms).", count, offset, workers, delayMs);
    }

    @GetMapping("/admin/migrate-stations")
    public String migrateStations() throws IOException {
        elasticService.migrateStationsIndexIfNeeded();
        return "done";
    }

    /** Flattens the daily availability index into a socket-level transform index. */
    @PostMapping("/admin/build-socket-transform")
    public String buildSocketTransform(
            @RequestParam(name = "date", required = false) String date) throws IOException {
        int count = socketTransformService.transformAvailabilityToSocketIndex(date);
        return String.format("Transform complete: %d flat socket records indexed.", count);
    }
}
