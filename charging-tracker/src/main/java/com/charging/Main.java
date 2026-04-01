package com.charging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = ElasticsearchClientAutoConfiguration.class)
@EnableScheduling
public class Main {

    public static void main(String[] args) {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        SpringApplication.run(Main.class, args);
    }
}
