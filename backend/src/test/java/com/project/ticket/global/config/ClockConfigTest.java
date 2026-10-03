package com.project.ticket.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class ClockConfigTest {
    @Test
    void clockZoneIsAsiaSeoulRegardlessOfJvmDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
