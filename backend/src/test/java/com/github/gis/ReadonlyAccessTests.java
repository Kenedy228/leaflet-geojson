package com.github.gis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "EDITING_ENABLED=false")
@AutoConfigureMockMvc
class ReadonlyAccessTests {
  @Autowired TestRestTemplate client;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  @Test
  void readonlyModeDeniesEvenAnAuthenticatedEditor() throws Exception {
    String name = "readonly-editor-test-" + UUID.randomUUID();
    UUID id =
        jdbc.queryForObject(
            "INSERT INTO app.user_points(name,geom) VALUES (?,"
                + "ST_SetSRID(ST_MakePoint(35.35,52.34),4326)) RETURNING id",
            UUID.class,
            name);
    try {
      mvc.perform(
              post("/api/v1/osm/points/add")
                  .with(user("editor").roles("EDITOR"))
                  .with(csrf())
                  .contentType("application/json")
                  .content("[{\"name\":\"" + name + "\",\"lat\":52.34,\"lng\":35.35}]"))
          .andExpect(status().isForbidden());
      mvc.perform(
              delete("/api/v1/osm/points/user:" + id)
                  .with(user("editor").roles("EDITOR"))
                  .with(csrf()))
          .andExpect(status().isForbidden());
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.user_points WHERE name=?", Long.class, name))
          .isEqualTo(1);
    } finally {
      jdbc.update("DELETE FROM app.user_points WHERE id=?", id);
    }
  }

  @Test
  void anonymousCreateCannotChangeTheDatabase() {
    String name = "access-test-" + UUID.randomUUID();
    try {
      var response =
          client.postForEntity(
              "/api/v1/osm/points/add",
              List.of(Map.of("name", name, "lat", 52.34, "lng", 35.35)),
              String.class);
      assertThat(response.getStatusCode().value()).isIn(401, 403);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.user_points WHERE name=?", Long.class, name))
          .isZero();
    } finally {
      jdbc.update("DELETE FROM app.user_points WHERE name=?", name);
    }
  }

  @Test
  void anonymousDeleteCannotChangeTheDatabase() {
    UUID id =
        jdbc.queryForObject(
            """
                INSERT INTO app.user_points(name,geom)
                VALUES ('access-test',ST_SetSRID(ST_MakePoint(35.35,52.34),4326)) RETURNING id
                """,
            UUID.class);
    try {
      var response =
          client.exchange("/api/v1/osm/points/user:" + id, HttpMethod.DELETE, null, String.class);
      assertThat(response.getStatusCode().value()).isIn(401, 403);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM app.user_points WHERE id=?", Long.class, id))
          .isEqualTo(1);
    } finally {
      jdbc.update("DELETE FROM app.user_points WHERE id=?", id);
    }
  }
}
