package com.charging.materialization.performance;

import com.charging.materialization.connectors.ConnectorTransformService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds {@code {provider}_station_performance-{date}} across a date range.
 *
 * Backfill exists because the ES chain is <em>derived</em> state: H2 only keeps
 * {@code zes.history.keepDays} of authoritative slots, but every index from
 * {@code {provider}_connector_transform-*} downwards can be recomputed at any
 * time from the day it was materialised. A missing or corrupt performance index
 * is therefore always repairable without re-polling the provider.
 *
 * Ordering constraint: a date can only be built when its connector_transform
 * index exists — the stage below it in the chain. Dates that fail that check
 * are reported as SKIPPED rather than treated as an error.
 */
@Service
public class PerformanceBackfillService {

    @Autowired
    private ConnectorTransformService connectorTransformService;

    @Autowired
    private StationPerformanceIndexService performanceIndexService;

    /**
     * @param providerId which network's chain to repair
     * @param force      rebuild dates whose performance index already exists
     * @return per-date outcome plus SKIPPED / EXISTING / BUILT / FAILED counts
     */
    public Map<String, Object> backfill(String providerId, String startDate, String endDate,
                                        double radius, boolean force) {
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
                    connectorTransformService.indexExists(providerId, dateStr);

                if (!hasConnectorTransform) {
                    result.put("status", "SKIPPED");
                    result.put("reason", "connector_transform index missing");
                    skipped++;
                    details.add(result);
                    continue;
                }

                // Skip if already exists and force not requested
                if (!force && performanceIndexService.indexExists(providerId, dateStr)) {
                    result.put("status", "EXISTING");
                    existing++;
                    details.add(result);
                    continue;
                }

                // Build (or rebuild) performance index
                int count = performanceIndexService.buildPerformanceIndex(providerId, dateStr, radius);
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

        long totalDates = ChronoUnit.DAYS.between(start, end) + 1;

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
}
