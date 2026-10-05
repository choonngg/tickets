package com.project.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.auth.service.AuthService;
import com.project.ticket.domain.auth.service.SessionCleanup;
import com.project.ticket.infra.google.GoogleOAuthClient.UserInfo;
import com.project.ticket.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.project.ticket.RefreshRotationIntegrationTest$SqlRecorder")
@AutoConfigureMockMvc
class RefreshRotationIntegrationTest extends IntegrationTestSupport {
    static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    @Autowired AuthService auth;
    @Autowired SessionCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        SqlRecorder.statements.clear();
        SqlRecorder.sessionSelect = null;
    }

    @AfterEach
    void cleanupRows() {
        SqlRecorder.sessionSelect = null;
        for (Long id : userIds) {
            jdbc.update("DELETE FROM auth_session WHERE user_id = ?", id); // real MySQL FK cascade
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void tenConcurrentRefreshesProduceOneSuccessAndNoUniqueViolations() throws Exception {
        var login = login();
        long sid = sid(login);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(10)) {
            var requests = new ArrayList<Future<org.springframework.mock.web.MockHttpServletResponse>>();
            for (int i = 0; i < 10; i++) requests.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/auth/refresh").cookie(refreshCookie(login.refreshToken())))
                        .andReturn().getResponse();
            }));
            start.countDown();
            var results = new ArrayList<org.springframework.mock.web.MockHttpServletResponse>();
            for (var request : requests) results.add(request.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result.getStatus() == 200).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.getStatus() == 401).count()).isEqualTo(9);
            for (var result : results) {
                if (result.getStatus() == 401) assertThat(result.getContentAsString()).contains("REFRESH_RETRY");
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ? AND consumed_at IS NULL", Long.class, sid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sid)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sid)).isNull();
    }

    @Test
    void mysqlConsumeUpdateExecutesBeforeInsertAndInvalidHashTakesNoRowLock() {
        var login = login();
        SqlRecorder.statements.clear();
        assertThat(auth.refresh("x".repeat(43)).error()).isEqualTo(com.project.ticket.global.exception.ErrorCode.REAUTH_REQUIRED);
        assertThat(SqlRecorder.statements).noneMatch(sql -> sql.contains("for update"));
        SqlRecorder.statements.clear();
        assertThat(auth.refresh(login.refreshToken()).error()).isNull();
        var writes = SqlRecorder.statements.stream().filter(sql -> sql.startsWith("update refresh_token ") || sql.startsWith("insert into refresh_token ")).toList();
        assertThat(writes).hasSize(2);
        assertThat(writes.get(0)).startsWith("update refresh_token ").contains("consumed_at=?");
        assertThat(writes.get(1)).startsWith("insert into refresh_token ");
    }

    @Test
    void graceBoundaryKeepsSessionThenReuse401CommitsRevocation() throws Exception {
        var login = login();
        long sid = sid(login);
        Cookie old = refreshCookie(login.refreshToken());
        Cookie next = mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        // Rotate the latest RT too: only the time window controls classification.
        Cookie latest = mvc.perform(post("/api/auth/refresh").cookie(next)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        when(clock.instant()).thenReturn(NOW.plusSeconds(15));
        mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_RETRY")).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Instant.class, sid)).isNull();
        when(clock.instant()).thenReturn(NOW.plusSeconds(15).plusNanos(1000));
        mvc.perform(post("/api/auth/refresh").cookie(old)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED")).andExpect(cookie().maxAge("refresh_token", 0));
        // No test transaction: observe the committed row on a separate connection after the 401.
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", java.time.LocalDateTime.class, sid)
                .toInstant(java.time.ZoneOffset.UTC)).isEqualTo(NOW.plusSeconds(15).plusNanos(1000));
        mvc.perform(post("/api/auth/refresh").cookie(latest)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
    }

    @Test
    void refreshWaitingForLogoutSeesCommittedRevocation() throws Exception {
        assertRefreshWaitsForRevocation(false);
    }

    @Test
    void refreshWaitingForWithdrawalSeesCommittedRevocation() throws Exception {
        assertRefreshWaitsForRevocation(true);
    }

    private void assertRefreshWaitsForRevocation(boolean withdrawal) throws Exception {
        var login = login();
        long sid = sid(login);
        long userId = jdbc.queryForObject("SELECT user_id FROM auth_session WHERE id = ?", Long.class, sid);
        var waiting = new CountDownLatch(1);
        SqlRecorder.sessionSelect = waiting;
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?>[] pending = new Future<?>[1];
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM auth_session WHERE id = ? FOR UPDATE", Long.class, sid);
                pending[0] = pool.submit(() -> auth.refresh(login.refreshToken()));
                try {
                    assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(pending[0].isDone()).isFalse();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (withdrawal) auth.withdraw(userId);
                else auth.logout(login.refreshToken());
            });
            var result = (AuthService.RefreshResult) pending[0].get(30, TimeUnit.SECONDS);
            assertThat(result.error()).isEqualTo(com.project.ticket.global.exception.ErrorCode.REAUTH_REQUIRED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sid)).isEqualTo(1);
    }

    @Test
    void mysqlCleanupRetainsSevenDaysThenCascadesWholeHistory() {
        var login = login();
        long sid = sid(login);
        auth.refresh(login.refreshToken());
        auth.logout(login.refreshToken());
        when(clock.instant()).thenReturn(NOW.plusSeconds(21 * 86400).minusNanos(1000));
        cleanup.clean();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE id = ?", Long.class, sid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sid)).isEqualTo(2);
        when(clock.instant()).thenReturn(NOW.plusSeconds(21 * 86400));
        cleanup.clean();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE id = ?", Long.class, sid)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id = ?", Long.class, sid)).isZero();
    }

    @Test
    void concurrentGoogleLoginsOfSameUserAllSucceedWithOneUserRow() throws Exception {
        String sub = UUID.randomUUID().toString();
        var info = new UserInfo(sub, "same@example.com", true, "Fan");
        runConcurrently(List.of(info, info, info, info, info, info));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub = ?", Long.class, sub)).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, sub);
        userIds.add(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id = ?", Long.class, id)).isEqualTo(6);
    }

    @Test
    void concurrentFirstLoginsOfDifferentUsersDoNotDeadlock() throws Exception {
        var infos = new ArrayList<UserInfo>();
        for (int i = 0; i < 10; i++) infos.add(new UserInfo(UUID.randomUUID().toString(), "u" + i + "@example.com", true, "Fan" + i));
        runConcurrently(infos);
        for (var info : infos) {
            long id = jdbc.queryForObject("SELECT id FROM users WHERE google_sub = ?", Long.class, info.sub());
            userIds.add(id);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE user_id = ?", Long.class, id)).isEqualTo(1);
        }
    }

    private void runConcurrently(List<UserInfo> infos) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(infos.size())) {
            var requests = new ArrayList<Future<AuthService.IssuedTokens>>();
            for (var info : infos) requests.add(pool.submit(() -> {
                start.await();
                return auth.authenticateGoogle(info);
            }));
            start.countDown();
            for (var request : requests) request.get(30, TimeUnit.SECONDS);
        }
    }

    private AuthService.IssuedTokens login() {
        return login(new UserInfo(UUID.randomUUID().toString(), "fan@example.com", true, "Fan"));
    }

    private AuthService.IssuedTokens login(UserInfo info) {
        var issued = auth.authenticateGoogle(info);
        userIds.add(jdbc.queryForObject("SELECT user_id FROM auth_session WHERE id = ?", Long.class, sid(issued)));
        return issued;
    }

    private long sid(AuthService.IssuedTokens login) {
        return jdbc.queryForObject("SELECT session_id FROM refresh_token WHERE token_hash = ?", Long.class, AuthService.hash(login.refreshToken()));
    }

    private static Cookie refreshCookie(String raw) {
        return new Cookie("refresh_token", raw);
    }

    public static class SqlRecorder implements StatementInspector {
        static final List<String> statements = new CopyOnWriteArrayList<>();
        static volatile CountDownLatch sessionSelect;

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            var latch = sessionSelect;
            if (latch != null && sql.startsWith("select ") && sql.contains("from auth_session ") && sql.contains("for update")) {
                latch.countDown();
            }
            return sql;
        }
    }
}
