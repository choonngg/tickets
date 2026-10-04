package com.project.ticket.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class ClockConfigTest {
    @Test
    void clockZoneIsUtcRegardlessOfJvmDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneOffset.UTC);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
