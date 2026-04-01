package com.charging.zes.controller;

import com.charging.zes.client.ZesApiClient;
import com.charging.zes.elastic.ZesConnectorTransformService;
import com.charging.zes.service.ZesStatusTrackerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/zes")
public class ZesController {

    @Autowired
    private ZesApiClient apiClient;

    @Autowired
    private ZesStatusTrackerService trackerService;

    @Autowired
    private ZesConnectorTransformService connectorTransformService;

    @Value("${zes.poll.lat:39.96282958984371}")
    private double defaultLat;

    @Value("${zes.poll.lng:32.77142333984374}")
    private double defaultLng;

    @Value("${zes.poll.locationHash:b8dae592482b26a57c5368501cd70ef2}")
    private String defaultLocationHash;

    @Value("${zes.poll.radius:12000}")
    private double defaultRadius;

    /** Yakındaki ZES istasyonlarını çeker (ES'e yazmaz). */
    @GetMapping("/fetch-nearby")
    public Map<String, Object> fetchNearby(
            @RequestParam(name = "latitude",     required = false) Double latitude,
            @RequestParam(name = "longitude",    required = false) Double longitude,
            @RequestParam(name = "locationHash", required = false) String locationHash,
            @RequestParam(name = "radius",       required = false) Double radius) throws Exception {
        return apiClient.fetchNearbyStations(
                latitude     != null ? latitude     : defaultLat,
                longitude    != null ? longitude    : defaultLng,
                locationHash != null ? locationHash : defaultLocationHash,
                radius       != null ? radius       : defaultRadius
        );
    }

    /** Anlık bir poll tetikler; status değişimleri H2'ye kaydedilir. */
    @PostMapping("/poll-now")
    public String pollNow() throws Exception {
        int count = trackerService.pollAndTrack();
        return String.format("Polled and tracked %d ZES stations.", count);
    }

    /** Günlük indexlemeyi manuel tetikler: H2'deki availability arrayi ile zes_stations-{date} oluşturur. */
    @PostMapping("/index-daily")
    public String indexDaily() throws Exception {
        int count = trackerService.indexDailyToElastic(false);
        return String.format("Indexed %d ZES stations to daily index.", count);
    }

    /** zes_stations-{date} kaynağından zes_connector_transform-{date} oluşturur. */
    @PostMapping("/index-connector-transform")
    public String indexConnectorTransform(
            @RequestParam(name = "date", required = false) String date) throws Exception {
        int count = connectorTransformService.transformConnectors(date);
        return String.format("Indexed %d connector documents to transform index.", count);
    }
}
