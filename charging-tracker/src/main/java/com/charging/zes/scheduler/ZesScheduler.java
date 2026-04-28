package com.charging.zes.scheduler;

import com.charging.zes.elastic.ZesConnectorTransformService;
import com.charging.zes.elastic.ZesStationPerformanceIndexService;
import com.charging.zes.service.ZesStatusTrackerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class ZesScheduler {

    @Autowired
    private ZesStatusTrackerService trackerService;

    @Autowired
    private ZesConnectorTransformService connectorTransformService;

    @Autowired
    private ZesStationPerformanceIndexService performanceIndexService;

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
            int connectorCount = connectorTransformService.transformConnectors(date);
            System.out.printf("[ZES DAILY TRANSFORM] zes_connector_transform-%s finalized: %d connector docs.%n", date, connectorCount);

            System.out.println("[ZES DAILY PERFORMANCE] Building zes_station_performance index...");
            int performanceCount = performanceIndexService.buildPerformanceIndex(date, 1.0);
            System.out.printf("[ZES DAILY PERFORMANCE] zes_station_performance-%s finalized: %d station docs.%n", date, performanceCount);
        } catch (Exception e) {
            System.err.printf("[ZES DAILY INDEX ERROR] %s%n", e.getMessage());
        }
    }
}
