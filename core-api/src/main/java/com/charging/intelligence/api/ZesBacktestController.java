package com.charging.intelligence.api;

import com.charging.intelligence.forecast.ZesForecastBacktestService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Forecast backtest UI endpoints.
 *
 *   GET /zes/backtest/dates              — overlap (forecast ∩ perf) dates
 *   GET /zes/backtest/{date}/summary     — KPIs + per-city breakdown
 *   GET /zes/backtest/{date}/stations    — per-station rows for scatter/tables
 *   GET /zes/backtest/trend              — per-date MAE trend (model vs naive)
 */
@RestController
@RequestMapping("/zes/backtest")
public class ZesBacktestController {

    @Autowired
    private ZesForecastBacktestService backtestService;

    @GetMapping("/dates")
    public ResponseEntity<List<String>> dates() throws IOException {
        return ResponseEntity.ok(backtestService.overlapDates());
    }

    @GetMapping("/{date}/summary")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String date) throws IOException {
        return ResponseEntity.ok(backtestService.summaryFor(date));
    }

    @GetMapping("/{date}/stations")
    public ResponseEntity<List<Map<String, Object>>> stations(@PathVariable String date) throws IOException {
        return ResponseEntity.ok(backtestService.stationsFor(date));
    }

    @GetMapping("/trend")
    public ResponseEntity<List<Map<String, Object>>> trend() throws IOException {
        return ResponseEntity.ok(backtestService.trend());
    }
}
