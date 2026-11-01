package com.github.gis;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Real cookie/login/CSRF client shared by the existing persistence integration tests. */
final class EditorHttpClient {
    static final String PASSWORD = "test-editor-password-only";

    static TestRestTemplate anonymous(int port) {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return anonymous(port, cookies);
    }

    static TestRestTemplate anonymous(int port, CookieManager cookies) {
        var http = HttpClient.newBuilder().cookieHandler(cookies).build();
        return new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port)
                .requestFactory(() -> new JdkClientHttpRequestFactory(http)));
    }

    static TestRestTemplate login(int port) {
        var client = anonymous(port);
        var before = client.getForObject("/api/v1/session", JsonNode.class);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", "editor");
        form.add("password", PASSWORD);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(before.path("csrf_header").asText(), before.path("csrf_token").asText());
        assertThat(client.postForEntity("/login", new HttpEntity<>(form, headers), String.class)
                .getStatusCode().value()).isEqualTo(302);
        var session = client.getForObject("/api/v1/session", JsonNode.class);
        assertThat(session.path("can_edit").asBoolean()).isTrue();
        client.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set(session.path("csrf_header").asText(), session.path("csrf_token").asText());
            return execution.execute(request, body);
        });
        return client;
    }
}
