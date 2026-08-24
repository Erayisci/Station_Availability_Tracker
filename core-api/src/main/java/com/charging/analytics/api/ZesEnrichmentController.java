package com.charging.analytics.api;

import com.charging.analytics.enrichment.ZesStationEnrichmentService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/zes")
public class ZesEnrichmentController {

    @Autowired
    private ZesStationEnrichmentService enrichmentService;

    /**
     * Adım 1-6: ES'ten istasyonları çek, Overpass'tan POI'ları al,
     * environment_type ata, zes_station_environment indexine yaz.
     * Daha önce işlenmiş istasyonlar atlanır; exception gelirse durur.
     *
     * POST /zes/enrich-stations
     * POST /zes/enrich-stations?date=2026-03-18
     */
    @PostMapping("/enrich-stations")
    public String enrichStations(
            @RequestParam(name = "date", required = false) String date) throws Exception {
        int written = enrichmentService.enrichAndWriteEnvironments(date);
        return String.format("Enrichment complete: %d records written to zes_station_environment.", written);
    }
}
