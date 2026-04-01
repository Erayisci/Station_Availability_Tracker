package com.charging.zes.overpass;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OverpassApiClient {

    private static final String OVERPASS_URL  = "https://overpass-api.de/api/interpreter";
    private static final int    RADIUS_METERS = 300;

    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OverpassApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public Map<String, Object> fetchPoisForStations(List<Map<String, Object>> stations) throws Exception {
        String query = buildQuery(stations);
        String body  = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        System.out.printf("[OVERPASS] Querying %d stations%n", stations.size());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OVERPASS_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(90))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            System.out.printf("[OVERPASS] OK — %d bytes%n", response.body().length());
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        }

        throw new RuntimeException("[OVERPASS] HTTP " + response.statusCode());
    }

    private String buildQuery(List<Map<String, Object>> stations) {
        StringBuilder sb = new StringBuilder("[out:json][timeout:90];\n(\n");
        for (Map<String, Object> station : stations) {
            double lat = toDouble(station.get("latitude"));
            double lng = toDouble(station.get("longitude"));
            String around = "(around:" + RADIUS_METERS + "," + lat + "," + lng + ")";
            // landuse alanları (polygon)
            sb.append("  way[\"landuse\"~\"residential|retail|commercial|office\"]").append(around).append(";\n");
            // bina tipi (polygon)
            sb.append("  way[\"building\"~\"retail|commercial|residential|apartments|office|supermarket\"]").append(around).append(";\n");
            // dükkanlar (node)
            sb.append("  node[\"shop\"]").append(around).append(";\n");
            // amenity noktaları (node)
            sb.append("  node[\"amenity\"~\"restaurant|cafe|bar|fast_food|food_court|supermarket|marketplace|bank|office\"]").append(around).append(";\n");
        }
        sb.append(");\nout center tags;");
        return sb.toString();
    }

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(val));
    }
}
