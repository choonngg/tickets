package com.project.ticket.infra.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpGoogleOAuthClient implements GoogleOAuthClient {
    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    @Autowired
    public HttpGoogleOAuthClient(
            @Value("${google.client-id}") String clientId,
            @Value("${google.client-secret}") String clientSecret,
            @Value("${google.redirect-uri}") String redirectUri) {
        this(RestClient.builder(), clientId, clientSecret, redirectUri);
    }

    public HttpGoogleOAuthClient(RestClient.Builder builder, String clientId, String clientSecret, String redirectUri) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public UserInfo exchange(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        try {
            var token = http.post().uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(TokenResponse.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            return http.get().uri("https://openidconnect.googleapis.com/v1/userinfo")
                    .headers(headers -> headers.setBearerAuth(token.accessToken())).retrieve().body(UserInfo.class);
        } catch (RestClientException | IllegalArgumentException exception) {
            // Do not expose/log provider payloads, codes, tokens, or client secrets.
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {}
}
