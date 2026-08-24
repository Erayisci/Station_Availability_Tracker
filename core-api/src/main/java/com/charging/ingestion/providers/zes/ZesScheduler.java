package com.charging.ingestion.providers.zes;

import com.charging.materialization.connectors.ConnectorTransformService;
import com.charging.materialization.performance.StationPerformanceIndexService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ZES zincirinin zamanlaması.
 *
 * <p>Zamanlayıcı sağlayıcıya özgü kalır (cadence bir ingestion özelliğidir), ama
 * çağırdığı L2 servisleri artık sağlayıcıdan bağımsız; hangi ağın işlendiğini
 * {@link ZesStatusTrackerService#providerId()} ile açıkça geçirir. İkinci bir ağ
 * eklendiğinde ona kendi zamanlayıcısı yazılır ve biri düşerken diğeri etkilenmez
 * (bkz. {@code StationStatusProvider} — hata yalıtımı değişmezi).
 */
@Component
public class ZesScheduler {

    @Autowired
    private ZesStatusTrackerService trackerService;

    @Autowired
    private ConnectorTransformService connectorTransformService;

    @Autowired
    private StationPerformanceIndexService performanceIndexService;

    @Scheduled(fixedRateString = "${zes.poll.intervalMs:300000}")
    public void pollZes() {
        try {
            int count = trackerService.pollAndTrack();
            System.out.printf("[ZES POLL] %s — tracked %d stations%n", LocalDateTime.now(), count);
        } catch (Exception e) {
            System.err.printf("[ZES POLL ERROR] %s — %s%n", LocalDateTime.now(), e.getMessage());
        }
    }

    @Scheduled(cron = "${zes.daily.cron:0 59 23 * * *}")
    public void indexDaily() {
        String date = LocalDate.now().toString();
        try {
            System.out.println("[ZES DAILY INDEX] End-of-day run — closing open slots and rebuilding zes_stations index...");
            int stationCount = trackerService.indexDailyToElastic(true);
            System.out.printf("[ZES DAILY INDEX] zes_stations-%s finalized: %d station docs.%n", date, stationCount);

            System.out.println("[ZES DAILY TRANSFORM] Rebuilding zes_connector_transform index...");
            int connectorCount = connectorTransformService.transformConnectors(trackerService.providerId(), date);
            System.out.printf("[ZES DAILY TRANSFORM] zes_connector_transform-%s finalized: %d connector docs.%n", date, connectorCount);

            // Performance index will be auto-built at 00:15 (see autoBuildPerformanceIndex below)
            System.out.println("[ZES DAILY] Daily finalization complete. Performance index will be built at 00:15.");
        } catch (Exception e) {
            System.err.printf("[ZES DAILY INDEX ERROR] %s%n", e.getMessage());
        }
    }

    /**
     * Automatically builds performance index for previous day at 00:15.
     * Runs after daily finalization (23:59) to ensure connector_transform is ready.
     *
     * This scheduler:
     * - Checks if connector_transform index exists for yesterday
     * - Skips if performance index already exists (idempotent)
     * - Builds performance index with 1.0km radius
     * - Logs success/failure for monitoring
     */
    @Scheduled(cron = "${zes.performance.autoBuildCron:0 15 0 * * *}")  // 00:15:00 daily
    public void autoBuildPerformanceIndex() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String date = yesterday.toString();
        String providerId = trackerService.providerId();

        try {
            // Check if connector_transform index exists
            if (!connectorTransformService.indexExists(providerId, date)) {
                System.err.printf(
                    "[PERF AUTO-BUILD SKIP] connector_transform-%s does not exist%n",
                    date
                );
                return;
            }

            // Check if performance index already exists (idempotent)
            if (performanceIndexService.indexExists(providerId, date)) {
                System.out.printf(
                    "[PERF AUTO-BUILD SKIP] performance-%s already exists%n",
                    date
                );
                return;
            }

            System.out.printf(
                "[PERF AUTO-BUILD START] Building performance index for %s%n",
                date
            );

            int count = performanceIndexService.buildPerformanceIndex(providerId, date, 1.0);

            System.out.printf(
                "[PERF AUTO-BUILD DONE] %d stations indexed for %s%n",
                count, date
            );

        } catch (Exception e) {
            System.err.printf(
                "[PERF AUTO-BUILD ERROR] %s — %s%n",
                date, e.getMessage()
            );
            e.printStackTrace();
        }
    }
}
