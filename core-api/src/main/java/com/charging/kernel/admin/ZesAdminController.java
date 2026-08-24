package com.charging.kernel.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * Admin endpoints for diagnosing and cleaning up corrupted performance indices.
 *
 *   GET    /zes/admin/perf-indices/health                  — scan all, with status per date
 *   GET    /zes/admin/perf-indices/health/{date}           — single date detail
 *   DELETE /zes/admin/perf-indices/{date}?confirm=true     — drop perf-{date} + every forecast
 *                                                            index that used it as source
 *   DELETE /zes/admin/perf-indices/broken?confirm=true     — drop every BROKEN perf index
 *                                                            (and their bound forecasts);
 *                                                            use confirm=false (default) for dry-run
 *
 * Thresholds for "broken" classification are tunable via query params on every
 * endpoint: meanThreshold, maxThreshold, stddevThreshold.
 */
@RestController
@RequestMapping("/zes/admin")
public class ZesAdminController {

    @Autowired
    private ZesPerformanceHealthService healthService;

    @GetMapping("/perf-indices/health")
    public ResponseEntity<Map<String, Object>> scanAll(
            @RequestParam(defaultValue = "1.0") double meanThreshold,
            @RequestParam(defaultValue = "5.0") double maxThreshold,
            @RequestParam(defaultValue = "0.5") double stddevThreshold
    ) throws IOException {
        return ResponseEntity.ok(healthService.scanAll(meanThreshold, maxThreshold, stddevThreshold));
    }

    @GetMapping("/perf-indices/health/{date}")
    public ResponseEntity<Map<String, Object>> checkOne(
            @PathVariable String date,
            @RequestParam(defaultValue = "1.0") double meanThreshold,
            @RequestParam(defaultValue = "5.0") double maxThreshold,
            @RequestParam(defaultValue = "0.5") double stddevThreshold
    ) throws IOException {
        return ResponseEntity.ok(healthService.check(date, meanThreshold, maxThreshold, stddevThreshold));
    }

    @DeleteMapping("/perf-indices/{date}")
    public ResponseEntity<?> deleteOne(
            @PathVariable String date,
            @RequestParam(defaultValue = "false") boolean confirm
    ) throws IOException {
        if (!confirm) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "confirm=true required",
                    "hint",  "DELETE /zes/admin/perf-indices/" + date + "?confirm=true",
                    "warning", "this drops perf-" + date + " AND every forecast index bound to it"
            ));
        }
        return ResponseEntity.ok(healthService.deleteIndex(date, true));
    }

    @DeleteMapping("/perf-indices/broken")
    public ResponseEntity<?> deleteBroken(
            @RequestParam(defaultValue = "false") boolean confirm,
            @RequestParam(defaultValue = "1.0") double meanThreshold,
            @RequestParam(defaultValue = "5.0") double maxThreshold,
            @RequestParam(defaultValue = "0.5") double stddevThreshold
    ) throws IOException {
        return ResponseEntity.ok(healthService.deleteBroken(
                meanThreshold, maxThreshold, stddevThreshold,
                true,           // cascade
                !confirm        // dry-run unless confirm=true
        ));
    }
}
