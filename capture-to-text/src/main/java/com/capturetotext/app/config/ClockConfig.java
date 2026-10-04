package com.capturetotext.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// Services ask this Clock for "now" instead of calling Instant.now(),
// so tests can pin time with Clock.fixed(...).
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
