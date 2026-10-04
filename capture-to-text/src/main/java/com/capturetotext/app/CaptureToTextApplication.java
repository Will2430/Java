package com.capturetotext.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling runs the OutboxRelay's @Scheduled poller.
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class CaptureToTextApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaptureToTextApplication.class, args);
    }
}
