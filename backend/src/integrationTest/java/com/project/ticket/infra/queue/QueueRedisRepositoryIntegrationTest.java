package com.project.ticket.infra.queue;

import static org.assertj.core.api.Assertions.*;

import com.project.ticket.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;

class QueueRedisRepositoryIntegrationTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.4-alpine")
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final long PID = 123;
    private QueueProperties properties;
    private QueueRedisRepository queue;

    @BeforeAll static void startRedis() {
        REDIS.start();
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
    }

    @AfterAll static void stopRedis() {
        if (connection != null) connection.destroy();
        REDIS.stop();
    }

    @BeforeEach void reset() {
        try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
        properties = QueueProperties.defaults();
        queue = at(NOW);
    }

    private QueueRedisRepository at(Instant time) {
        return new QueueRedisRepository(redis, properties, Clock.fixed(time, ZoneOffset.UTC));
    }

    private String key(String suffix) { return "queue:" + PID + ":" + suffix; }

    @Test void concurrentRegistrationHasUniqueContiguousSequenceAndOneState() throws Exception {
        List<Callable<QueueRedisRepository.Status>> tasks = new ArrayList<>();
        for (long user = 1; user <= 300; user++) {
            long id = user;
            tasks.add(() -> queue.register(PID, id));
            tasks.add(() -> queue.register(PID, id));
        }
        concurrently(tasks);
        var members = redis.opsForZSet().rangeWithScores(key("waiting"), 0, -1);
        assertThat(members).hasSize(300);
        assertThat(members.stream().map(item -> item.getScore().longValue()).toList())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 300).boxed().toList());
        assertThat(redis.opsForZSet().zCard(key("waiting-expiry"))).isEqualTo(300);
        assertThat(redis.opsForHash().size(key("registered-at"))).isEqualTo(300);
        assertExclusiveStates();
    }

    @Test void unexpiredDuplicateWithinGracePreservesOrderAndRegistrationTime() {
        queue.register(PID, 1);
        queue.register(PID, 2);
        assertThat(at(NOW.plusMillis(8_999)).register(PID, 1).rank()).isEqualTo(1);
        for (int seconds : new int[]{16, 24}) at(NOW.plusSeconds(seconds)).status(PID, 1);
        assertThat(at(NOW.plusSeconds(30)).register(PID, 1).rank()).isEqualTo(1);
        assertThat(redis.opsForValue().get(key("sequence"))).isEqualTo("2");
        assertThat(redis.opsForHash().get(key("registered-at"), "1")).isEqualTo(Long.toString(NOW.toEpochMilli()));
    }

    @Test void expiredDuplicateWithinGraceRegistersAtTailWithOrWithoutCleanup() {
        for (long millis : new long[]{9_000, 12_000}) {
            for (boolean cleanup : new boolean[]{false, true}) {
                redis.delete(QueueScripts.keys(PID));
                queue.register(PID, 1);
                at(NOW.plusSeconds(8)).register(PID, 2);
                var later = at(NOW.plusMillis(millis));
                if (cleanup) later.enter(PID, 1);
                assertThat(later.register(PID, 1).rank()).isEqualTo(2);
                assertThat(redis.opsForZSet().score(key("waiting"), "1")).isEqualTo(3);
                assertThat(redis.opsForHash().get(key("registered-at"), "1"))
                        .isEqualTo(Long.toString(NOW.toEpochMilli() + millis));
            }
        }
    }

    @Test void unexpiredDuplicateAfterGraceRegistersAtTail() {
        queue.register(PID, 1);
        queue.register(PID, 2);
        for (int seconds : new int[]{8, 16, 24}) at(NOW.plusSeconds(seconds)).status(PID, 1);
        assertThat(at(NOW.plusMillis(30_001)).register(PID, 1).rank()).isEqualTo(2);
        assertThat(redis.opsForZSet().score(key("waiting"), "1")).isEqualTo(3);
        assertThat(redis.opsForHash().get(key("registered-at"), "1"))
                .isEqualTo(Long.toString(NOW.toEpochMilli() + 30_001));
    }

    @Test void pollingBoundariesUseOneBasedRankAndTtlBeforeJitter() {
        for (long user = 1; user <= 1001; user++) queue.register(PID, user);
        long[] users = {100, 101, 1000, 1001};
        long[] polls = {3000, 10000, 10000, 20000};
        for (int i = 0; i < users.length; i++) {
            var status = at(NOW.plusMillis(1)).status(PID, users[i]);
            assertThat(status.rank()).isEqualTo(users[i]);
            assertThat(status.behind()).isEqualTo(1001 - users[i]);
            assertThat(status.basePollAfterMs()).isEqualTo(polls[i]);
            assertThat(status.nextPollAfterMs()).isBetween(polls[i] * 8 / 10, polls[i] * 12 / 10);
            assertThat(status.expiresAt()).isEqualTo(NOW.plusMillis(1 + polls[i] * 3));
            assertThat(redis.opsForZSet().score(key("waiting-expiry"), Long.toString(users[i])))
                    .isEqualTo((double) status.expiresAt().toEpochMilli());
        }
    }

    @Test void concurrentAdmissionAndEntryNeverExceedCapacityAndCycleLimit() throws Exception {
        for (long user = 1; user <= 1300; user++) queue.register(PID, user);
        AtomicBoolean running = new AtomicBoolean(true);
        // Sample both cardinalities in one Lua execution, avoiding a torn read during entry.
        var sample = new DefaultRedisScript<Long>(
                "return redis.call('ZCARD', KEYS[1]) + redis.call('ZCARD', KEYS[2])", Long.class);
        try (var executor = Executors.newFixedThreadPool(10)) {
            var sampler = executor.submit(() -> {
                do {
                    assertThat(redis.execute(sample, List.of(key("admitted"), key("active"))))
                            .isLessThanOrEqualTo(1000);
                } while (running.get());
            });
            try {
                List<Callable<Integer>> tasks = new ArrayList<>();
                for (int i = 0; i < 30; i++) tasks.add(() -> {
                    var result = queue.admit(PID);
                    assertThat(result.admitted()).isBetween(0, 100);
                    for (String member : redis.opsForZSet().range(key("admitted"), 0, 20)) {
                        queue.enter(PID, Long.parseLong(member));
                    }
                    return result.admitted();
                });
                for (var future : executor.invokeAll(tasks)) future.get(30, TimeUnit.SECONDS);
            } finally { running.set(false); }
            sampler.get(30, TimeUnit.SECONDS);
        }
        assertThat(redis.execute(sample, List.of(key("admitted"), key("active")))).isEqualTo(1000);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(300);
        assertThat(redis.opsForList().size(key("admit-history"))).isEqualTo(12);
        assertThat(redis.opsForValue().get(key("admit-rate")))
                .isEqualTo(Long.toString(redis.opsForList().range(key("admit-history"), 0, -1)
                        .stream().mapToLong(Long::parseLong).sum()));
        assertExclusiveStates();
    }

    @Test void cleanupIsBoundedAcrossAllStatesAndExpiredCandidatesNeverEnter() {
        for (long user = 1; user <= 650; user++) queue.register(PID, user);
        for (long user = 1; user <= 50; user++) {
            redis.opsForZSet().remove(key("waiting"), Long.toString(user));
            redis.opsForZSet().remove(key("waiting-expiry"), Long.toString(user));
            redis.opsForZSet().add(key(user <= 25 ? "admitted" : "active"), Long.toString(user), NOW.toEpochMilli());
        }
        var expired = at(NOW.plusSeconds(61));
        for (int cycle = 0; cycle < 4; cycle++) {
            var result = expired.admit(PID);
            assertThat(result.cleaned()).isEqualTo(cycle < 3 ? 200 : 50);
            assertThat(result.admitted()).isZero();
            assertThat(redis.opsForHash().size(key("registered-at"))).isEqualTo(Math.max(0, 650 - (cycle + 1) * 200));
            assertThat(redis.opsForSet().isMember(QueueScripts.ACTIVE_PERFORMANCES, "123")).isEqualTo(cycle < 3);
        }
        assertThat(redis.opsForZSet().zCard(key("admitted"))).isZero();
        assertThat(redis.opsForZSet().zCard(key("active"))).isZero();
        assertThat(redis.opsForZSet().zCard(key("waiting-expiry"))).isZero();
    }

    @Test void statusDoesNotResurrectOrRemoveExpiredWaiting() {
        queue.register(PID, 1);
        assertThat(at(NOW.plusSeconds(9)).status(PID, 1).state()).isEqualTo(QueueRedisRepository.State.EXPIRED);
        assertThat(redis.opsForZSet().score(key("waiting-expiry"), "1")).isEqualTo((double) NOW.plusSeconds(9).toEpochMilli());
        assertThat(redis.opsForHash().size(key("registered-at"))).isEqualTo(1);
    }

    @Test void activeRetriesKeepAbsoluteExpiryAndExpiredStatesCanRegisterAgain() {
        queue.register(PID, 1);
        queue.admit(PID);
        var admitted = at(NOW.plusSeconds(1)).status(PID, 1);
        assertThat(admitted.state()).isEqualTo(QueueRedisRepository.State.ADMITTED);
        assertThat(admitted.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(queue.register(PID, 1).state()).isEqualTo(QueueRedisRepository.State.ADMITTED);
        var entered = at(NOW.plusSeconds(2)).enter(PID, 1);
        assertThat(entered.state()).isEqualTo(QueueRedisRepository.State.ACTIVE);
        assertThat(entered.expiresAt()).isEqualTo(NOW.plusSeconds(302));
        assertThat(at(NOW.plusSeconds(30)).enter(PID, 1).expiresAt()).isEqualTo(entered.expiresAt());
        assertThat(at(NOW.plusSeconds(30)).register(PID, 1).expiresAt()).isEqualTo(entered.expiresAt());
        assertThat(at(NOW.plusSeconds(302)).status(PID, 1).state()).isEqualTo(QueueRedisRepository.State.EXPIRED);
        assertThat(at(NOW.plusSeconds(302)).register(PID, 1).state()).isEqualTo(QueueRedisRepository.State.WAITING);
        assertExclusiveStates();
    }

    @Test void expiredAdmissionCannotEnterAndEntryAlsoCleansAndRemovesEmptyPerformance() {
        queue.register(PID, 1);
        queue.admit(PID);
        assertThat(at(NOW.plusSeconds(60)).enter(PID, 1).state()).isEqualTo(QueueRedisRepository.State.EXPIRED);
        assertThat(redis.opsForSet().isMember(QueueScripts.ACTIVE_PERFORMANCES, "123")).isFalse();
        assertThat(redis.opsForHash().size(key("registered-at"))).isZero();
    }

    @Test void registrationRacingCleanupAlwaysRetainsPerformanceWithWaitingUsers() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            queue.register(PID, 1);
            var later = at(NOW.plusSeconds(61));
            concurrently(List.of(() -> later.enter(PID, 1), () -> later.register(PID, 2)));
            // Entry cleanup either removes the empty performance first, or sees the new waiting user.
            assertThat(redis.opsForSet().isMember(QueueScripts.ACTIVE_PERFORMANCES, "123")).isTrue();
            assertThat(later.status(PID, 2).state()).isEqualTo(QueueRedisRepository.State.WAITING);
            redis.delete(QueueScripts.keys(PID));
        }
    }

    @Test void invalidArgumentsAndEveryWrongKeyTypeLeaveAllDataUnchanged() {
        for (var script : QueueScripts.Operation.values()) {
            for (int index = 0; index < 9; index++) {
                redis.delete(QueueScripts.keys(PID));
                queue.register(PID, 1);
                String wrongKey = QueueScripts.keys(PID).get(index);
                redis.delete(wrongKey);
                if (index == 5 || index == 7) redis.opsForList().rightPush(wrongKey, "wrong");
                else redis.opsForValue().set(wrongKey, "wrong");
                var before = snapshot();
                assertThatThrownBy(() -> redis.execute(QueueScripts.script(script), QueueScripts.keys(PID),
                        queue.arguments(PID, script == QueueScripts.Operation.ADMIT ? null : 1L)))
                        .hasStackTraceContaining("QUEUE");
                assertThat(snapshot()).isEqualTo(before);
            }
            redis.delete(QueueScripts.keys(PID));
            queue.register(PID, 1);
            for (int index = 0; index < 14; index++) {
                String[] arguments = queue.arguments(PID, script == QueueScripts.Operation.ADMIT ? null : 1L);
                arguments[index] = "bad";
                var before = snapshot();
                assertThatThrownBy(() -> redis.execute(QueueScripts.script(script), QueueScripts.keys(PID), arguments))
                        .hasStackTraceContaining("QUEUE");
                assertThat(snapshot()).isEqualTo(before);
            }
        }
    }

    @Test void invalidNumericStoredValuesFailBeforeCleanupOrRegistrationWrites() {
        queue.register(PID, 1);
        for (String suffix : List.of("sequence", "admit-rate", "admit-history", "registered-at")) {
            redis.delete(key(suffix));
            switch (suffix) {
                case "admit-history" -> redis.opsForList().rightPush(key(suffix), "bad");
                case "registered-at" -> redis.opsForHash().put(key(suffix), "1", "bad");
                default -> redis.opsForValue().set(key(suffix), "bad");
            }
            var before = snapshot();
            assertThatThrownBy(() -> queue.register(PID, 1)).hasStackTraceContaining("QUEUE");
            assertThatThrownBy(() -> queue.admit(PID)).hasStackTraceContaining("QUEUE");
            assertThat(snapshot()).isEqualTo(before);
            redis.delete(key(suffix));
        }
    }

    @Test void configuredPolicyAndLargeUserIdsWorkInRedis() {
        properties = new QueueProperties(2, 1, 700, 1100, 2, 30, 1, 2, 10, 20, 30);
        queue = at(NOW);
        long largeId = 9_007_199_254_740_993L;
        assertThat(queue.register(PID, largeId).basePollAfterMs()).isEqualTo(10);
        assertThat(queue.register(PID, 2).basePollAfterMs()).isEqualTo(20);
        assertThat(queue.register(PID, 3).basePollAfterMs()).isEqualTo(30);
        assertThat(queue.admit(PID).admitted()).isEqualTo(1);
        assertThat(queue.status(PID, largeId).expiresAt()).isEqualTo(NOW.plusMillis(700));
        assertThat(queue.enter(PID, largeId).expiresAt()).isEqualTo(NOW.plusMillis(1100));
        assertThat(queue.admit(PID).admitted()).isEqualTo(1);
        assertThat(queue.admit(PID).admitted()).isZero();
        assertThat(queue.status(PID, 3).state()).isEqualTo(QueueRedisRepository.State.WAITING);
        assertThat(redis.opsForZSet().range(key("active"), 0, -1)).containsExactly(Long.toString(largeId));
        assertExclusiveStates();
    }

    @Test void performanceErrorDoesNotChangeAnotherPerformancesStateOrCapacity() {
        queue.register(PID, 1);
        queue.register(456, 1);
        redis.delete(key("active"));
        redis.opsForValue().set(key("active"), "wrong");
        assertThatThrownBy(() -> queue.admit(PID)).hasStackTraceContaining("QUEUE");
        assertThat(queue.admit(456).admitted()).isEqualTo(1);
        assertThat(queue.enter(456, 1).state()).isEqualTo(QueueRedisRepository.State.ACTIVE);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(1);
    }

    @Test void validatesWholeCleanupBatchBeforeAnyWritesAndRejectsSequenceOverflow() {
        queue.register(PID, 1);
        queue.register(PID, 2);
        redis.opsForHash().put(key("registered-at"), "2", "bad");
        var before = snapshot();
        var later = at(NOW.plusSeconds(61));
        assertThatThrownBy(() -> later.admit(PID)).hasStackTraceContaining("QUEUE");
        assertThatThrownBy(() -> later.enter(PID, 3)).hasStackTraceContaining("QUEUE");
        assertThat(snapshot()).isEqualTo(before);
        redis.opsForHash().put(key("registered-at"), "2", Long.toString(NOW.toEpochMilli()));
        for (String invalid : List.of("01", "9007199254740991", "9007199254740992")) {
            redis.opsForValue().set(key("sequence"), invalid);
            var current = snapshot();
            assertThatThrownBy(() -> queue.register(PID, 3)).hasStackTraceContaining("QUEUE");
            assertThat(snapshot()).isEqualTo(current);
        }
    }

    @Test void cancellationRemovesWaitingAndAdmittedAndKeepsOtherMembersAndSequence() {
        for (String state : List.of("WAITING", "ADMITTED")) {
            redis.delete(QueueScripts.keys(PID));
            queue.register(PID, 1);
            if (!state.equals("WAITING")) queue.admit(PID);
            queue.register(PID, 2);
            queue.cancel(PID, 1);
            queue.cancel(PID, 1);
            assertThat(queue.status(PID, 1).state()).isEqualTo(QueueRedisRepository.State.EXPIRED);
            for (int i = 0; i < 4; i++) assertThat(redis.opsForZSet().score(QueueScripts.keys(PID).get(i), "1")).isNull();
            assertThat(redis.opsForHash().hasKey(key("registered-at"), "1")).isFalse();
            assertThat(queue.status(PID, 2).state()).isEqualTo(QueueRedisRepository.State.WAITING);
            assertThat(redis.opsForSet().isMember(QueueScripts.ACTIVE_PERFORMANCES, "123")).isTrue();
            queue.cancel(PID, 2);
            assertThat(redis.opsForSet().isMember(QueueScripts.ACTIVE_PERFORMANCES, "123")).isFalse();
            assertThat(redis.opsForValue().get(key("sequence"))).isEqualTo("2");
        }
    }

    @Test void activeCancellationIsRejectedWithoutWritesAndCapacityIsHeldUntilExpiry() {
        properties = new QueueProperties(1, 1, 60_000, 300_000, 200, 30_000, 100, 1000, 3000, 10_000, 20_000);
        queue = at(NOW);
        queue.register(PID, 1);
        queue.admit(PID);
        var active = queue.enter(PID, 1);
        queue.register(PID, 2);
        var before = snapshot();
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> queue.cancel(PID, 1)).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.errorCode().name()).isEqualTo("QUEUE_ALREADY_ACTIVE"));
            assertThat(snapshot()).isEqualTo(before);
        }
        assertThat(queue.admit(PID).admitted()).isZero();
        assertThat(queue.status(PID, 2).state()).isEqualTo(QueueRedisRepository.State.WAITING);
        assertThat(queue.enter(PID, 1).expiresAt()).isEqualTo(active.expiresAt());
        // At the absolute expiry cancellation may remove the already invalid ACTIVE entry.
        at(active.expiresAt()).cancel(PID, 1);
        assertThat(redis.opsForZSet().score(key("active"), "1")).isNull();
        assertThat(at(active.expiresAt()).status(PID, 1).state()).isEqualTo(QueueRedisRepository.State.EXPIRED);
    }

    @Test void admissionRacingRegistrationRetainsPerformanceWithWaitingUsers() throws Exception {
        properties = new QueueProperties(1000, 1, 60_000, 300_000, 200, 30_000, 100, 1000, 3000, 10_000, 20_000);
        queue = at(NOW);
        for (int iteration = 0; iteration < 100; iteration++) {
            queue.register(PID, 1);
            var later = at(NOW.plusSeconds(61));
            concurrently(List.of(() -> { later.admit(PID); return true; }, () -> {
                later.register(PID, 2);
                later.register(PID, 3);
                return true;
            }));
            // Even if admission runs after registration, its one-user limit leaves user 3 WAITING.
            assertThat(redis.opsForZSet().zCard(key("waiting"))).isPositive();
            assertThat(later.status(PID, 3).state()).isEqualTo(QueueRedisRepository.State.WAITING);
            assertThat(redis.opsForSet().isMember(QueueScripts.ACTIVE_PERFORMANCES, "123")).isTrue();
            assertExclusiveStates();
            redis.delete(QueueScripts.keys(PID));
        }
    }

    @Test void cancellationRacingRegistrationRetainsNewWaitingUser() throws Exception {
        for (int iteration = 0; iteration < 30; iteration++) {
            queue.register(PID, 1);
            concurrently(List.of(() -> { queue.cancel(PID, 1); return true; }, () -> queue.register(PID, 2)));
            assertThat(redis.opsForSet().isMember(QueueScripts.ACTIVE_PERFORMANCES, "123")).isTrue();
            assertThat(queue.status(PID, 2).state()).isEqualTo(QueueRedisRepository.State.WAITING);
            redis.delete(QueueScripts.keys(PID));
        }
    }

    private List<String> snapshot() {
        var dump = new DefaultRedisScript<String>("local v = redis.call('DUMP', KEYS[1]); if not v then return '' end; return redis.sha1hex(v)", String.class);
        return QueueScripts.keys(PID).stream().map(key -> redis.execute(dump, List.of(key))).toList();
    }

    private void assertExclusiveStates() {
        var waiting = redis.opsForZSet().range(key("waiting-expiry"), 0, -1);
        var admitted = redis.opsForZSet().range(key("admitted"), 0, -1);
        var active = redis.opsForZSet().range(key("active"), 0, -1);
        var unique = new HashSet<>(waiting);
        unique.addAll(admitted);
        unique.addAll(active);
        assertThat(unique).hasSize(waiting.size() + admitted.size() + active.size());
    }

    private <T> void concurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return task.call();
            })).toList();
            start.countDown();
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
    }
}
