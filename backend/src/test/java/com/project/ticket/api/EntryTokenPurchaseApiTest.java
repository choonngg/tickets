package com.project.ticket.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.infra.idempotency.IdempotencyService;
import com.project.ticket.support.TestEntryTokens;
import com.project.ticket.support.TestJwtKeys;
import com.project.ticket.support.TestUsers;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EntryTokenPurchaseApiTest {
    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired JwtProvider login;
    @MockitoBean Clock clock;
    @MockitoSpyBean IdempotencyService idempotency;
    User fan;
    Concert concert;
    Seat seat;
    String bearer;
    String key;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        fan = users.save(TestUsers.fan("Entry fan"));
        var artist = users.save(TestUsers.artist("Entry artist"));
        concert = concerts.save(Concert.create(artist, "Entry concert", "Venue",
                NOW.plusSeconds(7200), NOW.minusSeconds(1), NOW.plusSeconds(3600)));
        seat = seats.save(Seat.create(concert, "A", 1, 1, 10000));
        bearer = "Bearer " + login.createAccessToken(fan.getId(), fan.getRole(), 1L);
        key = UUID.randomUUID().toString();
    }

    @Test void missingAndBlankEntryTokenAreForbiddenBeforeIdempotencyWhenKeyAbsent() throws Exception {
        for (String token : new String[]{null, "", "   "}) {
            reject(token, "ENTRY_TOKEN_REQUIRED", null);
        }
    }

    @Test void invalidTokensNeverConsumeKeyAndValidRetryPurchases() throws Exception {
        String valid = token();
        String[] parts = valid.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        String wrongKey = TestEntryTokens.builder(concert.getId(), fan.getId(), NOW.plusSeconds(300))
                .signWith(TestJwtKeys.PRIVATE_KEY, io.jsonwebtoken.Jwts.SIG.RS256).compact();
        for (String invalid : new String[]{"invalid", forged, wrongKey, bearer.substring(7), "Bearer " + valid}) {
            reject(invalid, "ENTRY_TOKEN_INVALID", key);
        }
        purchase(valid, key, seat.getId()).andExpect(status().isCreated());
        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
    }

    @Test void wrongUserAndConcertAreForbiddenAndReleaseIdempotencyKey() throws Exception {
        reject(TestEntryTokens.issue(concert.getId(), fan.getId() + 1, clock), "ENTRY_TOKEN_MISMATCH", key);
        reject(TestEntryTokens.issue(concert.getId() + 1, fan.getId(), clock), "ENTRY_TOKEN_MISMATCH", key);
        purchase(token(), key, seat.getId()).andExpect(status().isCreated());
    }

    @Test void invalidHeadersAndMissingOrMalformedClaimsAreForbidden() throws Exception {
        for (String header : new String[]{"kid", "typ", "alg"}) {
            var builder = TestEntryTokens.builder(concert.getId(), fan.getId(), NOW.plusSeconds(300));
            if (header.equals("kid")) builder.header().keyId("wrong").and();
            if (header.equals("typ")) builder.header().type("AT").and();
            String token = header.equals("alg")
                    ? builder.signWith(TestEntryTokens.KEYS.privateKey(), io.jsonwebtoken.Jwts.SIG.RS512).compact()
                    : TestEntryTokens.sign(builder);
            reject(token, "ENTRY_TOKEN_INVALID", key);
        }
        for (String header : new String[]{"kid", "typ"}) {
            var builder = TestEntryTokens.builder(concert.getId(), fan.getId(), NOW.plusSeconds(300));
            builder.header().add(header, null).and();
            reject(TestEntryTokens.sign(builder), "ENTRY_TOKEN_INVALID", key);
        }
        for (String claim : new String[]{"iss", "aud", "sub", "pid", "exp"}) {
            var builder = TestEntryTokens.builder(concert.getId(), fan.getId(), NOW.plusSeconds(300));
            builder.claim(claim, null);
            reject(TestEntryTokens.sign(builder), "ENTRY_TOKEN_INVALID", key);
        }
        for (String claim : new String[]{"iss", "aud", "sub", "pid"}) {
            var builder = TestEntryTokens.builder(concert.getId(), fan.getId(), NOW.plusSeconds(300));
            builder.claim(claim, "wrong");
            reject(TestEntryTokens.sign(builder), "ENTRY_TOKEN_INVALID", key);
        }
        var malformedExpiry = io.jsonwebtoken.Jwts.builder().header()
                .keyId(TestEntryTokens.KEY_ID).type("JWT").and().content("""
                {"iss":"queue-service","aud":"booking-service","sub":"%s","pid":"%s","exp":"wrong"}
                """.formatted(fan.getId(), concert.getId()));
        reject(TestEntryTokens.sign(malformedExpiry), "ENTRY_TOKEN_INVALID", key);
        var builder = TestEntryTokens.builder(concert.getId(), fan.getId(), NOW.plusSeconds(300));
        reject(TestEntryTokens.sign(builder.claim("pid", 123)), "ENTRY_TOKEN_INVALID", key);
    }

    @Test void signatureAndExpiryAreCheckedBeforeIdentityAndSeat() throws Exception {
        var expired = TestEntryTokens.builder(concert.getId() + 1, fan.getId() + 1, NOW.minusSeconds(1));
        reject(expired.signWith(TestJwtKeys.PRIVATE_KEY, io.jsonwebtoken.Jwts.SIG.RS256).compact(),
                "ENTRY_TOKEN_INVALID", key);
        reject(TestEntryTokens.sign(expired.issuer("wrong")), "ENTRY_TOKEN_EXPIRED", key);
        purchase(TestEntryTokens.issue(concert.getId(), fan.getId() + 1, clock), key, Long.MAX_VALUE)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENTRY_TOKEN_MISMATCH"));

    }

    @Test void expiryBoundaryAllowsCompletedReplayButRejectsNewPurchase() throws Exception {
        String token = token();
        when(clock.instant()).thenReturn(NOW.plusSeconds(300).minusNanos(1));
        purchase(token, key, seat.getId()).andExpect(status().isCreated());
        clearInvocations(idempotency);
        for (Instant instant : new Instant[]{NOW.plusSeconds(300), NOW.plusSeconds(300).plusNanos(1)}) {
            when(clock.instant()).thenReturn(instant);
            purchase(token, key, seat.getId()).andExpect(status().isCreated());
            reject(token, "ENTRY_TOKEN_EXPIRED", UUID.randomUUID().toString());
        }
        mvc.perform(get("/api/tickets/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].ticketId")
                        .value(tickets.findBySeatId(seat.getId()).orElseThrow().getId()));
    }

    @Test void validTokenPreservesMissingSeatContractAndReleasesKey() throws Exception {
        purchase(token(), key, Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"));
        verify(idempotency, atLeastOnce()).releaseIfOwner(eq(fan.getId()), eq(key), anyString());
    }

    private void reject(String token, String code, String requestKey) throws Exception {
        purchase(token, requestKey, seat.getId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(code));
        if (requestKey != null) verify(idempotency, atLeastOnce()).releaseIfOwner(eq(fan.getId()), eq(requestKey), anyString());

    }

    private String token() { return TestEntryTokens.issue(concert.getId(), fan.getId(), clock); }

    private ResultActions purchase(String token, String requestKey, long seatId) throws Exception {
        var request = post("/api/tickets").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seatId + "]}");
        if (token != null) request.header("X-Entry-Token", token);
        if (requestKey != null) request.header("Idempotency-Key", requestKey);
        return mvc.perform(request);
    }
}
