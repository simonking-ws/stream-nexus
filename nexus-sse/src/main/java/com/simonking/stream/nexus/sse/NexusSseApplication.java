package com.simonking.stream.nexus.sse;

import com.simonking.stream.nexus.sse.config.AdminAuthProperties;
import com.simonking.stream.nexus.sse.config.SseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SseProperties.class, AdminAuthProperties.class})
public class NexusSseApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusSseApplication.class, args);
    }

}
