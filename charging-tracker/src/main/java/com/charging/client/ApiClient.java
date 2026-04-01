package com.charging.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Component
public class ApiClient {

    private static final String BASE_URL = "https://sarjtr.epdk.gov.tr:443/sarjet/api";
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiClient() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }}, new SecureRandom());

        this.httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }

    private String datetimeSuffix() {
        return LocalDateTime.now().format(DATETIME_FMT).replace(" ", "%20");
    }

    private HttpRequest buildRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Dart/3.1 (dart:io)")
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
    }

    private String sendAndDecode(HttpRequest request) throws Exception {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(body));
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = gis.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toString(StandardCharsets.UTF_8);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    public List<Map<String, Object>> fetchStations() throws Exception {
        HttpRequest req = buildRequest(BASE_URL + "/stations");
        return objectMapper.readValue(sendAndDecode(req), new TypeReference<>() {});
    }

    public Map<String, Object> fetchStationDetail(int stationId) throws Exception {
        String url = BASE_URL + "/stations/id/" + stationId + "/" + datetimeSuffix();
        HttpRequest req = buildRequest(url);
        return objectMapper.readValue(sendAndDecode(req), new TypeReference<>() {});
    }

}
