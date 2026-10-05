package com.project.ticket.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    @Test
    void businessExceptionReturnsErrorResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.REAUTH_REQUIRED));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("REAUTH_REQUIRED");
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void dataIntegrityViolationReturns400JsonWithoutInternalDetails() throws Exception {
        mvc.perform(get("/integrity"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request body is invalid.\"}"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void missingIdempotencyHeaderReturns400Json() throws Exception {
        mvc.perform(get("/header"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\",\"message\":\"Idempotency-Key header is required.\"}"));
    }

    @Test
    void otherMissingHeaderReturns400Json() throws Exception {
        mvc.perform(get("/other-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unhandledExceptionReturns500JsonWithoutInternalDetails() throws Exception {
        mvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"An internal server error occurred.\"}"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void malformedJsonReturns400Json() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"value\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request body is invalid.\"}", true));
    }

    @Test
    void pathVariableTypeMismatchReturns400Json() throws Exception {
        mvc.perform(get("/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request body is invalid.\"}", true));
    }

    @Test
    void missingHandlerReturns404Json() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource was not found.\"}", true));
    }

    @Test
    void missingResourceReturns404Json() throws Exception {
        mvc.perform(get("/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource was not found.\"}", true));
    }

    @Test
    void unsupportedMethodReturns405JsonAndAllowHeader() throws Exception {
        mvc.perform(post("/number/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"Request method is not supported.\"}", true));
    }

    @Test
    void unsupportedContentTypeReturns415Json() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"code\":\"UNSUPPORTED_MEDIA_TYPE\",\"message\":\"Content-Type is not supported.\"}", true));
    }

    @RestController
    static class FailureController {
        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@RequestBody java.util.Map<String, Object> body) { }

        @GetMapping("/number/{id}")
        void number(@PathVariable long id) { }

        @GetMapping("/missing-resource")
        void missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing-resource", "secret resource details");
        }

        @GetMapping("/integrity")
        void integrity() { throw new DataIntegrityViolationException("secret database details"); }

        @GetMapping("/unexpected")
        void unexpected() { throw new IllegalStateException("secret internal details"); }

        @GetMapping("/header")
        void header(@RequestHeader("Idempotency-Key") String key) { }

        @GetMapping("/other-header")
        void otherHeader(@RequestHeader("X-Required") String value) { }
    }
}
