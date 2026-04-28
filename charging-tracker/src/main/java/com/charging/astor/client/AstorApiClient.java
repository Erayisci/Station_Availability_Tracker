package com.charging.astor.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Component
public class AstorApiClient {

    private static final String BASE_URL = "https://api-gw.beefull.io/api/beefull-charge-management";

    @Value("${astor.api.userAgent:astor/79 CFNetwork/3860.400.51 Darwin/25.3.0}")
    private String userAgent;

    @Value("${astor.api.authorization}")
    private String authorization;

    @Value("${astor.api.tenantCode:beefull,astor}")
    private String tenantCode;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AstorApiClient() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }}, new SecureRandom());

        this.httpClient = HttpClient.newBuilder().sslContext(sslContext).build();
    }

    public List<Map<String, Object>> fetchAllStations() throws Exception {
        String url = BASE_URL + "/grouped-charge-points-alternative"
                + "?columns=id,coordinate,unitName,address,tenantCode,availabilityStatusCode"
                + ",closesAt,opensAt,connectorCurrentType,maxPower,locationCode,vendorCode,lastCommunication"
                + "&serviceTypeCode=ev"
                + "&isVisible=true"
                + "&tenantCode=" + tenantCode
                + "&connectorCurrentType=ac,dc";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Authorization", "Basic " + authorization)
                .GET()
                .build();

        String body = sendAndDecode(req);
        return objectMapper.readValue(body, new TypeReference<>() {});
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
}
