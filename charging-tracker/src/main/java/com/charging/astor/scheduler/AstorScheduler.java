package com.charging.astor.scheduler;

import com.charging.astor.service.AstorStatusTrackerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class AstorScheduler {

    @Autowired
    private AstorStatusTrackerService trackerService;

    @Scheduled(fixedRateString = "${astor.poll.intervalMs:300000}")
    public void pollAstor() {
        try {
            int count = trackerService.pollAndTrack();
            System.out.printf("[ASTOR POLL] %s — tracked %d stations%n", LocalDateTime.now(), count);
        } catch (Exception e) {
            System.err.printf("[ASTOR POLL ERROR] %s — %s%n", LocalDateTime.now(), e.getMessage());
        }
    }

    @Scheduled(cron = "${astor.daily.cron:0 59 23 * * *}")
    public void indexDaily() {
        String date = LocalDate.now().toString();
        try {
            System.out.println("[ASTOR DAILY INDEX] End-of-day run — closing open slots and indexing...");
            int count = trackerService.indexDailyToElastic(true);
            System.out.printf("[ASTOR DAILY INDEX] astor_stations-%s finalized: %d station docs.%n", date, count);
        } catch (Exception e) {
            System.err.printf("[ASTOR DAILY INDEX ERROR] %s%n", e.getMessage());
        }
    }
}
