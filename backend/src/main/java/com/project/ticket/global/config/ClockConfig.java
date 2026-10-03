package com.project.ticket.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    // Phase 2에서 Instant/UTC로 전환할 때 이 고정 zone을 제거한다.
    static final ZoneId SALES_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SALES_ZONE);
    }
}
