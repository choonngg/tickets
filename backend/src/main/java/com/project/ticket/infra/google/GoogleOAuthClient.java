package com.project.ticket.infra.google;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface GoogleOAuthClient {
    UserInfo exchange(String code);

    record UserInfo(String sub, String email, @JsonProperty("email_verified") Boolean emailVerified, String name) {}
}
