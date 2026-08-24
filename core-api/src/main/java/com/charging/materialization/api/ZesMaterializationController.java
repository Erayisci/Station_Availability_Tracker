package com.charging.materialization.api;

import com.charging.ingestion.ProviderRegistry;
import com.charging.materialization.performance.PerformanceBackfillService;
import com.charging.materialization.performance.StationPerformanceIndexService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * L2 — Manual triggers for the index chain.
 *
 * The chain normally advances on its own schedule (23:59 finalize → 00:15
 * performance build, see {@code ingestion/providers/zes/ZesScheduler}). These
 * endpoints exist for repair and backfill: they are the only write-side
 * operations exposed under {@code /zes/availability}.
 *
 * <h2>The optional {@code provider} parameter</h2>
 * Both endpoints accept {@code ?provider=} and fall back to
 * {@link ProviderRegistry#soleProviderId()} when it is omitted, so existing
 * callers keep working unchanged. The fallback deliberately throws once a second
 * network is registered: a repair endpoint has no basis for guessing which
 * operator's data to rebuild, and silently picking one would be worse than a
 * clear error naming the parameter to supply.
 *
 * <p>The {@code /zes/} path prefix is legacy — it predates the provider
 * parameter and is kept because the console's API layer hard-codes it. It no
 * longer implies the request concerns ZES.
 */
@RestController
@RequestMapping("/zes/availability")
public class ZesMaterializationController {

    @Autowired
    private StationPerformanceIndexService performanceIndexService;

    @Autowired
    private PerformanceBackfillService backfillService;

    @Autowired
    private ProviderRegistry providers;

    /**
     * {provider}_connector_transform-{date} → {provider}_station_performance-{date}
     * transform'unu tetikler.
     * Scheduler zaten gece 00:15'te çalıştırır; bu endpoint manuel/backfill için kullanılır.
     *
     * POST /zes/availability/station-performance/build?date=2026-04-14&radius=1.0
     */
    @PostMapping("/station-performance/build")
    public Map<String, Object> buildPerformanceIndex(
            @RequestParam(name = "date",     required = false)   String date,
            @RequestParam(name = "provider", required = false)   String provider,
            @RequestParam(name = "radius", defaultValue = "1.0") double radius) throws Exception {
        String providerId = resolveProvider(provider);
        int count = performanceIndexService.buildPerformanceIndex(providerId, date, radius);
        return Map.of(
                "provider", providerId,
                "indexed_stations", count,
                "date", date != null ? date : "today");
    }

    /**
     * Backfills missing performance indexes for a date range.
     * Only creates indexes where connector_transform exists but performance is missing.
     *
     * POST /zes/availability/station-performance/backfill
     *   ?startDate=2026-04-01&endDate=2026-05-14&radius=1.0
     */
    @PostMapping("/station-performance/backfill")
    public Map<String, Object> backfillPerformanceIndex(
            @RequestParam(name = "startDate") String startDate,
            @RequestParam(name = "endDate")   String endDate,
            @RequestParam(name = "provider", required = false)    String provider,
            @RequestParam(name = "radius", defaultValue = "1.0")  double radius,
            @RequestParam(name = "force",  defaultValue = "false") boolean force) {
        return backfillService.backfill(resolveProvider(provider), startDate, endDate, radius, force);
    }

    private String resolveProvider(String provider) {
        return provider != null ? provider : providers.soleProviderId();
    }
}
