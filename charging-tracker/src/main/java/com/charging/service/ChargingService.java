package com.charging.service;

import com.charging.client.ApiClient;
import com.charging.elastic.IndexInitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/*
    bu servis ApiClient aracılığıyla sarjtr endpointlerinden ham veriyi alıp
    elasticserviceteki indexleme servis methodlarını çağırır. Availability için
    multithreaded bir şekilde çalışır çünkü her istasyon için ayrı request atılır ve toplamda
    11780 civarı istasyon var (takribi 5 dk sürmeli)

    bu servisi çağıran controller endpoint ileride scheduled bir şekilde
    her gün 1 kere bu servisi çağırıp yeni daily index yaratmalı
 */
@Service
public class ChargingService {

    private static final int WORKERS = 50;

    @Autowired
    private ApiClient apiClient;

    @Autowired
    private IndexInitService elasticService;

    public int indexAllStations() throws Exception {
        List<Map<String, Object>> stations = apiClient.fetchStations();
        elasticService.bulkIndexStations(stations);
        return stations.size();
    }

    public int indexStationDetail() throws Exception {
        elasticService.createTodayAvailabilityIndex();

        List<Integer> stationIds = elasticService.getAllStationIdsFromIndex();

        if (stationIds.isEmpty()) {
            System.out.println("[INFO] Stations index is empty, falling back to API.");
            List<Map<String, Object>> stations = apiClient.fetchStations();
            stationIds = stations.stream()
                    .map(s -> ((Number) s.get("id")).intValue())
                    .collect(Collectors.toList());
        }

        int total = stationIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        AtomicInteger completed = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = stationIds.stream()
                .map(stationId -> CompletableFuture.runAsync(() -> {
                    try {
                        Map<String, Object> detail = apiClient.fetchStationDetail(stationId);
                        elasticService.indexStationDetail(detail);
                    } catch (Exception e) {
                        System.err.printf("[ERROR] Station %d: %s%n", stationId, e.getMessage());
                    }
                    int done = completed.incrementAndGet();
                    if (done % 500 == 0) {
                        System.out.printf("  Progress: %d/%d%n", done, total);
                    }
                }, executor))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        return total;
    }

    /**
     * Sequential indexing with a fixed delay between each request.
     * offset: skip first N stations. limit=-1 means all remaining.
     */
    public int indexStationDetailSequential(int offset, int limit, long delayMs) throws Exception {
        elasticService.createTodayAvailabilityIndex();

        List<Integer> stationIds = elasticService.getAllStationIdsFromIndex();
        if (stationIds.isEmpty()) {
            System.out.println("[INFO] Stations index is empty, falling back to API.");
            List<Map<String, Object>> stations = apiClient.fetchStations();
            stationIds = stations.stream()
                    .map(s -> ((Number) s.get("id")).intValue())
                    .collect(Collectors.toList());
        }

        int from = Math.min(offset, stationIds.size());
        int to   = (limit <= 0) ? stationIds.size() : Math.min(offset + limit, stationIds.size());
        List<Integer> work = stationIds.subList(from, to);
        int total = work.size();

        System.out.printf("[SEQUENTIAL] %d stations (offset=%d), delayMs=%d%n", total, offset, delayMs);

        int ok = 0, fail = 0;
        for (int i = 0; i < total; i++) {
            int stationId = work.get(i);
            try {
                Map<String, Object> detail = apiClient.fetchStationDetail(stationId);
                elasticService.indexStationDetail(detail);
                ok++;
                System.out.printf("  [%d/%d] station %d -> OK%n", i + 1, total, stationId);
            } catch (Exception e) {
                fail++;
                System.err.printf("  [%d/%d] station %d -> FAILED: %s%n", i + 1, total, stationId, e.getMessage());
            }
            if (i < total - 1) Thread.sleep(delayMs);
        }

        System.out.printf("[SEQUENTIAL] Done: %d ok, %d failed (offset=%d)%n", ok, fail, offset);
        return ok;
    }

    /**
     * Temporary: batched concurrent indexing with rate limiting.
     * Fires `workers` requests in parallel, waits `delayMs` between batches.
     * offset: skip first N stations. limit=-1 means all remaining.
     */
    public int indexStationDetailThrottled(int offset, int limit, int workers, long delayMs) throws Exception {
        elasticService.createTodayAvailabilityIndex();

        List<Integer> stationIds = elasticService.getAllStationIdsFromIndex();
        if (stationIds.isEmpty()) {
            System.out.println("[INFO] Stations index is empty, falling back to API.");
            List<Map<String, Object>> stations = apiClient.fetchStations();
            stationIds = stations.stream()
                    .map(s -> ((Number) s.get("id")).intValue())
                    .collect(Collectors.toList());
        }

        int from = Math.min(offset, stationIds.size());
        int to   = (limit <= 0) ? stationIds.size() : Math.min(offset + limit, stationIds.size());
        List<Integer> work = stationIds.subList(from, to);
        int total = work.size();

        System.out.printf("[THROTTLED] %d stations (offset=%d), workers=%d, delayMs=%d%n",
                total, offset, workers, delayMs);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        AtomicInteger ok   = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        for (int bStart = 0; bStart < total; bStart += workers) {
            int bEnd = Math.min(bStart + workers, total);
            List<Integer> subBatch = work.subList(bStart, bEnd);

            List<CompletableFuture<Void>> futures = subBatch.stream()
                    .map(stationId -> CompletableFuture.runAsync(() -> {
                        try {
                            Map<String, Object> detail = apiClient.fetchStationDetail(stationId);
                            elasticService.indexStationDetail(detail);
                            int done = ok.incrementAndGet();
                            System.out.printf("  [%d/%d] station %d -> OK%n", done + fail.get(), total, stationId);
                        } catch (Exception e) {
                            int failed = fail.incrementAndGet();
                            System.err.printf("  [%d/%d] station %d -> FAILED: %s%n",
                                    ok.get() + failed, total, stationId, e.getMessage());
                        }
                    }, executor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (bEnd < total) Thread.sleep(delayMs);
        }

        executor.shutdown();
        System.out.printf("[THROTTLED] Done: %d ok, %d failed (offset=%d)%n", ok.get(), fail.get(), offset);
        return ok.get();
    }
}
