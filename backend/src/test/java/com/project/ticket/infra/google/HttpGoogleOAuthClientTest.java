package com.project.ticket.infra.google;

import static org.assertj.core.api.Assertions.*;

import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class HttpGoogleOAuthClientTest {
    @Test
    void exchangesCodeServerSideAndReadsVerifiedProfileWithoutRealHttp() {
        var calls = new AtomicInteger();
        var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            if (calls.getAndIncrement() == 0) {
                assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
                assertThat(request.getURI().toString()).isEqualTo("https://oauth2.googleapis.com/token");
                assertThat(request.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
                assertThat(new String(body, StandardCharsets.UTF_8)).contains("code=code%2Bvalue", "client_id=client-id",
                        "client_secret=secret%26value", "grant_type=authorization_code",
                        "redirect_uri=https%3A%2F%2Fservice.test%2Fapi%2Fauth%2Fgoogle%2Fcallback");
                return json("{\"access_token\":\"google-access\"}", HttpStatus.OK);
            }
            assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
            assertThat(request.getURI().toString()).isEqualTo("https://openidconnect.googleapis.com/v1/userinfo");
            assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer google-access");
            return json("{\"sub\":\"123\",\"email\":\"fan@example.com\",\"email_verified\":true,\"name\":\"Fan\"}", HttpStatus.OK);
        });
        var client = new HttpGoogleOAuthClient(builder, "client-id", "secret&value", "https://service.test/api/auth/google/callback");
        assertThat(client.exchange("code+value")).isEqualTo(new GoogleOAuthClient.UserInfo("123", "fan@example.com", true, "Fan"));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void providerErrorsAndMissingAccessTokenBecomeFixedUnauthorizedErrors() {
        for (var response : new String[]{"{}", "{\"access_token\":\"\"}", "{\"error\":\"sensitive-provider-detail\"}"}) {
            var builder = RestClient.builder().requestInterceptor((request, body, execution) ->
                    json(response, response.contains("error") ? HttpStatus.BAD_REQUEST : HttpStatus.OK));
            var client = new HttpGoogleOAuthClient(builder, "client", "secret", "https://service.test/callback");
            assertThatThrownBy(() -> client.exchange("code")).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }
    }

    private static MockClientHttpResponse json(String body, HttpStatus status) {
        var response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }
}
