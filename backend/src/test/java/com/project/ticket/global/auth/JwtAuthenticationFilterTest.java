package com.project.ticket.global.auth;

import static com.project.ticket.support.TestJwtKeys.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.domain.user.entity.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class JwtAuthenticationFilterTest {
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");
    private final JwtProvider provider = provider(Clock.fixed(NOW, ZoneOffset.UTC));
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(provider,
            new SecurityErrorHandler(new JsonMapper()));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> invalidTokens() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return Stream.of(
                Arguments.of("forged signature", accessToken(NOW).signWith(generator.generateKeyPair().getPrivate(), Jwts.SIG.RS256).compact()),
                Arguments.of("issuer", accessToken(NOW).issuer("https://evil.test").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("audience", accessToken(NOW).audience().clear().add("queue-service").and().signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("type", accessToken(NOW).claim("typ", "RT").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("expired beyond skew", accessToken(NOW).expiration(Date.from(NOW.minusSeconds(31))).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("wrong kid", accessToken(NOW).header().keyId("other-key").and().signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing kid", accessToken(NOW).header().keyId(null).and().signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing issuer", accessToken(NOW).issuer(null).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing audience", accessToken(NOW).audience().clear().and().signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing type", accessToken(NOW).claim("typ", null).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing role", accessToken(NOW).claim("role", null).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("unknown role", accessToken(NOW).claim("role", "UNKNOWN").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("wrong role type", accessToken(NOW).claim("role", 123).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing subject", accessToken(NOW).subject(null).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("invalid subject", accessToken(NOW).subject("not-a-number").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing sid", accessToken(NOW).claim("sid", null).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("zero sid", accessToken(NOW).claim("sid", "0").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("negative sid", accessToken(NOW).claim("sid", "-1").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("invalid sid", accessToken(NOW).claim("sid", "invalid").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("removed admin role", accessToken(NOW).claim("role", "ADMIN").signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing iat", accessToken(NOW).issuedAt(null).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("missing exp", accessToken(NOW).expiration(null).signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact()),
                Arguments.of("different RSA algorithm", accessToken(NOW).signWith(PRIVATE_KEY, Jwts.SIG.RS512).compact()),
                Arguments.of("HMAC algorithm confusion", accessToken(NOW).signWith(Keys.hmacShaKeyFor(PUBLIC_KEY.getEncoded()), Jwts.SIG.HS256).compact()),
                Arguments.of("legacy refresh token", Jwts.builder().subject("1").signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact()),
                Arguments.of("unsigned", accessToken(NOW).compact()),
                Arguments.of("empty", ""),
                Arguments.of("malformed", "not.a.jwt"),
                Arguments.of("missing segments", "abc")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    void invalidTokenReturnsUnauthorizedJsonAndClearsContext(String reason, String token) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("previous-user", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response,
                (req, res) -> { throw new AssertionError("Invalid token reached the API: " + reason); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(new JsonMapper().readTree(response.getContentAsString()).get("code").asText())
                .isEqualTo("UNAUTHORIZED");
        assertThat(new JsonMapper().readTree(response.getContentAsString()).get("message").asText())
                .isEqualTo("Authentication is required.");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {29, 30})
    void expirationWithinSkewAuthenticates(int secondsAfterExpiry) throws Exception {
        String token = accessToken(NOW).expiration(Date.from(NOW.minusSeconds(secondsAfterExpiry)))
                .signWith(PRIVATE_KEY, Jwts.SIG.RS256).compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilterInternal(request, new MockHttpServletResponse(), (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(1L, UserRole.FAN));
            assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_FAN");
        });
    }

    @Test
    void validTokenAuthenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + provider.createAccessToken(1L, UserRole.FAN, 1L));
        filter.doFilterInternal(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                        .isEqualTo(new AuthenticatedUser(1L, UserRole.FAN)));
    }
}
