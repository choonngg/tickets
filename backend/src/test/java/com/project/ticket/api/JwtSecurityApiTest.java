package com.project.ticket.api;

import static com.project.ticket.support.TestJwtKeys.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.project.ticket.domain.user.entity.UserRole;
import com.project.ticket.global.auth.JwtProvider;
import com.project.ticket.global.auth.SecurityErrorHandler;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class JwtSecurityApiTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired SecurityErrorHandler securityErrorHandler;
    @Autowired JsonMapper mapper;

    @Test
    void anonymousProtectedRequestReturns401Json() throws Exception {
        mvc.perform(get("/api/tickets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    void malformedBearerReturns401JsonThroughSecurityChain() throws Exception {
        mvc.perform(get("/api/tickets/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void publicJwksContainsOnlyPublicKeyAndVerifiesIssuedToken() throws Exception {
        var response = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys[0].kid").value(KEY_ID))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andReturn().getResponse();
        var keys = mapper.readTree(response.getContentAsString()).get("keys");
        assertThat(keys.size()).isEqualTo(1);
        var key = keys.get(0);
        assertThat(key.propertyNames()).containsExactlyInAnyOrder("kid", "kty", "alg", "use", "n", "e");
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").asText())),
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").asText()))));
        assertThat(publicKey.getModulus().bitLength()).isEqualTo(2048);
        var verified = Jwts.parser().verifyWith(publicKey).build()
                .parseSignedClaims(jwt.createAccessToken(1L, UserRole.FAN, 1L));
        assertThat(verified.getPayload().getSubject()).isEqualTo("1");
    }

    @Test
    void accessDeniedHandlerReturns403Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        securityErrorHandler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("internal detail"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(mapper.readTree(response.getContentAsString()).get("message").asText()).isEqualTo("Access is forbidden.");
    }
}
