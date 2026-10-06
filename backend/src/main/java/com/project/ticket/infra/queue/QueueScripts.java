package com.project.ticket.infra.queue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Executable scripts share validation/helpers; all nine keys are passed explicitly. */
public final class QueueScripts {
    public static final String ACTIVE_PERFORMANCES = "queue:active-performances";
    public enum Operation { REGISTER, STATUS, ADMIT, ENTER, CANCEL }
    private static final String COMMON = read("common");
    private static final List<DefaultRedisScript<List>> SCRIPTS = java.util.Arrays.stream(Operation.values())
            .map(operation -> new DefaultRedisScript<>(COMMON + "\n" + read(operation.name().toLowerCase(java.util.Locale.ROOT)), List.class))
            .toList();

    private QueueScripts() { }

    public static List<String> keys(long performanceId) {
        if (performanceId <= 0) throw new IllegalArgumentException("Performance ID must be positive.");
        String prefix = "queue:" + performanceId + ":";
        return List.of(prefix + "waiting", prefix + "waiting-expiry", prefix + "admitted", prefix + "active",
                prefix + "registered-at", prefix + "sequence", prefix + "admit-history", prefix + "admit-rate",
                ACTIVE_PERFORMANCES);
    }

    public static DefaultRedisScript<List> script(Operation operation) {
        return SCRIPTS.get(operation.ordinal());
    }

    private static String read(String name) {
        try {
            return new ClassPathResource("redis/queue/" + name + ".lua").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load queue Lua script: " + name, exception);
        }
    }
}
