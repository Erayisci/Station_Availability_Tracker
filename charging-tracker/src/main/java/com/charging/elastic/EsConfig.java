package com.charging.elastic;

import org.apache.http.HttpHost;

/**
 * Central Elasticsearch connection config. Reads the host/port from environment
 * variables so the same build works both locally and inside Docker.
 *
 * <ul>
 *   <li>{@code ELASTICSEARCH_HOST} — default {@code localhost}</li>
 *   <li>{@code ELASTICSEARCH_PORT} — default {@code 9200}</li>
 * </ul>
 *
 * The localhost defaults preserve the previous hardcoded local-dev behavior.
 */
public final class EsConfig {

    private EsConfig() {
    }

    public static HttpHost host() {
        String host = envOrDefault("ELASTICSEARCH_HOST", "localhost");
        int port = Integer.parseInt(envOrDefault("ELASTICSEARCH_PORT", "9200"));
        return new HttpHost(host, port);
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
