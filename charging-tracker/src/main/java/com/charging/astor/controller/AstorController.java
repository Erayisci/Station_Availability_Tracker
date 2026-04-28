package com.charging.astor.controller;

import com.charging.astor.service.AstorStatusTrackerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/astor")
public class AstorController {

    @Autowired
    private AstorStatusTrackerService trackerService;

    /** Anlık bir poll tetikler; status değişimleri H2'ye kaydedilir. */
    @PostMapping("/poll-now")
    public String pollNow() throws Exception {
        int count = trackerService.pollAndTrack();
        return String.format("Polled and tracked %d ASTOR stations.", count);
    }

    /** Günlük indexlemeyi manuel tetikler: astor_stations-{date} oluşturur. */
    @PostMapping("/index-daily")
    public String indexDaily() throws Exception {
        int count = trackerService.indexDailyToElastic(false);
        return String.format("Indexed %d ASTOR stations to daily index.", count);
    }
}
