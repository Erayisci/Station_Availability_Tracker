package com.charging.ingestion.providers.zes;

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
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Component
public class ZesApiClient {

    private static final String BASE_URL   = "https://api.electripglobalapp.com";
    private static final String USER_AGENT = "ZES/6.19 (com.zorlu.zes; build:171; iOS 26.3.1) Alamofire./5.10.1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ZesApiClient() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }}, new SecureRandom());

        this.httpClient = HttpClient.newBuilder().sslContext(sslContext).build();
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

    public Map<String, Object> fetchNearbyStations(double lat, double lng, String locationHash, double radius) throws Exception {
        String url = BASE_URL + "/nearbyStationsForGuest"
                + "?latitude=" + lat
                + "&locationHash=" + locationHash
                + "&longitude=" + lng
                + "&radius=" + radius;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return objectMapper.readValue(sendAndDecode(req), new TypeReference<>() {});
    }
}
