package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "EDITING_ENABLED=true", "EDITOR_USERNAME=editor", "EDITOR_PASSWORD=test-editor-password-only",
      "CORS_ALLOWED_ORIGINS=", "server.error.include-message=always",
          "server.error.include-stacktrace=always"
    })
@AutoConfigureMockMvc
@Transactional
class ApiValidationTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private JsonNode error(MvcResult result, int status) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    assertThat(result.getResponse().getContentType()).startsWith("application/json");
    var body = mapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.size()).isEqualTo(2);
    assertThat(body.path("code").asText()).matches("[a-z][a-z0-9_]*");
    assertThat(body.path("message").asText()).isNotBlank();
    return body;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{",
        "[]",
        "{\"points\":null}",
        "{\"points\":[]}",
        "{\"points\":[[0,0]]}",
        "{\"points\":[[],[0,0]]}",
        "{\"points\":[null,[0,0]]}",
        "{\"points\":[[0],[0,0]]}",
        "{\"points\":[[0,0,1],[0,0]]}",
        "{\"points\":[[0,null],[0,0]]}",
        "{\"points\":[[0,91],[0,0]]}",
        "{\"points\":[[181,0],[0,0]]}",
        "{\"points\":[[-181,0],[0,0]]}",
        "{\"points\":[[0,-91],[0,0]]}",
        "{\"points\":[[\"1\",0],[0,0]]}",
        "{\"points\":[[true,0],[0,0]]}",
        "{\"points\":[[NaN,0],[0,0]]}",
        "{\"points\":[[1e309,0],[0,0]]}",
        "{\"points\":[[0,0],[0,0]],\"unknown\":1}",
        "{\"points\":[[0,0],[0,0]],\"points\":[[1,1],[2,2]]}",
        "{\"points\":[[0,0],[0,0]]} {}"
      })
  void rejectsMalformedDistanceBeforeComputing(String json) throws Exception {
    long before = jdbc.queryForObject("SELECT count(*) FROM app.user_points", Long.class);
    error(
        mvc.perform(post("/api/v1/osm/distances").contentType("application/json").content(json))
            .andReturn(),
        400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app.user_points", Long.class))
        .isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "[]",
        "[null]",
        "[{}]",
        "[{\"name\":\" \",\"lat\":0,\"lng\":0}]",
        "[{\"name\":123,\"lat\":0,\"lng\":0}]",
        "[{\"name\":true,\"lat\":0,\"lng\":0}]",
        "[{\"name\":\"test\",\"lat\":\"0\",\"lng\":0}]",
        "[{\"name\":\"test\",\"lat\":null,\"lng\":0}]",
        "[{\"name\":\"test\",\"lat\":91,\"lng\":0}]",
        "[{\"name\":\"test\",\"lat\":0,\"lng\":-181}]",
        "[{\"name\":\"test\",\"lat\":1e309,\"lng\":0}]",
        "[{\"name\":\"test\",\"lat\":0,\"lng\":0,\"owner\":\"other\"}]",
        "[{\"name\":\"\\u0000\",\"lat\":0,\"lng\":0}]",
        "[{\"name\":\"\\uD800\",\"lat\":0,\"lng\":0}]",
        "[{\"name\":\"\\uDC00\",\"lat\":0,\"lng\":0}]"
      })
  void rejectsMalformedPointBatchWithoutWriting(String json) throws Exception {
    long before = jdbc.queryForObject("SELECT count(*) FROM app.user_points", Long.class);
    error(
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(json))
            .andReturn(),
        400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app.user_points", Long.class))
        .isEqualTo(before);
  }

  @Test
  void boundsBothPostBatchSizesAndNameLength() throws Exception {
    error(
        mvc.perform(
                post("/api/v1/osm/distances")
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of("points", Collections.nCopies(1001, List.of(0, 0))))))
            .andReturn(),
        400);
    error(
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Collections.nCopies(
                                1001, Map.of("name", "bounded", "lat", 0, "lng", 0)))))
            .andReturn(),
        400);
    error(
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            List.of(Map.of("name", "x".repeat(256), "lat", 0, "lng", 0)))))
            .andReturn(),
        400);
  }

  @Test
  void accepts255UnicodeCodePointsWithoutTruncation() throws Exception {
    String name = "😀".repeat(255);
    var result =
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            List.of(Map.of("name", name, "lat", 0, "lng", 0)))))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(
            mapper
                .readTree(result.getResponse().getContentAsString())
                .path("points")
                .get(0)
                .path("name")
                .asText())
        .isEqualTo(name);
  }

  @Test
  void acceptsMaximumLegalBatchesAndZeroDistanceForRepeatedCoordinates() throws Exception {
    var request = Map.of("points", Collections.nCopies(1000, List.of(0, 0)));
    var response =
        mvc.perform(
                post("/api/v1/osm/distances")
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();
    var result = mapper.readTree(response.getResponse().getContentAsString());
    assertThat(result.path("lines").size()).isEqualTo(999);
    assertThat(result.path("totalDistance").asDouble()).isZero();
    String name = "x".repeat(255);
    var created =
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Collections.nCopies(1000, Map.of("name", name, "lat", 0, "lng", 0)))))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(mapper.readTree(created.getResponse().getContentAsString()).path("inserted").asInt())
        .isEqualTo(1000);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM app.user_points WHERE name=?", Long.class, name))
        .isEqualTo(1000);
  }

  @Test
  void acceptsWgs84PolesFor4326UserStorageAndGeodesicDistances() throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content("[{\"name\":\"pole\",\"lat\":90,\"lng\":180}]"))
            .andExpect(status().isCreated())
            .andReturn();
    String id =
        mapper
            .readTree(response.getResponse().getContentAsString())
            .path("points")
            .get(0)
            .path("id")
            .asText();
    assertThat(
            jdbc.queryForObject(
                "SELECT ST_SRID(geom) FROM app.user_points WHERE id=?",
                Integer.class,
                UUID.fromString(id.substring(5))))
        .isEqualTo(4326);
    var distance =
        mvc.perform(
                post("/api/v1/osm/distances")
                    .contentType("application/json")
                    .content("{\"points\":[[180,90],[-180,-90]]}"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            mapper
                .readTree(distance.getResponse().getContentAsString())
                .path("totalDistance")
                .asDouble())
        .isBetween(20_000_000d, 20_010_000d);
  }

  @Test
  void deletionPreservesStatusSemanticsAndRejectsNoncanonicalIds() throws Exception {
    error(
        mvc.perform(
                delete("/api/v1/osm/points/user:1-1-1-1-1")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf()))
            .andReturn(),
        400);
    error(
        mvc.perform(
                delete("/api/v1/osm/points/user:" + UUID.randomUUID())
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf()))
            .andReturn(),
        404);
    error(
        mvc.perform(
                delete("/api/v1/osm/points/osm:1")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf()))
            .andReturn(),
        403);
  }

  @Test
  void frameworkAndSecurityFailuresShareTheSameSafeErrorShape() throws Exception {
    error(
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(csrf())
                    .contentType("application/json")
                    .content("[]"))
            .andReturn(),
        401);
    error(
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .contentType("application/json")
                    .content("[]"))
            .andReturn(),
        403);
    error(mvc.perform(get("/api/v1/osm/points/add")).andReturn(), 405);
    error(mvc.perform(get("/api/v1/missing")).andReturn(), 404);
    error(
        mvc.perform(post("/api/v1/osm/distances").contentType("text/plain").content("text"))
            .andReturn(),
        415);
    error(mvc.perform(get("/api/v1/osm/points").accept("application/xml")).andReturn(), 406);
    error(
        mvc.perform(
                options("/api/v1/osm/points/add")
                    .header("Origin", "https://untrusted.example")
                    .header("Access-Control-Request-Method", "POST"))
            .andReturn(),
        403);
    error(mvc.perform(get("/api/v1/osm/points;bad")).andReturn(), 400);
  }

  @Test
  void rejectsExcessiveJsonStringsBeforeWriting() throws Exception {
    long before = jdbc.queryForObject("SELECT count(*) FROM app.user_points", Long.class);
    error(
        mvc.perform(
                post("/api/v1/osm/points/add")
                    .with(user("editor").roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            List.of(Map.of("name", "x".repeat(5000), "lat", 0, "lng", 0)))))
            .andReturn(),
        400);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app.user_points", Long.class))
        .isEqualTo(before);
  }
}
