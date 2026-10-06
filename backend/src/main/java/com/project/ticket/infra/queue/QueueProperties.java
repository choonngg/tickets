package com.project.ticket.infra.queue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticket.queue")
public record QueueProperties(
        @DefaultValue("1000") @Min(1) int maxConcurrent,
        @DefaultValue("100") @Min(1) int admitPerCycle,
        @DefaultValue("60000") @Min(1) long admittedTtlMs,
        @DefaultValue("300000") @Min(1) long activeTtlMs,
        @DefaultValue("200") @Min(1) @Max(200) int cleanupBatchSize,
        @DefaultValue("30000") @Min(1) long reregisterGraceMs,
        @DefaultValue("100") @Min(1) int fastRankThreshold,
        @DefaultValue("1000") @Min(1) int mediumRankThreshold,
        @DefaultValue("3000") @Min(1) long fastPollMs,
        @DefaultValue("10000") @Min(1) long mediumPollMs,
        @DefaultValue("20000") @Min(1) long slowPollMs) {
    public QueueProperties {
        if (fastRankThreshold >= mediumRankThreshold || fastPollMs > mediumPollMs || mediumPollMs > slowPollMs) {
            throw new IllegalArgumentException("Queue polling thresholds and intervals must be ordered.");
        }
    }

    public static QueueProperties defaults() {
        return new QueueProperties(1000, 100, 60_000, 300_000, 200, 30_000, 100, 1000, 3000, 10_000, 20_000);
    }
}
