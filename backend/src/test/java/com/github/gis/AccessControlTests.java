package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "EDITING_ENABLED=true",
      "EDITOR_USERNAME=editor",
      "EDITOR_PASSWORD=test-editor-password-only"
    })
@AutoConfigureMockMvc
class AccessControlTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @LocalServerPort int port;
  UUID id;
  final String name = "access-test-" + UUID.randomUUID();
  final String json = "[{\"name\":\"" + name + "\",\"lat\":52.34,\"lng\":35.35}]";

  @BeforeEach
  void fixture() {
    id =
        jdbc.queryForObject(
            "INSERT INTO app.user_points(name,geom) VALUES (?,"
                + "ST_SetSRID(ST_MakePoint(35.35,52.34),4326)) RETURNING id",
            UUID.class,
            name);
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM app.user_points WHERE name=?", name);
  }

  private void unchanged() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.user_points WHERE name=?", Long.class, name))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM app.user_points WHERE id=?", Long.class, id))
        .isEqualTo(1);
  }

  @Test
  void anonymousAndReaderHaveNoMutationRightsEvenWithValidCsrf() throws Exception {
    for (var principal : List.of(anonymous(), user("viewer").roles("READER"))) {
      mvc.perform(
              post("/api/v1/osm/points/add")
                  .with(principal)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json))
          .andExpect(status().is4xxClientError());
      mvc.perform(delete("/api/v1/osm/points/user:" + id).with(principal).with(csrf()))
          .andExpect(status().is4xxClientError());
    }
    unchanged();
  }

  @Test
  void editorRequiresValidCsrfForBothMutationMethods() throws Exception {
    for (boolean invalid : new boolean[] {false, true}) {
      var create =
          post("/api/v1/osm/points/add")
              .with(user("editor").roles("EDITOR"))
              .contentType(MediaType.APPLICATION_JSON)
              .content(json);
      var remove = delete("/api/v1/osm/points/user:" + id).with(user("editor").roles("EDITOR"));
      if (invalid) {
        create.with(csrf().useInvalidToken());
        remove.with(csrf().useInvalidToken());
      }
      mvc.perform(create).andExpect(status().isForbidden());
      mvc.perform(remove).andExpect(status().isForbidden());
    }
    unchanged();
  }

  @Test
  void unexpectedMutationPathsAreDeniedEvenForEditor() throws Exception {
    for (String path :
        List.of(
            "/api/v1/osm/points",
            "/api/v1/osm/points/add/",
            "/api/v1/osm/points/add.json",
            "/api/v1/osm/roads")) {
      mvc.perform(
              post(path)
                  .with(user("editor").roles("EDITOR"))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json))
          .andExpect(status().isForbidden());
    }
    unchanged();
  }

  @Test
  void publicReadingAndCalculationDoNotRequireLoginOrCsrf() {
    var client = EditorHttpClient.anonymous(port);
    for (String layer : List.of("points", "roads", "polygons")) {
      assertThat(client.getForEntity("/api/v1/osm/" + layer, String.class).getStatusCode())
          .isEqualTo(HttpStatus.OK);
    }
    var result =
        client.postForEntity(
            "/api/v1/osm/distances",
            Map.of("points", List.of(List.of(35.35, 52.34), List.of(35.36, 52.35))),
            JsonNode.class);
    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getBody().path("totalDistance").asDouble()).isPositive();
    unchanged();
  }

  @Test
  void realLoginRotatesSessionAndCsrfAndLogoutPreventsReplay() {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client = EditorHttpClient.anonymous(port, cookies);
    var initial = client.getForEntity("/api/v1/session", JsonNode.class);
    assertThat(initial.getHeaders().getCacheControl()).contains("no-store");
    assertThat(initial.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
        .contains("HttpOnly", "SameSite=Lax");
    var before = initial.getBody();
    String oldSession = cookies.getCookieStore().getCookies().getFirst().getValue();
    var loginPage = client.getForEntity("/login", String.class);
    assertThat(loginPage.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginPage.getBody()).contains("name=\"username\"", "name=\"password\"");
    var form = new LinkedMultiValueMap<String, String>();
    form.add("username", "editor");
    form.add("password", EditorHttpClient.PASSWORD);
    assertThat(client.postForEntity("/login", form, String.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.set(before.path("csrf_header").asText(), before.path("csrf_token").asText());
    form.set("password", "wrong-test-password");
    assertThat(
            client
                .postForEntity("/login", new HttpEntity<>(form, headers), String.class)
                .getHeaders()
                .getLocation()
                .toString())
        .endsWith("/login?error");
    form.set("password", EditorHttpClient.PASSWORD);
    assertThat(
            client
                .postForEntity("/login", new HttpEntity<>(form, headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FOUND);
    String authenticatedSession = cookies.getCookieStore().getCookies().getFirst().getValue();
    assertThat(authenticatedSession).isNotEqualTo(oldSession);
    var session = client.getForObject("/api/v1/session", JsonNode.class);
    assertThat(session.path("can_edit").asBoolean()).isTrue();
    // A pre-login token must not authorize the new authenticated session.
    headers.setContentType(MediaType.APPLICATION_JSON);
    assertThat(
            client
                .postForEntity(
                    "/api/v1/osm/points/add", new HttpEntity<>(json, headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    headers.set(session.path("csrf_header").asText(), session.path("csrf_token").asText());
    var added =
        client.postForEntity(
            "/api/v1/osm/points/add", new HttpEntity<>(json, headers), JsonNode.class);
    assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String addedId = added.getBody().path("points").get(0).path("id").asText();
    assertThat(
            client
                .exchange(
                    "/api/v1/osm/points/" + addedId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            client
                .postForEntity("/logout", new HttpEntity<>(headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FOUND);
    assertThat(client.getForObject("/api/v1/session", JsonNode.class).path("can_edit").asBoolean())
        .isFalse();
    // Replaying the former authenticated cookie cannot revive the logged-out session.
    var replay = new org.springframework.boot.test.web.client.TestRestTemplate();
    headers.set(HttpHeaders.COOKIE, "JSESSIONID=" + authenticatedSession);
    assertThat(
            replay
                .exchange(
                    "http://localhost:" + port + "/api/v1/osm/points/user:" + id,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isIn(401, 403);
    unchanged();
  }
}
